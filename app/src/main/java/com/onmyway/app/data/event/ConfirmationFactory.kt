package com.onmyway.app.data.event

import com.onmyway.app.data.local.entity.ConfirmationOutbox
import com.onmyway.app.data.local.entity.Errand
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * One stable receipt id per `(subject type, subject id, version)`. Retries reuse
 * the row; a changed proposal or replanned route gets the next version and a
 * different id. Snapshots contain business identifiers and outcomes only.
 */
internal object ConfirmationIds {
    fun forVersion(subjectType: String, subjectId: String, version: Int): String =
        UUID.nameUUIDFromBytes(
            "onmyway-confirmation:v1:$subjectType:$subjectId:$version".toByteArray(Charsets.UTF_8),
        ).toString()
}

internal object ConfirmationFactory {
    const val SCHEMA_VERSION = 1

    fun proposal(
        subjectId: String,
        version: Int,
        correlationId: String,
        confirmedAt: Long,
        task: Errand,
    ) = receipt(
        subjectType = "proposal",
        subjectId = subjectId,
        subjectVersion = version,
        correlationId = correlationId,
        confirmedAt = confirmedAt,
        snapshot = JSONObject()
            .put("subject_id", subjectId)
            .put("task_id", task.id)
            .put("title", task.title)
            .put("place_type", task.placeType)
            .put("has_place", task.placeJson.isNotBlank())
            .put("planned_date", task.plannedDate),
    )

    fun plan(
        planId: String,
        sessionId: String,
        version: Int,
        correlationId: String,
        confirmedAt: Long,
        request: JSONObject,
        route: JSONObject,
        replanned: Boolean,
    ) = receipt(
        subjectType = "plan",
        subjectId = planId,
        subjectVersion = version,
        correlationId = correlationId,
        confirmedAt = confirmedAt,
        snapshot = JSONObject()
            .put("plan_id", planId)
            .put("plan_version", version)
            .put("route_session_id", sessionId)
            .put("mode", request.optString("mode"))
            .put("selected_task_count", request.optJSONArray("tasks")?.length() ?: 0)
            .put("selected_task_ids", taskIds(request))
            .put("total_seconds", route.optInt("total_seconds", -1).takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("total_meters", route.optInt("total_meters", -1).takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("replanned", replanned),
    )

    private fun receipt(
        subjectType: String,
        subjectId: String,
        subjectVersion: Int,
        correlationId: String,
        confirmedAt: Long,
        snapshot: JSONObject,
    ): ConfirmationOutbox {
        // The server bounds every field too; failing here would lose the local
        // confirmation, so only positive versions are asserted.
        require(subjectId.isNotBlank() && subjectVersion > 0) { "确认版本无效" }
        return ConfirmationOutbox(
            receiptId = ConfirmationIds.forVersion(subjectType, subjectId, subjectVersion),
            subjectType = subjectType,
            subjectId = subjectId,
            subjectVersion = subjectVersion,
            correlationId = correlationId,
            confirmedAt = confirmedAt,
            snapshotJson = snapshot.toString(),
            createdAt = confirmedAt,
        )
    }

    private fun taskIds(request: JSONObject): JSONArray {
        val ids = JSONArray()
        val tasks = request.optJSONArray("tasks") ?: return ids
        for (index in 0 until tasks.length()) {
            tasks.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(ids::put)
        }
        return ids
    }
}
