package com.onmyway.app.data.execution

import com.onmyway.app.data.local.entity.CapabilityRun
import com.onmyway.app.data.remote.ApiHttpException
import com.onmyway.app.data.sync.canonicalJson
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal interface CapabilityRunStore {
    suspend fun <T> transaction(block: suspend () -> T): T
    suspend fun load(id: String): CapabilityRun?
    suspend fun save(run: CapabilityRun)
}

internal class CapabilityRuntime(private val store: CapabilityRunStore, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun execute(runId: String, capabilityId: String, version: Int, parameters: JSONObject,
        correlationId: String, requireCurrent: suspend () -> Unit,
        reconcile: (suspend () -> PlanningRunStatus)? = null, execute: suspend () -> JSONObject): JSONObject {
        val contract = CapabilityRegistry.require(capabilityId, version)
        require(capabilityId != "start_navigation") { "导航需要设备确认与执行回执" }
        val immutable = JSONObject(parameters.toString())
        contract.validate(immutable)
        require(runId.isNotBlank() && correlationId.isNotBlank()) { "执行身份缺失" }
        val input = canonicalJson(immutable)
        val existing = store.transaction {
            requireCurrent()
            val old = store.load(runId)
            if (old != null) {
                check(old.capabilityId == capabilityId && old.capabilityVersion == version &&
                    old.snapshotJson == input && old.correlationId == correlationId) { "执行编号已用于不同内容" }
                old
            } else {
                val time = now()
                store.save(CapabilityRun(runId, capabilityId, version, contract.executor, "running", "invoking",
                    input, correlationId, createdAt = time, updatedAt = time))
                null
            }
        }
        if (existing != null) {
            if (existing.state == "completed") {
                try {
                    return savedResult(existing, immutable)
                } catch (error: IllegalArgumentException) {
                    recordFailure(runId, error, invalidCache = existing)
                    throw error
                }
            }
            check(capabilityId == "plan_route" && existing.state in setOf("running", "unknown") && reconcile != null) {
                "该能力正在执行、已失败或结果未知，请先核对原执行记录；不会重复调用。"
            }
            val status = try {
                reconcile().also {
                    if (it.runId != runId || it.operation != immutable.optString("operation", "plan") ||
                        it.state !in setOf("not_found", "running", "unknown", "failed", "completed")) {
                        throw PlanningRunProtocolException()
                    }
                }
            } catch (error: Exception) {
                // A rejected status lookup says nothing about the original invocation.
                recordFailure(runId, error, statusLookup = true)
                throw error
            }
            if (status.state == "completed") {
                try {
                    return complete(runId, capabilityId, immutable, requireCurrent,
                        requireNotNull(status.result) { "已完成的执行缺少结果" })
                } catch (error: Exception) {
                    recordFailure(runId, error)
                    throw error
                }
            }
            val completed = store.transaction {
                requireCurrent()
                val row = checkNotNull(store.load(runId))
                if (row.state == "completed") savedResult(row, immutable) else {
                    // A late lookup must never downgrade a terminal result from another caller.
                    if (row.state in setOf("running", "unknown")) {
                        val failed = status.state == "failed"
                        store.save(row.copy(state = if (failed) "failed" else "unknown",
                            step = if (failed) "server_rejected" else "awaiting_reconciliation",
                            error = if (failed) "电脑服务确认执行失败" else "执行结果未确认", updatedAt = now()))
                    }
                    null
                }
            }
            if (completed != null) return completed
            error(if (status.state == "failed") "电脑服务确认执行失败，请检查输入后重新规划。"
                else "原执行结果尚未确认，请稍后核对；不会重复调用。")
        }
        try {
            return complete(runId, capabilityId, immutable, requireCurrent, execute())
        } catch (error: Exception) {
            recordFailure(runId, error)
            throw error
        }
    }

    private suspend fun complete(runId: String, capabilityId: String, parameters: JSONObject, requireCurrent: suspend () -> Unit,
        result: JSONObject): JSONObject {
        // JSONObject's serializer can turn 600.0 into 600; validate the original scalar types first.
        validateCapabilityResult(capabilityId, parameters, result)
        val immutable = JSONObject(result.toString())
        return store.transaction {
            requireCurrent()
            val row = checkNotNull(store.load(runId))
            validateCapabilityResult(row.capabilityId, parameters, immutable)
            if (row.state == "completed") savedResult(row, parameters) else {
                check(row.state in setOf("running", "unknown")) { "原执行已终止，请重新规划。" }
                store.save(row.copy(state = "completed", step = "result_saved", resultJson = immutable.toString(),
                    error = "", updatedAt = now()))
                immutable
            }
        }
    }

    private fun savedResult(row: CapabilityRun, parameters: JSONObject): JSONObject {
        val result = try {
            JSONObject(row.resultJson)
        } catch (error: org.json.JSONException) {
            throw IllegalArgumentException("保存的执行结果损坏", error)
        }
        validateCapabilityResult(row.capabilityId, parameters, result)
        return result
    }

    private suspend fun recordFailure(runId: String, error: Exception, statusLookup: Boolean = false,
        invalidCache: CapabilityRun? = null) = withContext(NonCancellable) {
        store.transaction {
            val row = checkNotNull(store.load(runId))
            if (invalidCache != null) {
                if (row != invalidCache) return@transaction
            } else if (row.state !in setOf("running", "unknown")) return@transaction
            // Cancellation, 409 and network/server failures cannot prove the planner did not run.
            val rejected = !statusLookup && error is ApiHttpException && error.status in 400..499 && error.status != 409
            val unknown = !rejected && (statusLookup || error is IOException || error is CancellationException)
            store.save(row.copy(state = if (unknown) "unknown" else "failed",
                step = if (unknown) "awaiting_reconciliation" else "validation_failed",
                resultJson = "", error = if (unknown) "执行结果未确认" else "输入或结果未通过当前版本校验",
                updatedAt = now()))
        }
    }
}
