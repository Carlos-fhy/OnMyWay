package com.onmyway.app.data.execution

import com.onmyway.app.data.local.entity.CapabilityRun
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityRuntimeTest {
    private class Store : CapabilityRunStore {
        val rows = mutableMapOf<String, CapabilityRun>()
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
        override suspend fun load(id: String) = rows[id]
        override suspend fun save(run: CapabilityRun) { rows[run.runId] = run }
    }
    private fun input() = JSONObject("""{"context_snapshot":{"snapshot_id":"snapshot"},"origin":{"id":"start","name":"学校","longitude":121.0,"latitude":31.0},"destination":{"id":"dorm","name":"宿舍","longitude":121.01,"latitude":31.01},"mode":"walking","tasks":[],"departure":"2026-11-03T09:00:00+08:00"}""")
    private fun route() = JSONObject("""{"mode":"walking","baseline_seconds":600,"baseline_meters":500,"travel_seconds":600,"service_seconds":0,"total_seconds":600,"total_meters":500,"extra_seconds":0,"extra_travel_seconds":0,"extra_meters":0,"arrival":"2026-11-03T09:10:00+08:00","stops":[],"legs":[{"seconds":600,"meters":500,"polyline":""}],"omitted":[],"warnings":[],"solver_status":"direct"}""")
        .put("origin", input().getJSONObject("origin")).put("destination", input().getJSONObject("destination"))

    @Test fun duplicateReturnsSavedResultWithoutCallingPlannerAgain() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store) { 100L }
        var calls = 0
        repeat(2) {
            val result = runtime.execute("run", "plan_route", 1, input(), "correlation", {}) {
                calls++; route()
            }
            assertEquals("宿舍", result.getJSONObject("destination").getString("name"))
        }
        assertEquals(1, calls)
        assertEquals("completed", store.rows.getValue("run").state)
    }

    @Test fun changedInputCannotReuseExecutionIdentity() = runBlocking {
        val runtime = CapabilityRuntime(Store()) { 100L }
        runtime.execute("run", "plan_route", 1, input(), "correlation", {}) { route() }
        try {
            runtime.execute("run", "plan_route", 1, input().put("mode", "cycling"), "correlation", {}) { fail(); JSONObject() }
            fail("different parameters reused an execution")
        } catch (_: IllegalStateException) { }
    }

    @Test fun unknownDeliveryRequiresReconciliationAndNeverResubmits() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store) { 100L }
        var calls = 0
        repeat(2) {
            try {
                runtime.execute("run", "plan_route", 1, input(), "correlation", {}) {
                    calls++; throw IOException("response lost")
                }
                fail("unknown outcome returned success")
            } catch (_: IOException) { } catch (_: IllegalStateException) { }
        }
        assertEquals(1, calls)
        assertEquals("unknown", store.rows.getValue("run").state)
    }

    @Test fun cancellationOfPrintWhilePlanningRejectsResultAndPreservesOtherInputs() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store) { 100L }
        val tasks = mutableListOf("取快递", "打印论文")
        val expected = tasks.toList()
        val body = input()
        try {
            runtime.execute("run", "plan_route", 1, body, "correlation", { check(tasks == expected) { "任务已变化" } }) {
                tasks.remove("打印论文")
                route()
            }
            fail("stale result accepted")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("取快递"), tasks)
        assertEquals("宿舍", body.getJSONObject("destination").getString("name"))
        assertEquals("failed", store.rows.getValue("run").state)
    }

    @Test fun invalidCapabilityVersionAndArgumentsNeverInvokeAdapter() = runBlocking {
        val runtime = CapabilityRuntime(Store()) { 100L }
        for ((capability, version, body) in listOf(
            Triple("unknown", 1, input()), Triple("plan_route", 2, input()),
            Triple("plan_route", 1, JSONObject()), Triple("read_tasks", 1, JSONObject().put("delete", true)),
            Triple("start_navigation", 1, input()),
        )) {
            try {
                runtime.execute("run", capability, version, body, "correlation", {}) { fail("adapter invoked"); JSONObject() }
                fail("invalid invocation accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun cachedResultStillRequiresCurrentTaskAndPlanVersions() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store) { 100L }
        runtime.execute("run", "plan_route", 1, input(), "correlation", {}) { route() }
        try {
            runtime.execute("run", "plan_route", 1, input(), "correlation", { error("old plan") }) { fail(); JSONObject() }
            fail("cached result bypassed context validation")
        } catch (_: IllegalStateException) { }
    }

    @Test fun explicitHttpRejectionIsFailedRatherThanUnknown() = runBlocking {
        val store = Store()
        try {
            CapabilityRuntime(store).execute("run", "plan_route", 1, input(), "correlation", {}) {
                throw com.onmyway.app.data.remote.ApiHttpException(422, "invalid task")
            }
            fail("HTTP rejection returned success")
        } catch (_: IOException) { }
        assertEquals("failed", store.rows.getValue("run").state)
    }

    @Test fun malformedPlannerOutputCannotBecomeCompleted() = runBlocking {
        val store = Store()
        try {
            CapabilityRuntime(store).execute("run", "plan_route", 1, input(), "correlation", {}) { JSONObject() }
            fail("missing route fields accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals("failed", store.rows.getValue("run").state)
    }

    @Test fun plannerConflictRequiresStatusReconciliation() = runBlocking {
        val store = Store()
        try {
            CapabilityRuntime(store).execute("run", "plan_route", 1, input(), "correlation", {}) {
                throw com.onmyway.app.data.remote.ApiHttpException(409, "still running")
            }
            fail("conflict returned success")
        } catch (_: IOException) { }
        assertEquals("unknown", store.rows.getValue("run").state)
    }

    @Test fun integerLookingFloatingPointResultIsRejectedBeforeSaving() = runBlocking {
        val store = Store()
        try {
            CapabilityRuntime(store).execute("run", "plan_route", 1, input(), "correlation", {}) {
                route().put("total_seconds", 600.0)
            }
            fail("floating point result was coerced to an integer")
        } catch (_: IllegalArgumentException) { }
        assertEquals("failed", store.rows.getValue("run").state)
    }

    @Test fun lostPlanningResponseIsReconciledWithoutCallingPlannerAgain() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        val id = "11111111-1111-4111-8111-111111111111"
        try {
            runtime.execute(id, "plan_route", 1, input(), "correlation", {}) { throw IOException("lost") }
        } catch (_: IOException) { }
        val recovered = runtime.execute(id, "plan_route", 1, input(), "correlation", {}, reconcile = {
            PlanningRunStatus.fromJson(JSONObject().put("run_id", id).put("capability_id", "plan_route")
                .put("operation", "plan").put("state", "completed").put("result", route())
                .put("error_status", JSONObject.NULL), id, "plan")
        }) { fail("must query status before any replay"); route() }
        assertEquals("宿舍", recovered.getJSONObject("destination").getString("name"))
        assertEquals("completed", store.rows.getValue(id).state)
    }

    @Test fun serverMissingRunIsUnknownAndDoesNotPermitReplay() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        val id = "11111111-1111-4111-8111-111111111111"
        try { runtime.execute(id, "plan_route", 1, input(), "correlation", {}) { throw IOException() } } catch (_: IOException) { }
        try {
            runtime.execute(id, "plan_route", 1, input(), "correlation", {}, reconcile = {
                PlanningRunStatus.fromJson(JSONObject().put("run_id", id).put("capability_id", "plan_route")
                    .put("operation", "plan").put("state", "not_found").put("result", JSONObject.NULL)
                    .put("error_status", JSONObject.NULL), id, "plan")
            }) { fail("missing server evidence must not repeat work"); route() }
            fail("missing result returned success")
        } catch (_: IllegalStateException) { }
        assertEquals("unknown", store.rows.getValue(id).state)
    }

    @Test fun corruptedCachedResultIsRejectedWithoutInvokingPlanner() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        runtime.execute("run", "plan_route", 1, input(), "correlation", {}) { route() }
        store.rows["run"] = store.rows.getValue("run").copy(resultJson = "{}")
        try {
            runtime.execute("run", "plan_route", 1, input(), "correlation", {}) { fail("cached run replayed"); route() }
            fail("corrupted cache accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals("failed", store.rows.getValue("run").state)
    }

    @Test fun malformedRecoveredResultCannotBecomeCompleted() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        try { runtime.execute("run", "plan_route", 1, input(), "correlation", {}) { throw IOException() } } catch (_: IOException) { }
        try {
            runtime.execute("run", "plan_route", 1, input(), "correlation", {}, reconcile = {
                PlanningRunStatus("run", "plan", "completed", JSONObject(), null)
            }) { fail("recovered run replayed"); route() }
            fail("malformed recovered result accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals("failed", store.rows.getValue("run").state)
    }

    @Test fun statusQueryHttpRejectionDoesNotProvePlanningFailed() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        try { runtime.execute("run", "plan_route", 1, input(), "correlation", {}) { throw IOException() } } catch (_: IOException) { }
        try {
            runtime.execute("run", "plan_route", 1, input(), "correlation", {}, reconcile = {
                throw com.onmyway.app.data.remote.ApiHttpException(404, "status endpoint unavailable")
            }) { fail("failed lookup replayed planning"); route() }
            fail("failed lookup returned success")
        } catch (_: IOException) { }
        assertEquals("unknown", store.rows.getValue("run").state)
    }

    @Test fun confirmedServerFailureRemainsTerminal() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        try { runtime.execute("run", "plan_route", 1, input(), "correlation", {}) { throw IOException() } } catch (_: IOException) { }
        try {
            runtime.execute("run", "plan_route", 1, input(), "correlation", {}, reconcile = {
                PlanningRunStatus("run", "plan", "failed", null, 503)
            }) { fail("failed run replayed"); route() }
            fail("server failure returned success")
        } catch (_: IllegalStateException) { }
        assertEquals("failed", store.rows.getValue("run").state)
    }

    @Test fun recoveryStillChecksTaskVersionsAfterStatusReturns() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        try { runtime.execute("run", "plan_route", 1, input(), "correlation", {}) { throw IOException() } } catch (_: IOException) { }
        var current = true
        try {
            runtime.execute("run", "plan_route", 1, input(), "correlation", { check(current) }, reconcile = {
                current = false
                PlanningRunStatus("run", "plan", "completed", route(), null)
            }) { fail("stale run replayed"); route() }
            fail("stale recovered result accepted")
        } catch (_: IllegalStateException) { }
        assertEquals("failed", store.rows.getValue("run").state)
    }

    @Test fun lateOriginalFailureCannotOverwriteReconciledCompletion() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = async {
            runCatching {
                runtime.execute("run", "plan_route", 1, input(), "correlation", {}) {
                    started.complete(Unit); release.await(); throw IOException("late lost response")
                }
            }
        }
        started.await()
        try {
            val result = runtime.execute("run", "plan_route", 1, input(), "correlation", {}, reconcile = {
                PlanningRunStatus("run", "plan", "completed", route(), null)
            }) { fail("concurrent duplicate invoked planner"); route() }
            assertEquals("宿舍", result.getJSONObject("destination").getString("name"))
        } finally { release.complete(Unit) }
        assertTrue(original.await().isFailure)
        assertEquals("completed", store.rows.getValue("run").state)
    }

    @Test fun lateUnknownStatusCannotOverwriteOriginalCompletion() = runBlocking {
        val store = Store()
        val runtime = CapabilityRuntime(store)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = async {
            runtime.execute("run", "plan_route", 1, input(), "correlation", {}) {
                started.complete(Unit); release.await(); route()
            }
        }
        started.await()
        try {
            val result = runtime.execute("run", "plan_route", 1, input(), "correlation", {}, reconcile = {
                release.complete(Unit); original.await()
                PlanningRunStatus("run", "plan", "unknown", null, null)
            }) { fail("concurrent duplicate invoked planner"); route() }
            assertEquals("宿舍", result.getJSONObject("destination").getString("name"))
        } finally { release.complete(Unit) }
        assertEquals("completed", store.rows.getValue("run").state)
    }

    @Test fun recommendationsUseTheirOwnResultContract() = runBlocking {
        val store = Store()
        val params = input().put("operation", "recommend")
        val recommendation = JSONObject().put("direct", route()).put("suggestion", JSONObject.NULL)
            .put("note", "").put("assessments", org.json.JSONArray())
        CapabilityRuntime(store).execute("run", "plan_route", 1, params, "correlation", {}) { recommendation }
        assertEquals("completed", store.rows.getValue("run").state)
    }
}
