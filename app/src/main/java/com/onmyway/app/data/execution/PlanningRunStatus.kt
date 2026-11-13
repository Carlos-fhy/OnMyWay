package com.onmyway.app.data.execution

import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class PlanningRunProtocolException : IOException("规划执行状态无法核对，请检查电脑服务版本。")

internal data class PlanningRunStatus(
    val runId: String,
    val operation: String,
    val state: String,
    val result: JSONObject?,
    val errorStatus: Int?,
) {
    companion object {
        fun fromJson(value: JSONObject, expectedRunId: String, expectedOperation: String): PlanningRunStatus {
            fun requireProtocol(condition: Boolean) {
                if (!condition) throw PlanningRunProtocolException()
            }
            requireProtocol(listOf("run_id", "capability_id", "operation", "state", "result", "error_status")
                .all(value::has))
            val runId = value.opt("run_id")
            val operation = value.opt("operation")
            val state = value.opt("state")
            requireProtocol(runId is String && runId == expectedRunId && runCatching {
                UUID.fromString(runId).toString().equals(runId, ignoreCase = true)
            }.getOrDefault(false))
            requireProtocol(value.opt("capability_id") == "plan_route")
            requireProtocol(operation in setOf("plan", "recommend") && operation == expectedOperation)
            requireProtocol(state in setOf("not_found", "running", "unknown", "failed", "completed"))
            val result = value.opt("result")
            requireProtocol(if (state == "completed") result is JSONObject else result === JSONObject.NULL)
            val errorStatus = value.opt("error_status")
            requireProtocol(errorStatus === JSONObject.NULL ||
                ((errorStatus is Int || errorStatus is Long) && (errorStatus as Number).toLong() in 400L..599L))
            return PlanningRunStatus(runId as String, operation as String, state as String,
                (result as? JSONObject)?.let { copyResult(it) as JSONObject }, (errorStatus as? Number)?.toInt())
        }

        private fun copyResult(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().apply {
                value.keys().forEach { key -> put(key, copyResult(value.opt(key))) }
            }
            is JSONArray -> JSONArray().apply {
                for (index in 0 until value.length()) put(copyResult(value.opt(index)))
            }
            else -> value
        }
    }
}
