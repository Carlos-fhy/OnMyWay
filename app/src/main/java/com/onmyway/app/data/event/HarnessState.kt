package com.onmyway.app.data.event

import java.io.IOException
import org.json.JSONObject

internal class HarnessStateProtocolException : IOException("状态回包无法核对，请检查电脑服务版本。")

internal data class HarnessPlanIdentity(val id: String, val version: Int, val routeSessionId: String?)

internal data class HarnessState(
    val correlationId: String,
    val recordedCount: Int,
    val currentPlan: HarnessPlanIdentity?,
    val executionState: String,
    val latestObservationMatches: Boolean?,
) {
    val executionLabel: String get() = when (executionState) {
        "not_started" -> "尚未收到导航开始回执"
        "started" -> "导航已开始"
        "arrived" -> "导航已到达"
        "cancelled" -> "导航已取消"
        "failed" -> "导航执行失败"
        else -> "执行状态未知"
    }

    companion object {
        fun fromJson(value: JSONObject, expectedCorrelation: String): HarnessState = try {
            require(value.opt("recorded_is_not_executed") == true)
            val correlation = value.requiredUuid("correlation_id")
            require(correlation == expectedCorrelation)
            val countValue = value.getJSONObject("recorded").get("count")
            require(countValue is Int || countValue is Long)
            val count = (countValue as Number).toLong().also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
            val plan = value.optJSONObject("current_plan")?.let(::identity)
            val execution = value.optJSONObject("current_plan_execution")
            val state = if (execution == null) "unknown" else {
                require(plan != null && identity(execution) == plan)
                execution.requiredText("state").also {
                    require(it in setOf("not_started", "started", "arrived", "cancelled", "failed", "unknown"))
                    if (it !in setOf("unknown", "not_started")) require(!plan.routeSessionId.isNullOrBlank())
                }
            }
            val match = value.opt("execution_matches_recorded_plan")
            require(match == null || match == JSONObject.NULL || match is Boolean)
            HarnessState(correlation, count, plan, state, match as? Boolean)
        } catch (_: Exception) {
            throw HarnessStateProtocolException()
        }

        private fun identity(value: JSONObject): HarnessPlanIdentity = HarnessPlanIdentity(
            value.requiredText("plan_id"), value.requiredPositiveInt("plan_version"),
            if (value.isNull("route_session_id")) null else value.requiredText("route_session_id"),
        )
    }
}
