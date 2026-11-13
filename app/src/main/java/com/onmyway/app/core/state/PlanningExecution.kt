package com.onmyway.app.core.state

import com.onmyway.app.data.execution.CapabilityRunStore
import com.onmyway.app.data.execution.CapabilityRuntime
import com.onmyway.app.data.execution.CapabilityRegistry
import com.onmyway.app.data.execution.PlanningRunStatus
import com.onmyway.app.data.local.entity.CapabilityRun
import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.remote.ApiHttpException
import com.onmyway.app.data.sync.canonicalJson
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

/** One immutable planning request, including the status lookup after a lost response. */
internal class PlanningExecution(
    private val store: CapabilityRunStore,
    private val submit: suspend (String, JSONObject, String, String) -> JSONObject,
    private val status: (suspend (String, JSONObject, String, String) -> PlanningRunStatus)?,
    private val requests: PlanningRequestStore? = null,
    private val owner: PlanningRequestOwner = PlanningRequestOwner("manual"),
) {
    private val runtime = CapabilityRuntime(store)

    suspend fun execute(server: String, body: JSONObject, operation: String, correlationId: String,
        requireCurrent: suspend () -> Unit): JSONObject {
        require(operation in setOf("plan", "recommend")) { "规划操作未登记" }
        val endpoint = Api.validateBase(server)
        val payload = canonicalJson(JSONObject(body.toString()))
        val runId = identity(endpoint, operation, payload)
        val parameters = JSONObject(payload).put("operation", operation)
        val correlation = store.load(runId)?.correlationId ?: correlationId
        requests?.let { journal ->
            val previous = journal.pending()
            if (previous != null) {
                check(previous.owner == owner) { "上次规划属于另一项建议，请先核对或放弃原请求。" }
                if (previous.runId != runId) {
                    check(store.load(previous.runId)?.state in setOf("completed", "failed")) {
                        "上次规划结果尚未确认，请先核对原请求；不会重新规划。"
                    }
                }
            }
            requireCurrent()
            val pending = if (previous?.runId == runId) previous else PendingPlanningRequest(
                runId, endpoint, payload, operation, correlation, owner, System.currentTimeMillis(),
            )
            check(pending.server == endpoint && pending.bodyJson == payload && pending.operation == operation &&
                pending.correlationId == correlation) { "原规划请求与执行身份不一致。" }
            journal.create(pending, previous?.runId)
        }
        val reconcile = status?.let { lookup ->
            suspend { lookup(endpoint, JSONObject(payload), runId, operation) }
        }
        var submitted = false
        suspend fun attempt() = runtime.execute(runId, "plan_route", 1, parameters, correlation,
            requireCurrent, reconcile = reconcile) {
            submitted = true
            submit(endpoint, JSONObject(payload), runId, operation)
        }
        return try { attempt() }
        catch (error: IOException) {
            val rejected = error is ApiHttpException && error.status in 400..499 && error.status != 409
            if (!submitted || rejected || reconcile == null) throw error
            // The runtime has already persisted unknown. Re-enter only to query that run;
            // it must never invoke submit a second time, including a not_found response.
            attempt()
        }
    }

    /** Startup must never take the first-submit branch, including a crash before the run row exists. */
    suspend fun recover(request: PendingPlanningRequest, requireCurrent: suspend () -> Unit): JSONObject {
        check(requests?.pending() == request && request.owner == owner) { "待恢复规划已经变化。" }
        val endpoint = Api.validateBase(request.server)
        val payload = canonicalJson(JSONObject(request.bodyJson))
        check(identity(endpoint, request.operation, payload) == request.runId) { "原规划请求身份无效。" }
        val parameters = JSONObject(payload).put("operation", request.operation)
        store.transaction {
            requireCurrent()
            if (store.load(request.runId) == null) store.save(CapabilityRun(
                request.runId, "plan_route", 1, CapabilityRegistry.require("plan_route", 1).executor, "unknown", "awaiting_reconciliation",
                canonicalJson(parameters), request.correlationId, createdAt = request.createdAt,
                updatedAt = System.currentTimeMillis(),
            ))
        }
        return runtime.execute(request.runId, "plan_route", 1, parameters, request.correlationId,
            requireCurrent = {
                check(requests?.pending() == request) { "待恢复规划已经变化。" }
                requireCurrent()
            }, reconcile = status?.let { lookup ->
                suspend { lookup(endpoint, JSONObject(payload), request.runId, request.operation) }
            }) { error("恢复只能核对原执行，不能重新提交。") }
    }

    companion object {
        fun identity(endpoint: String, operation: String, payload: String): String = UUID.nameUUIDFromBytes(
            "onmyway:plan_route:1:${Api.validateBase(endpoint)}:$operation:${canonicalJson(JSONObject(payload))}".toByteArray(Charsets.UTF_8),
        ).toString()
    }
}
