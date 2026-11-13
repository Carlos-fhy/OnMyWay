package com.onmyway.app.core.state

internal data class PlanningRequestOwner(
    val kind: String,
    val conversationId: String = "",
    val draftId: String = "",
    val planId: String = "",
    val sessionId: String = "",
    val planVersion: Int = 0,
)

internal data class PendingPlanningRequest(
    val runId: String,
    val server: String,
    val bodyJson: String,
    val operation: String,
    val correlationId: String,
    val owner: PlanningRequestOwner,
    val createdAt: Long,
)

internal interface PlanningRequestStore {
    suspend fun pending(): PendingPlanningRequest?
    suspend fun create(request: PendingPlanningRequest, replaceRunId: String? = null)
    suspend fun finish(runId: String): Boolean
}
