package com.onmyway.app

internal data class ConfirmedRouteSnapshot(
    val versionId: String,
    val requestJson: String,
    val routeJson: String,
    val previous: SavedTrip?,
    val tasks: List<Errand>,
    val createdAt: Long,
    val planId: String = previous?.planId ?: java.util.UUID.randomUUID().toString(),
    val draftId: String? = null,
)

internal fun validateRouteConfirmation(createdAt: Long, now: Long, sameTrip: Boolean, sameTasks: Boolean) {
    check(createdAt > 0 && now >= createdAt && now - createdAt <= 5 * 60_000L) {
        "方案已过期，请重新定位并计算。"
    }
    check(sameTrip) { "行程已变化，请重新计算后确认。" }
    check(sameTasks) { "待办已变化，请重新计算后确认。" }
}
