package com.onmyway.app

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

/** Shared by the UI snapshot and Room's transaction so a late reply cannot target a new trip. */
internal fun assistantTripContext(trip: SavedTrip?): String {
    if (trip == null) return "none"
    val content = JSONObject().put("session", trip.sessionId).put("plan", trip.planId)
        .put("request", trip.requestJson).put("result", trip.resultJson)
        .put("started", trip.started).put("started_at", trip.startedAt)
        .put("planned_at", trip.plannedAt).toString()
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
