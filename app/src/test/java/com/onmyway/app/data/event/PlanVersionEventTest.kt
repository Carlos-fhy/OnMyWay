package com.onmyway.app.data.event

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanVersionEventTest {
    private val request = JSONObject("""{"mode":"walking","tasks":[{"id":"parcel"},{"id":"paper"}]}""")
    private val route = JSONObject("""{"total_seconds":600,"total_meters":400}""")

    @Test fun legacyRouteAndExecutionExplicitlyReportUnknownVersion() {
        val events = listOf(
            BusinessEventFactory.routeConfirmed("history", "session", "plan", request, route, "correlation", 10, false),
            BusinessEventFactory.navigationStarted("session", "plan", request, "correlation", 10),
            BusinessEventFactory.navigationArrived("session", "plan", request, null, "correlation", 10),
            BusinessEventFactory.navigationCancelled("session", "plan", request, null, "user", "correlation", 10),
            BusinessEventFactory.navigationFailed("session", "plan", request, "sdk", "correlation", 10),
        )
        events.forEach {
            val payload = JSONObject(it.payloadJson)
            assertTrue("${it.kind} must explicitly report the unknown plan version", payload.has("plan_version"))
            assertTrue(payload.isNull("plan_version"))
        }
    }

    @Test fun confirmationSnapshotRecordsItsAcceptedPlanVersion() {
        val receipt = ConfirmationFactory.plan("plan", "session", 4, "correlation", 10, request, route, true)
        assertEquals(4, JSONObject(receipt.snapshotJson).getInt("plan_version"))
    }

    @Test fun everyExecutionOutcomeIdentifiesTheConfirmedVersion() {
        val events = listOf(
            BusinessEventFactory.routeConfirmed("history", "session", "plan", request, route, "correlation", 10, false, 2),
            BusinessEventFactory.navigationStarted("session", "plan", request, "correlation", 10, 2),
            BusinessEventFactory.navigationArrived("session", "plan", request, null, "correlation", 10, 2),
            BusinessEventFactory.navigationCancelled("session", "plan", request, null, "user", "correlation", 10, 2),
            BusinessEventFactory.navigationFailed("session", "plan", request, "sdk", "correlation", 10, 2),
        )
        events.forEach {
            val payload = JSONObject(it.payloadJson)
            assertEquals(it.kind, 2, payload.getInt("plan_version"))
            assertEquals("plan", payload.getString("plan_id"))
            assertEquals("session", payload.getString("route_session_id"))
        }
    }

    @Test fun startedAndArrivedDeduplicateWithinVersionWithoutDroppingReplannedExecution() {
        val start = BusinessEventFactory.navigationStarted("session", "plan", request, "correlation", 10, 1)
        assertEquals(start.eventId,
            BusinessEventFactory.navigationStarted("session", "plan", request, "correlation", 11, 1).eventId)
        assertNotEquals(start.eventId,
            BusinessEventFactory.navigationStarted("session", "plan", request, "correlation", 11, 2).eventId)
        val arrived = BusinessEventFactory.navigationArrived("session", "plan", request, null, "correlation", 10, 1)
        assertNotEquals(arrived.eventId,
            BusinessEventFactory.navigationArrived("session", "plan", request, null, "correlation", 11, 2).eventId)
    }

    @Test fun failedAttemptsRemainDistinctEvenForTheSameConfirmedVersion() {
        val first = BusinessEventFactory.navigationFailed("session", "plan", request, "sdk", "correlation", 10, 2)
        val second = BusinessEventFactory.navigationFailed("session", "plan", request, "sdk", "correlation", 10, 2)
        assertNotEquals(first.eventId, second.eventId)
    }

    @Test fun differentPlansInTheSameSessionDoNotShareExecutionEventIds() {
        val first = BusinessEventFactory.navigationStarted("session", "plan-a", request, "correlation", 10, 1)
        val second = BusinessEventFactory.navigationStarted("session", "plan-b", request, "correlation", 10, 1)
        assertNotEquals(first.eventId, second.eventId)
    }
}
