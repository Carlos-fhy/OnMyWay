package com.onmyway.app.data.execution

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlanningRunStatusTest {
    private val runId = "11111111-1111-4111-8111-111111111111"
    private fun response(state: String = "completed", operation: String = "plan") = JSONObject()
        .put("run_id", runId).put("capability_id", "plan_route").put("operation", operation)
        .put("state", state).put("result", if (state == "completed") JSONObject().put("total_meters", 42) else JSONObject.NULL)
        .put("error_status", JSONObject.NULL)
    private fun parse(value: JSONObject) = PlanningRunStatus.fromJson(value, runId, "plan")

    @Test fun completedResultRetainsIdentityAndCopiesTheResponseObject() {
        val value = response()
        val parsed = parse(value)
        value.getJSONObject("result").put("total_meters", 99)
        assertEquals(runId, parsed.runId)
        assertEquals("plan", parsed.operation)
        assertEquals("completed", parsed.state)
        assertEquals(42, parsed.result!!.getInt("total_meters"))
        assertNull(parsed.errorStatus)
    }

    @Test fun unresolvedAndNotFoundStatusesDoNotInventResult() {
        for (state in listOf("not_found", "running", "unknown", "failed")) {
            val parsed = parse(response(state))
            assertEquals(state, parsed.state)
            assertNull(parsed.result)
        }
        assertEquals(503, parse(response("failed").put("error_status", 503)).errorStatus)
    }

    @Test fun copiedResultPreservesScalarTypesAndNestedArraysForValidation() {
        val value = response()
        value.getJSONObject("result").put("total_meters", 42.0)
            .put("legs", JSONArray().put(JSONObject().put("meters", 42.0)))
        val parsed = parse(value).result!!
        value.getJSONObject("result").getJSONArray("legs").getJSONObject(0).put("meters", 99)
        assertTrue(parsed.opt("total_meters") is Double)
        assertEquals(42.0, parsed.getJSONArray("legs").getJSONObject(0).getDouble("meters"), 0.0)
        assertTrue(parsed.getJSONArray("legs").getJSONObject(0).opt("meters") is Double)
    }

    @Test fun recommendationHasItsOwnOperationIdentity() {
        assertEquals("recommend", PlanningRunStatus.fromJson(response(operation = "recommend"), runId, "recommend").operation)
        assertThrows(PlanningRunProtocolException::class.java) { parse(response(operation = "recommend")) }
    }

    @Test fun wrongRunCapabilityAndUnknownStateAreRejected() {
        for ((key, value) in listOf(
            "run_id" to "22222222-2222-4222-8222-222222222222",
            "run_id" to "not-a-uuid", "run_id" to 1,
            "capability_id" to "start_navigation", "state" to "waiting_confirmation", "state" to true,
            "operation" to "navigate",
        )) assertThrows(PlanningRunProtocolException::class.java) { parse(response().put(key, value)) }
    }

    @Test fun completedRequiresAnObjectAndOtherStatesRequireNull() {
        for (invalid in listOf(JSONObject.NULL, "{}", JSONArray(), true)) {
            assertThrows(PlanningRunProtocolException::class.java) { parse(response().put("result", invalid)) }
        }
        for (state in listOf("not_found", "running", "unknown", "failed")) {
            assertThrows(PlanningRunProtocolException::class.java) { parse(response(state).put("result", JSONObject())) }
        }
    }

    @Test fun errorStatusMustBeNullOrAnActualHttpErrorInteger() {
        for (invalid in listOf("500", 500.0, true, 399, 600)) {
            assertThrows(PlanningRunProtocolException::class.java) { parse(response("failed").put("error_status", invalid)) }
        }
    }

    @Test fun missingContractFieldsAreRejected() {
        for (key in listOf("run_id", "capability_id", "operation", "state", "result", "error_status")) {
            assertThrows(PlanningRunProtocolException::class.java) { parse(response().apply { remove(key) }) }
        }
    }
}
