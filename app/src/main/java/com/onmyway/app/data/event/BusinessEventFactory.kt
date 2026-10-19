package com.onmyway.app.data.event

import com.onmyway.app.core.model.TripMetrics
import com.onmyway.app.data.local.entity.BusinessEventOutbox
import com.onmyway.app.data.local.entity.Errand
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the v1 envelope exactly once. Payloads contain business identifiers
 * and outcomes only: no notification text, place coordinates or credentials.
 */
internal object BusinessEventFactory {
    const val SCHEMA_VERSION = 1
    const val SOURCE_USER = "user"
    const val SOURCE_DEVICE = "device"

    private fun receipt(ref: String): String = JSONArray().put(
        JSONObject().put("kind", "local_receipt").put("ref", ref),
    ).toString()

    fun taskConfirmed(
        task: Errand,
        draftId: String,
        receiptRef: String,
        correlationId: String,
        occurredAt: Long,
    ) = BusinessEventOutbox(
        eventId = BusinessEventIds.forObservation("TASK_CONFIRMED", task.id),
        correlationId = correlationId,
        kind = "TASK_CONFIRMED",
        source = SOURCE_USER,
        schemaVersion = SCHEMA_VERSION,
        occurredAt = occurredAt,
        evidenceJson = receipt(receiptRef),
        payloadJson = JSONObject()
            .put("task_id", task.id)
            .put("title", task.title)
            .put("place_type", task.placeType)
            .put("has_place", task.placeJson.isNotBlank())
            .put("planned_date", task.plannedDate)
            .put("draft_id", draftId)
            .toString(),
        createdAt = occurredAt,
    )

    fun taskCompleted(
        task: Errand,
        completionSource: String,
        correlationId: String,
        occurredAt: Long = task.completedAt,
    ) = BusinessEventOutbox(
        eventId = BusinessEventIds.forObservation("TASK_COMPLETED", "${task.id}:$occurredAt"),
        correlationId = correlationId,
        kind = "TASK_COMPLETED",
        source = SOURCE_USER,
        schemaVersion = SCHEMA_VERSION,
        occurredAt = occurredAt,
        evidenceJson = receipt("task:${task.id}"),
        payloadJson = JSONObject()
            .put("task_id", task.id)
            .put("title", task.title)
            .put("completion_source", completionSource)
            .toString(),
        createdAt = occurredAt,
    )

    fun routeConfirmed(
        historyId: String,
        sessionId: String,
        planId: String,
        request: JSONObject,
        route: JSONObject,
        correlationId: String,
        occurredAt: Long,
        replanned: Boolean,
    ) = BusinessEventOutbox(
        eventId = BusinessEventIds.forObservation("ROUTE_CONFIRMED", historyId),
        correlationId = correlationId,
        kind = "ROUTE_CONFIRMED",
        source = SOURCE_USER,
        schemaVersion = SCHEMA_VERSION,
        occurredAt = occurredAt,
        evidenceJson = receipt("route-history:$historyId"),
        payloadJson = JSONObject()
            .put("route_session_id", sessionId)
            .put("plan_id", planId)
            .put("mode", request.optString("mode"))
            .put("selected_task_count", request.optJSONArray("tasks")?.length() ?: 0)
            .put("selected_task_ids", taskIds(request))
            .put("total_seconds", route.optInt("total_seconds", -1).takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("total_meters", route.optInt("total_meters", -1).takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("replanned", replanned)
            .toString(),
        createdAt = occurredAt,
    )

    fun navigationStarted(
        sessionId: String,
        planId: String,
        request: JSONObject,
        correlationId: String,
        occurredAt: Long,
    ) = navigationEvent(
        eventId = BusinessEventIds.forObservation("NAVIGATION_STARTED", sessionId),
        kind = "NAVIGATION_STARTED",
        correlationId = correlationId,
        sessionId = sessionId,
        planId = planId,
        request = request,
        metrics = null,
        occurredAt = occurredAt,
    )

    fun navigationArrived(
        sessionId: String,
        planId: String,
        request: JSONObject,
        metrics: TripMetrics?,
        correlationId: String,
        occurredAt: Long = metrics?.endedAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
    ) = navigationEvent(
        eventId = BusinessEventIds.forObservation("NAVIGATION_ARRIVED", sessionId),
        kind = "NAVIGATION_ARRIVED",
        correlationId = correlationId,
        sessionId = sessionId,
        planId = planId,
        request = request,
        metrics = metrics,
        occurredAt = occurredAt,
    )

    fun navigationCancelled(
        sessionId: String,
        planId: String,
        request: JSONObject,
        metrics: TripMetrics?,
        reason: String,
        correlationId: String,
        occurredAt: Long = System.currentTimeMillis(),
    ) = navigationEvent(
        eventId = BusinessEventIds.forObservation("NAVIGATION_CANCELLED", sessionId),
        kind = "NAVIGATION_CANCELLED",
        correlationId = correlationId,
        sessionId = sessionId,
        planId = planId,
        request = request,
        metrics = metrics,
        occurredAt = occurredAt,
        reason = reason,
    )

    fun navigationFailed(
        sessionId: String,
        planId: String,
        request: JSONObject,
        reason: String,
        correlationId: String,
        occurredAt: Long = System.currentTimeMillis(),
    ) = navigationEvent(
        // Every failed attempt is a distinct observation, even in one session.
        eventId = BusinessEventIds.newObservation(),
        kind = "NAVIGATION_FAILED",
        correlationId = correlationId,
        sessionId = sessionId,
        planId = planId,
        request = request,
        metrics = null,
        occurredAt = occurredAt,
        reason = reason,
    )

    private fun navigationEvent(
        eventId: String,
        kind: String,
        correlationId: String,
        sessionId: String,
        planId: String,
        request: JSONObject,
        metrics: TripMetrics?,
        occurredAt: Long,
        reason: String = "",
    ) = BusinessEventOutbox(
        eventId = eventId,
        correlationId = correlationId,
        kind = kind,
        source = SOURCE_DEVICE,
        schemaVersion = SCHEMA_VERSION,
        occurredAt = occurredAt,
        evidenceJson = receipt("route-session:$sessionId"),
        payloadJson = JSONObject()
            .put("route_session_id", sessionId)
            .put("plan_id", planId)
            .put("mode", request.optString("mode"))
            .put("selected_task_count", request.optJSONArray("tasks")?.length() ?: 0)
            .put("selected_task_ids", taskIds(request))
            .apply {
                if (metrics != null) {
                    put("actual_seconds", metrics.actualSeconds)
                    put("actual_meters", metrics.actualMeters ?: JSONObject.NULL)
                    put("predicted_seconds", metrics.predictedSeconds ?: JSONObject.NULL)
                    put("predicted_meters", metrics.predictedMeters ?: JSONObject.NULL)
                    put("reroutes", metrics.reroutes)
                }
                if (reason.isNotBlank()) put("reason", reason)
            }
            .toString(),
        createdAt = occurredAt,
    )

    private fun taskIds(request: JSONObject): JSONArray {
        val ids = JSONArray()
        val tasks = request.optJSONArray("tasks") ?: return ids
        for (index in 0 until tasks.length()) {
            tasks.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(ids::put)
        }
        return ids
    }
}
