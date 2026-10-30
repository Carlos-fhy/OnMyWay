package com.onmyway.app.feature.assistant

import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.remote.assistantTripContext
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AssistantTripContextTest {
    private val trip = SavedTrip(
        sessionId = "session", planId = "plan", planVersion = 1,
        requestJson = "{}", resultJson = "{}", plannedAt = 1_000,
    )

    @Test fun newConfirmedVersionInvalidatesOutstandingAssistantReply() {
        assertNotEquals(assistantTripContext(trip), assistantTripContext(trip.copy(planVersion = 2)))
    }

    @Test fun restoredTripRequiringReplanInvalidatesOutstandingAssistantReply() {
        assertNotEquals(assistantTripContext(trip), assistantTripContext(trip.copy(needsReplan = true)))
    }
}
