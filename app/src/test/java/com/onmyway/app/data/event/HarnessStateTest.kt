package com.onmyway.app.data.event

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessStateTest {
    private val correlation = "22222222-2222-4222-8222-222222222222"
    private fun identity() = JSONObject().put("plan_id", "plan-1").put("plan_version", 2)
        .put("route_session_id", "session-1")
    private fun response() = JSONObject().put("correlation_id", correlation)
        .put("recorded", JSONObject().put("count", 1))
        .put("recorded_is_not_executed", true)
        .put("current_plan", identity())
        .put("current_plan_execution", identity().put("state", "not_started"))
        .put("execution", identity().put("plan_version", 1).put("state", "arrived"))
        .put("execution_matches_recorded_plan", false)

    @Test fun staleArrivalDoesNotCompleteTheCurrentPlan() {
        val state = HarnessState.fromJson(response(), correlation)
        assertEquals("not_started", state.executionState)
        assertEquals(2, state.currentPlan?.version)
        assertEquals(false, state.latestObservationMatches)
        assertFalse(state.executionLabel.contains("已到达"))
    }

    @Test fun mismatchedCurrentExecutionIsRejected() {
        listOf("plan_id" to "other-plan", "plan_version" to 1, "route_session_id" to "other-session")
            .forEach { (key, value) ->
                assertThrows(HarnessStateProtocolException::class.java) {
                    HarnessState.fromJson(response().put("current_plan_execution",
                        identity().put("state", "started").put(key, value)), correlation)
                }
            }
    }

    @Test fun unknownHistoricalVersionDoesNotClaimNavigationStarted() {
        val state = HarnessState.fromJson(response()
            .put("current_plan_execution", identity().put("state", "unknown"))
            .put("execution_matches_recorded_plan", JSONObject.NULL), correlation)
        assertEquals("unknown", state.executionState)
        assertTrue(state.executionLabel.contains("未知"))
    }

    @Test fun oldServerWithoutCurrentPlanFieldsShowsUnknownInsteadOfRawArrival() {
        val old = response().apply { remove("current_plan"); remove("current_plan_execution") }
        assertEquals("unknown", HarnessState.fromJson(old, correlation).executionState)
    }

    @Test fun serverRecordingAloneDoesNotClaimExecution() {
        val state = HarnessState.fromJson(response(), correlation)
        assertEquals(1, state.recordedCount)
        assertEquals("尚未收到导航开始回执", state.executionLabel)
    }

    @Test fun wrongCorrelationAndMalformedStateAreRejected() {
        listOf(response().put("correlation_id", "33333333-3333-4333-8333-333333333333"),
            response().put("recorded_is_not_executed", false),
            response().put("current_plan_execution", identity().put("state", "complete")))
            .forEach { value ->
                assertThrows(HarnessStateProtocolException::class.java) {
                    HarnessState.fromJson(value, correlation)
                }
            }
    }
}
