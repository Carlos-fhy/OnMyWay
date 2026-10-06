package com.onmyway.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class SyncConflict(message: String) : IllegalStateException(message)
internal interface SyncTransport {
    suspend fun read(): JSONObject
    suspend fun write(body: JSONObject): JSONObject
}
internal class HttpSyncTransport(private val credential: BackupCredential) : SyncTransport {
    private val api = Api(credential.server)
    override suspend fun read() = api.readSync(credential.token)
    override suspend fun write(body: JSONObject) = api.writeSync(credential.token, body)
}
internal object PcSyncEngine {
    private val mutex = Mutex()
    /** Returns true when remote records were restored; the screen should reload its view models. */
    suspend fun synchronize(context: Context, credential: BackupCredential,
        repository: BackupRepository = BackupRepository(context, TaskDatabase.get(context)),
        transport: SyncTransport = HttpSyncTransport(credential),
        journalName: String = "onmyway-sync.db"): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val scope = syncScope(credential.server, credential.token)
            var restored = false
            SyncJournal(context, journalName).use { journal ->
                suspend fun deliver() {
                    val pending = journal.pending(scope) ?: return
                    if (pending.kind == "restore") {
                        repository.applySyncedSnapshot(pending.payload, pending.baseline)
                        journal.accept(scope, pending.storeId, pending.revision, repository.normalizedRestore(pending.payload))
                        restored = true
                    } else {
                        val result = transport.write(JSONObject()
                            .put("store_id", pending.storeId).put("base_revision", pending.revision)
                            .put("request_id", pending.requestId).put("snapshot", pending.payload))
                        check(result.getString("store_id") == pending.storeId)
                        journal.accept(scope, pending.storeId, result.getLong("revision"), pending.payload)
                    }
                }
                // Deliver the exact persisted request first after network failure or process death.
                deliver()
                repeat(3) {
                    val remote = transport.read()
                    val storeId = remote.getString("store_id")
                    val revision = remote.getLong("revision")
                    val remoteSnapshot = remote.optJSONObject("snapshot")
                    val local = repository.snapshot()
                    val binding = journal.binding(scope)
                    if (binding == null) {
                        if (journal.hasOtherStore(storeId) && hasUserRecords(local))
                            throw SyncConflict("此手机已有其他数据服务的记录；请先处理账户或服务迁移")
                        if (remoteSnapshot != null) {
                            val normalized = repository.normalizedRestore(remoteSnapshot)
                            if (canonicalJson(local) == canonicalJson(normalized)) {
                                journal.accept(scope, storeId, revision, normalized)
                            } else if (!hasUserRecords(local)) {
                                journal.stage(scope, "restore", storeId, revision, remoteSnapshot, local)
                                deliver()
                            } else {
                                throw SyncConflict("电脑和手机均有记录；为避免覆盖，已保留双方数据。请先合并或在空设备上连接")
                            }
                        } else {
                            journal.stage(scope, "write", storeId, revision, local, local)
                            deliver()
                        }
                    } else {
                        if (binding.storeId != storeId) throw SyncConflict("数据服务身份已变化；手机记录已保留，请先确认服务迁移")
                        if (revision != binding.revision) {
                            if (revision < binding.revision || remoteSnapshot == null)
                                throw SyncConflict("电脑数据库版本回退；已暂停写入，手机记录已保留")
                            if (canonicalJson(local) != canonicalJson(binding.baseline))
                                throw SyncConflict("电脑和手机都有未合并的修改；同步已暂停，双方数据均保留")
                            val trips = local.getJSONObject("tables").getJSONArray("saved_trip")
                            if ((0 until trips.length()).any { trips.getJSONObject(it).optInt("started") != 0 })
                                throw SyncConflict("当前正在导航；已保留路线，请结束导航后再拉取电脑修改")
                            journal.stage(scope, "restore", storeId, revision, remoteSnapshot, local)
                            deliver()
                        } else if (canonicalJson(local) != canonicalJson(binding.baseline)) {
                            journal.stage(scope, "write", storeId, revision, local, binding.baseline)
                            deliver()
                        } else return@withContext restored
                    }
                }
                // More edits may arrive while syncing. Keep the job alive instead of marking them synced.
                if (canonicalJson(repository.snapshot()) != canonicalJson(journal.binding(scope)?.baseline))
                    throw java.io.IOException("还有本地修改等待同步，将继续重试")
            }
            restored
        }
    }
}
