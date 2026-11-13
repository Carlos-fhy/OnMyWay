package com.onmyway.app.data.event

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NavigationExecutionEventTest {
    private val request = JSONObject().put("mode", "walking")

    @Test fun startCarriesTheExactExecutionAttempt() {
        val event = BusinessEventFactory.navigationStarted("session", "plan", request, "correlation", 10, 2,
            attemptId = "attempt-1", executionId = "execution-1")
        val payload = JSONObject(event.payloadJson)
        assertEquals("execution-1", payload.getString("execution_id"))
        assertEquals("attempt-1", payload.getString("attempt_id"))
    }

    @Test fun repeatedFailureOfSameAttemptKeepsReceiptIdentity() {
        fun failed(attempt: String, observedAt: Long) = BusinessEventFactory.navigationFailed(
            "session", "plan", request, "sdk failure", "correlation", observedAt, 2,
            attemptId = attempt, executionId = "execution-1")
        val first = failed("attempt-1", 10)
        assertEquals(first.eventId, failed("attempt-1", 20).eventId)
        assertNotEquals(first.eventId, failed("attempt-2", 20).eventId)
        assertEquals("attempt-1", JSONObject(first.payloadJson).getString("attempt_id"))
    }

    @Test fun arrivalAndCancellationCarryExecutionIdentity() {
        val events = listOf(
            BusinessEventFactory.navigationArrived("session", "plan", request, null, "correlation", 10, 2,
                executionId = "execution-1", attemptId = "attempt-1"),
            BusinessEventFactory.navigationCancelled("session", "plan", request, null, "user", "correlation", 10, 2,
                executionId = "execution-1", attemptId = "attempt-1"),
        )
        events.forEach {
            assertEquals("execution-1", JSONObject(it.payloadJson).getString("execution_id"))
            assertEquals("attempt-1", JSONObject(it.payloadJson).getString("attempt_id"))
        }
    }
}
