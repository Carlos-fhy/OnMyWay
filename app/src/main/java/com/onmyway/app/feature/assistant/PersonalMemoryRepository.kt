package com.onmyway.app.feature.assistant

import android.content.Context
import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.remote.ApiHttpException
import com.onmyway.app.data.sync.BackupCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Separate snapshots for each configured service and credential; no credential enters the cache. */
internal class PersonalMemoryRepository(private val context: Context) {
    private val clients = mutableMapOf<String, PersonalMemoryClient>()

    private fun controls(server: String) = context.getSharedPreferences(
        "personal_memory_controls_" + personalMemoryCacheKey(Api.validateBase(server), "device-controls"), Context.MODE_PRIVATE,
    )
    fun localControls(server: String): Pair<Boolean, Boolean> = controls(server).let {
        it.getBoolean("reference_allowed", true) to it.getBoolean("learning_allowed", true)
    }
    fun setLocalControls(server: String, reference: Boolean, learning: Boolean) {
        check(controls(server).edit().putBoolean("reference_allowed", reference).putBoolean("learning_allowed", learning).commit()) {
            "本机记忆开关保存失败。"
        }
    }

    @Synchronized fun client(server: String): PersonalMemoryClient? {
        val base = Api.validateBase(server)
        val credential = BackupCredentials.load(context)?.takeIf { it.server == base } ?: return null
        val partition = personalMemoryCacheKey(base, credential.token)
        return clients.getOrPut(partition) {
            val prefs = context.getSharedPreferences("personal_memory_$partition", Context.MODE_PRIVATE)
            val cache = object : PersonalMemoryCache {
                override fun read(): String? = prefs.getString("snapshot", null)
                override fun write(value: String) { check(prefs.edit().putString("snapshot", value).commit()) { "记忆缓存保存失败。" } }
                override fun readPending(): String? = prefs.getString("pending", null)
                override fun writePending(value: String?) {
                    check(prefs.edit().putString("pending", value).commit()) { "记忆更新记录保存失败。" }
                }
            }
            val transport = object : PersonalMemoryTransport {
                override suspend fun read() = Api(base).personalMemory(credential.token)
                override suspend fun command(body: JSONObject): JSONObject = try {
                    Api(base).personalMemoryCommand(credential.token, body)
                } catch (error: ApiHttpException) {
                    if (error.status in setOf(400, 401, 403, 404, 409, 413, 422))
                        throw PersonalMemoryCommandRejected(error.message ?: "服务拒绝本次更新，请刷新后重试。")
                    throw error
                }
            }
            PersonalMemoryClient(cache, transport)
        }
    }

    suspend fun refresh(server: String): PersonalMemorySnapshot? = withContext(Dispatchers.IO) { client(server)?.refresh() }
}
