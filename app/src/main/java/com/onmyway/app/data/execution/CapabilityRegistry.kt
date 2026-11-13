package com.onmyway.app.data.execution

import org.json.JSONObject

internal data class CapabilityContract(
    val id: String,
    val version: Int,
    val executor: String,
    val inputContract: String,
    val outputContract: String,
    val permission: String,
    val confirmationRequired: Boolean,
    val recovery: String,
) {
    fun validate(parameters: JSONObject) {
        val keys = parameters.keys().asSequence().toSet()
        when (id) {
            "read_tasks" -> require(keys == setOf("context_snapshot") && parameters.optJSONObject("context_snapshot") != null) {
                "读取任务需要当前上下文"
            }
            "plan_route" -> {
                require(keys.all { it in setOf("origin", "destination", "tasks", "mode", "preference", "departure",
                    "arrive_by", "max_extra_minutes", "allow_partial", "context_snapshot", "operation") }) { "规划参数未登记" }
                require(!parameters.has("operation") || parameters.opt("operation") in setOf("plan", "recommend")) {
                    "规划操作未登记"
                }
                require(parameters.optJSONObject("context_snapshot") != null && parameters.optJSONObject("origin") != null &&
                    parameters.optJSONObject("destination") != null && parameters.optJSONArray("tasks") != null &&
                    parameters.optString("mode") in setOf("walking", "cycling")) { "规划参数不完整" }
            }
            "start_navigation" -> require(keys == setOf("plan_id", "plan_version", "session_id") &&
                parameters.optString("plan_id").isNotBlank() && parameters.optString("session_id").isNotBlank() &&
                parameters.opt("plan_version") is Int && parameters.getInt("plan_version") > 0) { "导航计划身份无效" }
        }
    }
}

/** Capability identity is shared with the PC registry; each executor validates its own authority. */
internal object CapabilityRegistry {
    val capabilities = listOf(
        CapabilityContract("read_tasks", 1, "android", "ContextSnapshot", "TaskSnapshot", "local_tasks", false, "read_saved_result"),
        CapabilityContract("plan_route", 1, "server", "PlanRequest+ContextSnapshot", "PlanResult", "local_context_and_network", false, "reconcile_no_replay"),
        CapabilityContract("start_navigation", 1, "android", "ConfirmedPlanIdentity", "NavigationReceipt", "location_and_foreground_service", true, "exact_attempt_receipts"),
    )
    fun require(id: String, version: Int): CapabilityContract = requireNotNull(capabilities.find { it.id == id && it.version == version }) {
        "能力或版本未登记"
    }
}
