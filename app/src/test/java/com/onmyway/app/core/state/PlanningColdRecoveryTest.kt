package com.onmyway.app.core.state

import com.onmyway.app.data.execution.CapabilityRunStore
import com.onmyway.app.data.execution.PlanningRunStatus
import com.onmyway.app.data.local.entity.CapabilityRun
import com.onmyway.app.data.sync.canonicalJson
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanningColdRecoveryTest {
    private class Runs : CapabilityRunStore {
        val rows = mutableMapOf<String, CapabilityRun>()
        override suspend fun <T> transaction(block: suspend () -> T) = block()
        override suspend fun load(id: String) = rows[id]
        override suspend fun save(run: CapabilityRun) { rows[run.runId] = run }
    }
    private class Requests : PlanningRequestStore {
        var row: PendingPlanningRequest? = null
        override suspend fun pending() = row
        override suspend fun create(request: PendingPlanningRequest, replaceRunId: String?) {
            check(row == null || row == request || row?.runId == replaceRunId)
            row = request
        }
        override suspend fun finish(runId: String): Boolean {
            if (row?.runId != runId) return false
            row = null
            return true
        }
    }
    private val owner = PlanningRequestOwner("assistant", "conversation", "draft", "plan")
    private fun body() = JSONObject("""{"context_snapshot":{"snapshot_id":"snapshot"},"origin":{"id":"start","name":"学校","longitude":121.0,"latitude":31.0},"destination":{"id":"dorm","name":"宿舍","longitude":121.01,"latitude":31.01},"mode":"walking","tasks":[],"departure":"2026-11-08T09:00:00+08:00"}""")
    private fun route() = JSONObject("""{"mode":"walking","baseline_seconds":600,"baseline_meters":500,"travel_seconds":600,"service_seconds":0,"total_seconds":600,"total_meters":500,"extra_seconds":0,"extra_travel_seconds":0,"extra_meters":0,"arrival":"2026-11-08T09:10:00+08:00","stops":[],"legs":[{"seconds":600,"meters":500,"polyline":""}],"omitted":[],"warnings":[],"solver_status":"direct"}""")
        .put("origin", body().getJSONObject("origin")).put("destination", body().getJSONObject("destination"))

    @Test fun originalRequestAndOwnerAreDurableBeforeTransportStarts() = runBlocking {
        val runs = Runs()
        val requests = Requests()
        val original = canonicalJson(body())
        val execution = PlanningExecution(runs, submit = { endpoint, sent, id, operation ->
            val pending = requests.pending()
            assertNotNull("A process killed during POST must retain the original request", pending)
            assertEquals(endpoint, pending!!.server)
            assertEquals(id, pending.runId)
            assertEquals(operation, pending.operation)
            assertEquals(owner, pending.owner)
            assertEquals(original, pending.bodyJson)
            assertEquals(original, canonicalJson(sent))
            route()
        }, status = null, requests = requests, owner = owner)
        execution.execute("http://localhost:8000/", body(), "plan", "trace", {})
        assertNotNull("Completed result remains recoverable until its caller consumes it", requests.pending())
        assertEquals("completed", runs.rows.values.single().state)
    }

    @Test fun unresolvedRequestCannotBeOverwrittenByANewBody() = runBlocking {
        val runs = Runs()
        val requests = Requests()
        var submits = 0
        val execution = PlanningExecution(runs, { _, _, _, _ -> submits++; throw IOException("lost") },
            { _, _, id, operation -> PlanningRunStatus(id, operation, "not_found", null, null) }, requests, owner)
        runCatching { execution.execute("http://localhost:8000", body(), "plan", "trace", {}) }
        val retained = requests.pending()
        runCatching { execution.execute("http://localhost:8000", body().put("departure", "2026-11-08T09:01:00+08:00"), "plan", "trace", {}) }
        assertEquals("A new snapshot must not replay an unresolved planner", 1, submits)
        assertNotNull(retained)
        assertEquals(retained, requests.pending())
    }

    @Test fun newExecutionObjectCanFindTheOriginalRequestWithoutCallerRebuildingIt() = runBlocking {
        val runs = Runs()
        val requests = Requests()
        var submits = 0
        val first = PlanningExecution(runs, { _, _, _, _ -> submits++; throw IOException("lost") }, null, requests, owner)
        runCatching { first.execute("http://localhost:8000", body(), "plan", "original-trace", {}) }
        assertNotNull("Startup needs a discoverable pending request, not recent() heuristics", requests.pending())
        val original = requests.pending()!!
        val reopened = PlanningExecution(runs, { _, _, _, _ -> submits++; error("must not replay") },
            { endpoint, sent, id, operation ->
                assertEquals(original.server, endpoint)
                assertEquals(original.bodyJson, canonicalJson(sent))
                assertEquals(original.runId, id)
                PlanningRunStatus(id, operation, "completed", route(), null)
            }, requests, owner)
        val result = reopened.execute(original.server, JSONObject(original.bodyJson), original.operation, original.correlationId, {})
        assertEquals("宿舍", result.getJSONObject("destination").getString("name"))
        assertEquals(1, submits)
        assertEquals(original, requests.pending())
    }

    @Test fun sameWireBodyDoesNotTransferAnUnconsumedResultToANewDraft() = runBlocking {
        val runs = Runs()
        val requests = Requests()
        val first = PlanningExecution(runs, { _, _, _, _ -> route() }, null, requests, owner)
        first.execute("http://localhost:8000", body(), "plan", "trace", {})
        val second = PlanningExecution(runs, { _, _, _, _ -> error("must not submit") }, null,
            requests, owner.copy(conversationId = "new-conversation", draftId = "new-draft"))
        var rejected = false
        try { second.execute("http://localhost:8000", body(), "plan", "trace", {}) }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue("A cached route belongs to its original conversation and draft", rejected)
    }

    @Test fun crashBeforeRunRowExistsStillQueriesStatusWithoutSubmitting() = runBlocking {
        val runs = Runs()
        val requests = Requests()
        val payload = canonicalJson(body())
        val pending = PendingPlanningRequest(PlanningExecution.identity("http://localhost:8000", "plan", payload),
            "http://localhost:8000", payload, "plan", "trace", owner, 1L)
        requests.create(pending)
        var queries = 0
        val execution = PlanningExecution(runs, { _, _, _, _ -> error("recovery must not submit") },
            { _, _, id, op -> queries++; PlanningRunStatus(id, op, "completed", route(), null) }, requests, owner)
        execution.recover(pending, {})
        assertEquals(1, queries)
        assertEquals("completed", runs.rows[pending.runId]?.state)
        assertEquals(pending, requests.pending())
    }

    @Test fun unknownStatusKeepsOriginalRequestForAnotherExplicitCheck() = runBlocking {
        val runs = Runs()
        val requests = Requests()
        val payload = canonicalJson(body())
        val pending = PendingPlanningRequest(PlanningExecution.identity("http://localhost:8000", "plan", payload),
            "http://localhost:8000", payload, "plan", "trace", owner, 1L)
        requests.create(pending)
        var queries = 0
        val execution = PlanningExecution(runs, { _, _, _, _ -> error("recovery must not submit") },
            { _, _, id, op -> queries++; PlanningRunStatus(id, op, "not_found", null, null) }, requests, owner)
        repeat(2) { runCatching { execution.recover(pending, {}) } }
        assertEquals(2, queries)
        assertEquals("unknown", runs.rows[pending.runId]?.state)
        assertEquals(pending, requests.pending())
    }

    @Test fun lateCompletedLookupCannotApplyAfterOwnerWasDiscarded() = runBlocking {
        val runs = Runs()
        val requests = Requests()
        val payload = canonicalJson(body())
        val pending = PendingPlanningRequest(PlanningExecution.identity("http://localhost:8000", "plan", payload),
            "http://localhost:8000", payload, "plan", "trace", owner, 1L)
        requests.create(pending)
        var queries = 0
        val execution = PlanningExecution(runs, { _, _, _, _ -> error("recovery must not submit") },
            { _, _, id, op -> queries++; requests.finish(id); PlanningRunStatus(id, op, "completed", route(), null) }, requests, owner)
        var rejected = false
        try { execution.recover(pending, {}) } catch (_: IllegalStateException) { rejected = true }
        assertEquals(1, queries)
        assertTrue(rejected)
        assertNotEquals("completed", runs.rows[pending.runId]?.state)
    }
}
