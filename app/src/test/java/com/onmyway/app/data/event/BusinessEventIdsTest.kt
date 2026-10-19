package com.onmyway.app.data.event

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BusinessEventIdsTest {
    @Test fun retryOfOneObservationKeepsTheSameEventId() {
        assertEquals(
            BusinessEventIds.forObservation("NAVIGATION_ARRIVED", "session-1"),
            BusinessEventIds.forObservation("NAVIGATION_ARRIVED", "session-1"),
        )
    }

    @Test fun differentKindOrSubjectIsANewObservation() {
        val arrived = BusinessEventIds.forObservation("NAVIGATION_ARRIVED", "session-1")
        assertNotEquals(arrived, BusinessEventIds.forObservation("NAVIGATION_CANCELLED", "session-1"))
        assertNotEquals(arrived, BusinessEventIds.forObservation("NAVIGATION_ARRIVED", "session-2"))
    }

    @Test fun deterministicIdsAreValidUuids() {
        val id = BusinessEventIds.forObservation("TASK_CONFIRMED", "task-1")
        assertEquals(id, UUID.fromString(id).toString())
        val random = BusinessEventIds.newObservation()
        assertEquals(random, UUID.fromString(random).toString())
    }
}
