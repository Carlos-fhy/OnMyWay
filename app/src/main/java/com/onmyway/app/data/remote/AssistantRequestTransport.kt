package com.onmyway.app.data.remote

import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.sync.BackupCredentials
import org.json.JSONObject

/** The request body is persisted before either operation; recovery never creates a new ID. */
internal interface AssistantRequestTransport {
    suspend fun submit(server: String, body: JSONObject): JSONObject
    suspend fun status(server: String, body: JSONObject): JSONObject
}

internal object HttpAssistantRequestTransport : AssistantRequestTransport {
    override suspend fun submit(server: String, body: JSONObject) = Api(server).assistant(body)
    override suspend fun status(server: String, body: JSONObject) = Api(server).assistantStatus(body)
}

/** Resolve the credential at transport time so persisted requests never contain a secret. */
internal class AuthenticatedAssistantRequestTransport(private val context: android.content.Context) : AssistantRequestTransport {
    private suspend fun token(server: String, body: JSONObject): String? {
        if (body.optInt("memory_contract") != 2 || body.opt("memory_enabled") != true) return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BackupCredentials.load(context)?.takeIf { it.server == Api.validateBase(server) }?.token
        }
            ?: throw java.io.IOException("个人记忆连接已变化，请恢复电脑连接后获取本次结果。")
    }
    override suspend fun submit(server: String, body: JSONObject) = Api(server).assistant(body, token(server, body))
    override suspend fun status(server: String, body: JSONObject) = Api(server).assistantStatus(body, token(server, body))
}

/** Shared by the UI snapshot and Room's transaction so a late reply cannot target a new trip. */
internal fun assistantTripContext(trip: SavedTrip?): String {
    if (trip == null) return "none"
    val content = JSONObject().put("session", trip.sessionId).put("plan", trip.planId)
        .put("plan_version", trip.planVersion).put("needs_replan", trip.needsReplan)
        .put("request", trip.requestJson).put("result", trip.resultJson)
        .put("started", trip.started).put("started_at", trip.startedAt)
        .put("planned_at", trip.plannedAt).toString()
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
