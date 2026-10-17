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

    suspend fun taskProtocol(context: Context, credential: BackupCredential,
        journalName: String = "onmyway-sync.db"): Int = withContext(Dispatchers.IO) {
        SyncJournal(context, journalName).use {
            it.binding(syncScope(credential.server, credential.token))?.tasks?.protocol ?: 0
        }
    }

    private fun preventDowngrade(previous: TaskAuthority?, remote: TaskAuthority) {
        if (previous?.active == true && !remote.active)
            throw SyncConflict("任务服务协议已回退；已保留本机修改，请更新电脑后端后重试")
    }
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
                    var pending = journal.pending(scope) ?: return
                    if (pending.kind == "restore") {
                        pending.tasks.checkSnapshot(pending.payload)
                        repository.applySyncedSnapshot(pending.payload, pending.baseline)
                        journal.accept(scope, pending.storeId, pending.revision, repository.normalizedRestore(pending.payload), pending.tasks)
                        restored = true
                        return
                    }
                    var observedProtocol = journal.binding(scope)?.tasks?.protocol ?: 0
                    // Probe before every queued PUT. A downgraded legacy server may ignore
                    // task_commands, so rejecting its receipt after it writes would be too late.
                    val probe = transport.read()
                    val authority = TaskAuthority.from(probe)
                    preventDowngrade(journal.binding(scope)?.tasks, authority)
                    preventDowngrade(pending.tasks, authority)
                    authority.checkSnapshot(probe.optJSONObject("snapshot"))
                    if (probe.getString("store_id") != pending.storeId)
                        throw SyncConflict("数据服务身份已变化；原待提交记录已保留，请先确认服务迁移")
                    if (probe.getLong("revision") < pending.revision)
                        throw SyncConflict("电脑数据库版本回退；原待提交记录已保留，已暂停写入")
                    observedProtocol = maxOf(observedProtocol, authority.protocol)
                    if (pending.taskCommands == null) {
                        // A v1 queue may already have committed before an upgrade. Add commands
                        // only while its original base is current; otherwise replay the exact old
                        // body to recover its existing receipt (or report the conflict).
                        if (authority.active && probe.getLong("revision") == pending.revision) {
                            journal.upgradePending(scope, pending.requestId, authority,
                                taskCommands(probe.optJSONObject("snapshot"), pending.payload, authority))
                            pending = journal.pending(scope)!!
                        }
                    }
                    val result = transport.write(pending.request())
                    if (result.getString("store_id") != pending.storeId || result.getLong("revision") <= pending.revision)
                        throw SyncConflict("数据服务回执与提交不一致；已保留待提交记录")
                    val resultTasks = TaskAuthority.from(result)
                    preventDowngrade(pending.tasks, resultTasks)
                    resultTasks.checkSnapshot(pending.payload)
                    // Old receipts omit metadata. Retain the protocol floor, then GET metadata
                    // before another write; never attach later versions to this older baseline.
                    val acceptedTasks = if (resultTasks.active) resultTasks else TaskAuthority(observedProtocol)
                    journal.accept(scope, pending.storeId, result.getLong("revision"), pending.payload, acceptedTasks)
                }
                // Deliver the exact persisted request first after network failure or process death.
                deliver()
                repeat(3) {
                    val remote = transport.read()
                    val storeId = remote.getString("store_id")
                    val revision = remote.getLong("revision")
                    val remoteSnapshot = remote.optJSONObject("snapshot")
                    val authority = TaskAuthority.from(remote)
                    authority.checkSnapshot(remoteSnapshot)
                    val local = repository.snapshot()
                    val binding = journal.binding(scope)
                    val baseline = binding?.baseline?.let(repository::normalizedSchema)
                    preventDowngrade(binding?.tasks, authority)
                    fun stage(kind: String, payload: JSONObject, baseline: JSONObject,
                        taskBaseline: JSONObject? = baseline) {
                        journal.stage(scope, kind, storeId, revision, payload, baseline, authority,
                            if (kind == "write" && authority.active) taskCommands(taskBaseline, payload, authority) else null)
                    }
                    if (binding == null) {
                        if (journal.hasOtherStore(storeId) && hasUserRecords(local))
                            throw SyncConflict("此手机已有其他数据服务的记录；请先处理账户或服务迁移")
                        if (remoteSnapshot != null) {
                            val normalized = repository.normalizedRestore(remoteSnapshot)
                            if (canonicalJson(local) == canonicalJson(normalized)) {
                                journal.accept(scope, storeId, revision, normalized, authority)
                            } else if (!hasUserRecords(local)) {
                                stage("restore", remoteSnapshot, local)
                                deliver()
                            } else {
                                throw SyncConflict("电脑和手机均有记录；为避免覆盖，已保留双方数据。请先合并或在空设备上连接")
                            }
                        } else {
                            stage("write", local, local, taskBaseline = null)
                            deliver()
                        }
                    } else {
                        if (binding.storeId != storeId) throw SyncConflict("数据服务身份已变化；手机记录已保留，请先确认服务迁移")
                        if (revision != binding.revision) {
                            if (revision < binding.revision || remoteSnapshot == null)
                                throw SyncConflict("电脑数据库版本回退；已暂停写入，手机记录已保留")
                            if (canonicalJson(local) != canonicalJson(baseline))
                                throw SyncConflict("电脑和手机都有未合并的修改；同步已暂停，双方数据均保留")
                            val trips = local.getJSONObject("tables").getJSONArray("saved_trip")
                            if ((0 until trips.length()).any { trips.getJSONObject(it).optInt("started") != 0 })
                                throw SyncConflict("当前正在导航；已保留路线，请结束导航后再拉取电脑修改")
                            stage("restore", remoteSnapshot, local)
                            deliver()
                        } else if (canonicalJson(local) != canonicalJson(baseline)) {
                            stage("write", local, baseline!!)
                            deliver()
                        } else {
                            // Server migration may introduce task versions without modifying
                            // user records or the snapshot revision. Persist negotiation too.
                            journal.accept(scope, storeId, revision, baseline!!, authority)
                            return@withContext restored
                        }
                    }
                }
                // More edits may arrive while syncing. Keep the job alive instead of marking them synced.
                if (canonicalJson(repository.snapshot()) != canonicalJson(journal.binding(scope)?.baseline?.let(repository::normalizedSchema)))
                    throw java.io.IOException("还有本地修改等待同步，将继续重试")
            }
            restored
        }
    }
}
