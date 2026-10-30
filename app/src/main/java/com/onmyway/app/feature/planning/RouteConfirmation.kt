package com.onmyway.app.feature.planning

import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.core.context.ContextSnapshot

internal data class ConfirmedRouteSnapshot(
    val versionId: String,
    val requestJson: String,
    val routeJson: String,
    val previous: SavedTrip?,
    val tasks: List<Errand>,
    val createdAt: Long,
    val planId: String = previous?.planId ?: java.util.UUID.randomUUID().toString(),
    val draftId: String? = null,
    val contextSnapshot: ContextSnapshot = ContextSnapshot.capture(tasks, previous, now = createdAt),
)

internal fun validateRouteConfirmation(createdAt: Long, now: Long, sameTrip: Boolean, sameTasks: Boolean) {
    check(createdAt > 0 && now >= createdAt && now - createdAt <= 5 * 60_000L) {
        "方案已过期，请重新定位并计算。"
    }
    check(sameTrip) { "行程已变化，请重新计算后确认。" }
    check(sameTasks) { "待办已变化，请重新计算后确认。" }
}
