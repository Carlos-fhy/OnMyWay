package com.onmyway.app.core.state

import com.onmyway.app.data.execution.CapabilityRunStore
import com.onmyway.app.data.execution.PlanningRunStatus
import com.onmyway.app.data.local.entity.CapabilityRun
import com.onmyway.app.data.remote.ApiHttpException
import com.onmyway.app.data.sync.canonicalJson
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanningExecutionTest {
    private class Store : CapabilityRunStore {
        val rows = mutableMapOf<String, CapabilityRun>()
        override suspend fun <T> transaction(block: suspend () -> T) = block()
        override suspend fun load(id: String) = rows[id]
        override suspend fun save(run: CapabilityRun) { rows[run.runId] = run }
    }
    private fun body() = JSONObject("""{"context_snapshot":{"snapshot_id":"snapshot"},"origin":{"id":"start","name":"学校","longitude":121.0,"latitude":31.0},"destination":{"id":"dorm","name":"宿舍","longitude":121.01,"latitude":31.01},"mode":"walking","tasks":[],"departure":"2026-11-03T09:00:00+08:00"}""")
    private fun route() = JSONObject("""{"mode":"walking","baseline_seconds":600,"baseline_meters":500,"travel_seconds":600,"service_seconds":0,"total_seconds":600,"total_meters":500,"extra_seconds":0,"extra_travel_seconds":0,"extra_meters":0,"arrival":"2026-11-03T09:10:00+08:00","stops":[],"legs":[{"seconds":600,"meters":500,"polyline":""}],"omitted":[],"warnings":[],"solver_status":"direct"}""")
        .put("origin", body().getJSONObject("origin")).put("destination", body().getJSONObject("destination"))

    @Test fun lostResponseUsesExactRequestAndIdentityForStatusOnly() = runBlocking {
        val store = Store()
        val request = body()
        val original = canonicalJson(request)
        var submittedId = ""
        var submits = 0
        var queries = 0
        val execution = PlanningExecution(store, submit = { endpoint, sent, runId, operation ->
            submits++
            assertEquals("http://localhost:8000", endpoint)
            assertEquals("plan", operation)
            assertFalse(sent.has("operation"))
            assertEquals(original, canonicalJson(sent))
            submittedId = runId
            request.put("mode", "cycling")
            sent.put("mode", "cycling")
            throw IOException("response lost")
        }, status = { endpoint, sent, runId, operation ->
            queries++
            assertEquals("http://localhost:8000", endpoint)
            assertEquals(submittedId, runId)
            assertEquals(original, canonicalJson(sent))
            PlanningRunStatus(runId, operation, "completed", route(), null)
        })
        val result = execution.execute("http://localhost:8000/", request, "plan", "original-trace", {})
        assertEquals("宿舍", result.getJSONObject("destination").getString("name"))
        assertEquals(1, submits)
        assertEquals(1, queries)
        assertEquals("completed", store.rows.values.single().state)
    }

    @Test fun laterRetryUsesOriginalCorrelationAndDoesNotReplayNotFound() = runBlocking {
        val store = Store()
        var submits = 0
        var queries = 0
        val execution = PlanningExecution(store, { _, _, _, _ -> submits++; throw IOException() },
            { _, _, id, operation ->
                queries++
                if (queries == 1) PlanningRunStatus(id, operation, "not_found", null, null)
                else PlanningRunStatus(id, operation, "completed", route(), null)
            })
        try { execution.execute("http://localhost:8000", body(), "plan", "old-trace", {}); fail() }
        catch (_: IllegalStateException) { }
        assertEquals("unknown", store.rows.values.single().state)
        execution.execute("http://localhost:8000", body(), "plan", "new-trace", {})
        assertEquals(1, submits)
        assertEquals(2, queries)
        assertEquals("old-trace", store.rows.values.single().correlationId)
    }

    @Test fun inProgressConflictIsReconciledWithoutAnotherSubmission() = runBlocking {
        val store = Store()
        var submits = 0
        var queries = 0
        val execution = PlanningExecution(store, { _, _, _, _ -> submits++; throw ApiHttpException(409, "running") },
            { _, _, id, operation -> queries++; PlanningRunStatus(id, operation, "completed", route(), null) })
        execution.execute("http://localhost:8000", body(), "plan", "trace", {})
        assertEquals(1, submits)
        assertEquals(1, queries)
    }

    @Test fun rejectedOrCancelledSubmissionDoesNotQueryOrReplay() = runBlocking {
        for (error in listOf(ApiHttpException(422, "invalid"), CancellationException("cancelled"))) {
            val store = Store()
            val execution = PlanningExecution(store, { _, _, _, _ -> throw error },
                { _, _, _, _ -> throw AssertionError("unexpected query") })
            try { execution.execute("http://localhost:8000", body(), "plan", "trace", {}); fail() }
            catch (caught: Exception) { assertSame(error, caught) }
            assertEquals(if (error is CancellationException) "unknown" else "failed", store.rows.values.single().state)
        }
    }

    @Test fun recommendationAndServerHaveSeparateExecutionIdentities() = runBlocking {
        val store = Store()
        val ids = mutableSetOf<String>()
        val execution = PlanningExecution(store, { _, sent, id, operation ->
            assertFalse(sent.has("operation"))
            ids += id
            if (operation == "plan") route() else JSONObject().put("direct", route())
                .put("suggestion", JSONObject.NULL).put("note", "").put("assessments", JSONArray())
        }, null)
        execution.execute("http://localhost:8000", body(), "plan", "trace", {})
        execution.execute("http://localhost:8000", body(), "recommend", "trace", {})
        execution.execute("http://localhost:8001", body(), "recommend", "trace", {})
        assertEquals(3, ids.size)
        assertTrue(store.rows.values.all { it.state == "completed" })
    }

    @Test fun changedContextRejectsRecoveredResultWithoutResubmission() = runBlocking {
        val store = Store()
        var current = true
        val execution = PlanningExecution(store, { _, _, _, _ -> throw IOException() },
            { _, _, id, operation ->
                current = false
                PlanningRunStatus(id, operation, "completed", route(), null)
            })
        try {
            execution.execute("http://localhost:8000", body(), "plan", "trace", { check(current) })
            fail("stale result applied")
        } catch (_: IllegalStateException) { }
        assertNotEquals("completed", store.rows.values.single().state)
    }
}
