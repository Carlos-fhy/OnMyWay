package com.onmyway.app.core.model

/** Backend-evaluated eligibility; the phone only maps IDs back to local errands. */
data class RouteCandidateState(
    val taskId: String,
    val title: String,
    val effectiveAt: String?,
    val reason: String,
)

data class RouteCandidatesResult(
    val candidates: List<RouteCandidateState>,
    val blocked: List<RouteCandidateState>,
    val selectedIds: Set<String>,
) {
    val blockedById: Map<String, String> get() = blocked.associate { it.taskId to it.reason }
}

fun routeBlockedLabel(reason: String) = when (reason) {
    "INDOOR" -> "不用出门，不参与路线"
    "MISSING_PLACE" -> "待补办理地点"
    "EXPIRED" -> "已过截止时间"
    else -> "暂不能参与路线"
}
