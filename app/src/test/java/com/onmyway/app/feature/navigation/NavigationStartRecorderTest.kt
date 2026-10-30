package com.onmyway.app.feature.navigation

import com.onmyway.app.data.local.entity.BusinessEventOutbox
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NavigationStartRecorderTest {
    private val identity = NavigationExecutionIdentity("session", "plan", 2)
    private fun trip() = SavedTrip(requestJson = """{"mode":"walking","tasks":[]}""",
        resultJson = "{}", sessionId = "session", planId = "plan", planVersion = 2)

    @Test fun sdkStartCommitsStateHistoryAndExactVersionReceiptTogether() = runBlocking {
        val store = MemoryStore(trip())
        val started = NavigationStartRecorder(store, "correlation").record(identity, 100)
        assertTrue(started.started)
        assertEquals(100L, started.startedAt)
        assertEquals(2, store.history.single().planVersion)
        val payload = JSONObject(store.events.values.single().payloadJson)
        assertEquals(2, payload.getInt("plan_version"))
        assertEquals("session", payload.getString("route_session_id"))
        assertEquals(started, store.trip)
    }

    @Test fun outboxFailureRollsBackStartedStateAndHistoryThenRetryCommitsOnce() = runBlocking {
        val original = trip()
        val store = MemoryStore(original).apply { failEnqueue = true }
        try {
            NavigationStartRecorder(store, "correlation").record(identity, 100)
            fail("expected durable event failure")
        } catch (_: IOException) { }
        assertEquals(original, store.trip)
        assertTrue(store.history.isEmpty())
        assertTrue(store.events.isEmpty())
        store.failEnqueue = false
        NavigationStartRecorder(store, "correlation").record(identity, 200)
        assertEquals(1, store.history.size)
        assertEquals(1, store.events.size)
    }

    @Test fun duplicateSdkCallbackPreservesOriginalObservationAndDoesNotAddHistory() = runBlocking {
        val store = MemoryStore(trip())
        val recorder = NavigationStartRecorder(store, "correlation")
        val first = recorder.record(identity, 100)
        val event = store.events.values.single()
        assertEquals(first, recorder.record(identity, 200))
        assertEquals(listOf(event), store.events.values.toList())
        assertEquals(1, store.history.size)
    }

    @Test fun callbackForOldVersionCannotStartTheReplacementPlan() = runBlocking {
        val store = MemoryStore(trip().copy(planVersion = 3))
        try {
            NavigationStartRecorder(store, "correlation").record(identity, 100)
            fail("old SDK callback must be rejected")
        } catch (_: IllegalStateException) { }
        assertFalse(store.trip!!.started)
        assertTrue(store.events.isEmpty())
    }

    @Test fun staleSessionAndInvalidatedPlanCannotBeStarted() = runBlocking {
        for (changed in listOf(trip().copy(sessionId = "another"), trip().copy(needsReplan = true))) {
            val store = MemoryStore(changed)
            try {
                NavigationStartRecorder(store, "correlation").record(identity, 100)
                fail("stale route must be rejected")
            } catch (_: IllegalStateException) { }
            assertEquals(changed, store.trip)
            assertTrue(store.history.isEmpty())
        }
    }

    @Test fun newRevisionGetsOwnStartEvidenceWithoutChangingTripStartTime() = runBlocking {
        val store = MemoryStore(trip().copy(started = true, startedAt = 50))
        NavigationStartRecorder(store, "correlation").record(identity, 200)
        assertEquals(50L, store.trip!!.startedAt)
        assertEquals(200L, store.events.values.single().occurredAt)
        assertTrue(store.history.isEmpty())
    }

    @Test fun explicitRetryCreatesNewExecutionEvidenceWithoutIncrementingPlanVersion() = runBlocking {
        val store = MemoryStore(trip())
        val recorder = NavigationStartRecorder(store, "correlation")
        recorder.record(identity, 100, "attempt-1")
        recorder.record(identity, 101, "attempt-1")
        recorder.record(identity, 200, "attempt-2")
        assertEquals(2, store.events.size)
        assertEquals(listOf(100L, 200L), store.events.values.map { it.occurredAt })
        assertEquals(2, store.trip!!.planVersion)
        assertEquals(1, store.history.size)
    }

    private class MemoryStore(var trip: SavedTrip?) : NavigationStartStore {
        var failEnqueue = false
        val history = mutableListOf<RouteHistory>()
        val events = linkedMapOf<String, BusinessEventOutbox>()
        override suspend fun transaction(block: suspend () -> SavedTrip): SavedTrip {
            val before = trip
            val historyBefore = history.toList()
            val eventsBefore = events.toMap()
            return try { block() } catch (error: Throwable) {
                trip = before
                history.clear(); history.addAll(historyBefore)
                events.clear(); events.putAll(eventsBefore)
                throw error
            }
        }
        override suspend fun load() = trip
        override suspend fun save(trip: SavedTrip) { this.trip = trip }
        override suspend fun record(history: RouteHistory) { this.history += history }
        override suspend fun enqueue(event: BusinessEventOutbox) {
            if (failEnqueue) throw IOException("outbox unavailable")
            events.putIfAbsent(event.eventId, event)
        }
    }
}
