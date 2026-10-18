package com.onmyway.app.feature.navigation

import com.onmyway.app.core.model.Place

/** External navigation launch is observable; actual progress inside Amap is not. */
enum class NavigationState { IDLE, PLANNING, READY, STARTING, NAVIGATING, PAUSED, FAILED, NAVIGATION_OPENED, REPLANNING, ARRIVED, CANCELLED }

data class RouteSession(val id: String, val state: NavigationState, val origin: Place, val destination: Place,
    val plannedAt: Long, val startedAt: Long, val requestJson: String, val routeJson: String,
    val planId: String = id)

/** Conservative V0.2 command extraction; ambiguous intents never run tools. */
fun destinationQuery(input: String): String? {
    val text = input.trim().trimEnd('。', '！', '!', '.', '？', '?')
    if (text.isBlank() || text.length > 100 || listOf("取消", "别去", "不去", "不要", "不想", "不回", "记下", "提醒", "然后", "顺便", "明天", "后天").any { it in text }) return null
    return text.replace(Regex("^(请|帮我)?(导航去|导航到|带我去|我?(现在)?(准备|要|想)?(去|回)|去|回)"), "")
        .trim().takeIf { it.isNotBlank() }
}
