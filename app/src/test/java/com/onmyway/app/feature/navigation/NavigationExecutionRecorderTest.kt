package com.onmyway.app.feature.navigation

import com.onmyway.app.core.context.ContextSnapshot
import com.onmyway.app.data.local.entity.BusinessEventOutbox
import com.onmyway.app.data.local.entity.CapabilityRun
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NavigationExecutionRecorderTest {
    private val identity = NavigationExecutionIdentity("session", "plan", 2)
    private val parcel = Errand(id = "parcel", title = "取快递", placeType = "INDOOR")
    private val print = Errand(id = "print", title = "打印论文", placeType = "INDOOR")
    private val dorm = Errand(id = "dorm", title = "回宿舍", placeType = "INDOOR")
    private val tasks = listOf(parcel, print, dorm)

    private fun store(): MemoryStore {
        val snapshot = ContextSnapshot.capture(tasks, null, now = 1_000)
        val request = JSONObject().put("mode", "walking").put("tasks", JSONArray(tasks.map { it.json() }))
            .put("context_snapshot", snapshot.json())
        return MemoryStore(SavedTrip(requestJson = request.toString(), resultJson = "{}", sessionId = identity.sessionId,
            planId = identity.planId, planVersion = identity.planVersion, plannedAt = 1_000), tasks)
    }
    private fun recorder(store: MemoryStore) = NavigationExecutionRecorder(store, "correlation")

    @Test fun explicitStartClaimsOnceAndPersistsAttemptBeforeSdkCanRun() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        assertEquals("waiting_confirmation", recorder.prepare(identity, 1_100).state)
        assertEquals(NavigationClaimResult.CLAIMED, recorder.claim(identity, "a1", 1_200))
        assertEquals(NavigationClaimResult.ALREADY_RUNNING, recorder.claim(identity, "a1", 1_300))
        assertEquals(NavigationClaimResult.UNKNOWN, recorder.claim(identity, "a2", 1_400))
        val run = store.runs.values.single()
        assertEquals("a1", run.attemptId)
        assertEquals("unknown", run.state)
        assertFalse(store.current!!.started)
        assertTrue(store.events.isEmpty())
    }

    @Test fun cancelledPrintingRejectsOldExecutionAndPreservesParcelAndDorm() = runBlocking {
        val store = store()
        store.currentTasks = listOf(parcel, dorm)
        try {
            recorder(store).claim(identity, "a1", 1_200)
            fail("a removed input task must invalidate the old route")
        } catch (_: IllegalStateException) { }
        assertTrue(store.runs.isEmpty())
        assertEquals(listOf(parcel, dorm), store.currentTasks)
        assertFalse(store.current!!.started)
    }

    @Test fun oldPlanVersionAndMissingSnapshotAreRejectedBeforeClaim() = runBlocking {
        for (change in listOf<(SavedTrip) -> SavedTrip>(
            { it.copy(planVersion = 3) },
            { it.copy(requestJson = """{"mode":"walking","tasks":[]}""") },
            { it.copy(needsReplan = true) },
        )) {
            val store = store().apply { current = change(current!!) }
            try { recorder(store).claim(identity, "a1", 1_200); fail("must reject invalid input") }
            catch (_: IllegalStateException) { }
            assertTrue(store.runs.isEmpty())
        }
    }

    @Test fun sdkStartCommitsRunTripHistoryAndReceiptAtomically() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.claim(identity, "a1", 1_100)
        store.failEnqueue = true
        try { recorder.recordStarted(identity, "a1", 1_200); fail("outbox must be atomic") }
        catch (_: IOException) { }
        assertFalse(store.current!!.started)
        assertEquals("claimed", store.runs.values.single().step)
        assertTrue(store.history.isEmpty())
        store.failEnqueue = false
        assertTrue(recorder.recordStarted(identity, "a1", 1_300)!!.started)
        assertEquals("sdk_started", store.runs.values.single().step)
        val payload = JSONObject(store.events.values.single().payloadJson)
        assertEquals(NavigationExecutionRecorder.runId(identity), payload.getString("execution_id"))
        assertEquals("a1", payload.getString("attempt_id"))
        recorder.recordStarted(identity, "a1", 1_400)
        assertEquals(1, store.events.size)
        assertEquals(1, store.history.size)
    }

    @Test fun pageIndependentArrivalPersistsAndTerminalStateRejectsLateFailure() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.claim(identity, "a1", 1_100)
        recorder.recordStarted(identity, "a1", 1_200)
        // A new recorder owns only durable storage, no Activity or callback is retained.
        assertTrue(recorder(store).recordArrived(identity, "a1", 1_300, null))
        assertFalse(recorder.recordFailed(identity, "a1", 1_400, "late"))
        assertFalse(recorder.recordArrived(identity, "a1", 1_500, null))
        assertNull(recorder.recordStarted(identity, "a1", 1_600))
        assertEquals("completed", store.runs.values.single().state)
        assertEquals(listOf("NAVIGATION_STARTED", "NAVIGATION_ARRIVED"), store.events.values.map { it.kind })
        assertEquals(NavigationClaimResult.COMPLETED, recorder.claim(identity, "a2", 1_700))
    }

    @Test fun explicitRetryAfterKnownFailureIgnoresPreviousAttemptCallbacks() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.claim(identity, "a1", 1_100)
        assertTrue(recorder.recordFailed(identity, "a1", 1_200, "sdk rejected start"))
        assertEquals(NavigationClaimResult.FAILED, recorder.claim(identity, "a1", 1_300))
        assertEquals(NavigationClaimResult.CLAIMED, recorder.claim(identity, "a2", 1_400))
        assertNull(recorder.recordStarted(identity, "a1", 1_500))
        assertFalse(recorder.recordArrived(identity, "a1", 1_600, null))
        assertFalse(recorder.recordFailed(identity, "a1", 1_700, "late"))
        assertTrue(recorder.recordStarted(identity, "a2", 1_800)!!.started)
        assertEquals("a2", store.runs.values.single().attemptId)
        assertEquals(2, store.events.size)
    }

    @Test fun unknownDeliveryBlocksReexecutionButMatchingReceiptCanResolveIt() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.claim(identity, "a1", 1_100)
        assertTrue(recorder.markUnknown(identity, "a1", 1_200, "callback timeout"))
        assertEquals(NavigationClaimResult.UNKNOWN, recorder(store).claim(identity, "a2", 1_300))
        assertTrue(recorder.recordStarted(identity, "a1", 1_400)!!.started)
        assertEquals("sdk_started", store.runs.values.single().step)
        assertEquals(NavigationClaimResult.ALREADY_RUNNING, recorder.claim(identity, "a2", 1_500))
    }

    @Test fun staleSessionCallbackNeverMutatesReplacementTripOrRun() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.claim(identity, "a1", 1_100)
        store.current = store.current!!.copy(sessionId = "new-session", planVersion = 3)
        assertNull(recorder.recordStarted(identity, "a1", 1_200))
        assertFalse(recorder.recordArrived(identity, "a1", 1_300, null))
        assertFalse(recorder.recordFailed(identity, "a1", 1_400, "old controller"))
        assertFalse(store.current!!.started)
        assertEquals("claimed", store.runs.values.single().step)
        assertTrue(store.events.isEmpty())
    }

    @Test fun preparationFailureIsRecordedWithoutClaimingNavigationStarted() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        assertTrue(recorder.prepareAttempt(identity, "a1", 1_050))
        assertTrue(recorder.recordFailed(identity, "a1", 1_100, "route calculation failed"))
        assertFalse(store.current!!.started)
        assertEquals("preparation_failed", store.runs.values.single().step)
        assertEquals("failed", store.runs.values.single().state)
        assertEquals(listOf("NAVIGATION_FAILED"), store.events.values.map { it.kind })
        assertEquals(NavigationClaimResult.CLAIMED, recorder.claim(identity, "a2", 1_200))
    }

    @Test fun pauseRequiresAnotherExplicitAttemptAndOldCallbacksCannotResumeIt() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.claim(identity, "a1", 1_100)
        recorder.recordStarted(identity, "a1", 1_200)
        assertTrue(recorder.recordPaused(identity, "a1", 1_300))
        assertEquals("waiting_confirmation", store.runs.values.single().state)
        assertEquals("paused", store.runs.values.single().step)
        assertNull(recorder.recordStarted(identity, "a1", 1_400))
        assertFalse(recorder.recordFailed(identity, "a1", 1_500, "late"))
        assertEquals(NavigationClaimResult.FAILED, recorder.claim(identity, "a1", 1_600))
        assertEquals(NavigationClaimResult.CLAIMED, recorder.claim(identity, "a2", 1_700))
        assertEquals(listOf("NAVIGATION_STARTED"), store.events.values.map { it.kind })
    }

    @Test fun oldPreparationFailureCannotOverwriteNewerFailedAttempt() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.prepareAttempt(identity, "a1", 1_100)
        assertTrue(recorder.recordFailed(identity, "a1", 1_200, "first"))
        recorder.prepareAttempt(identity, "a2", 1_300)
        assertTrue(recorder.recordFailed(identity, "a2", 1_400, "second"))
        assertFalse(recorder.recordFailed(identity, "a1", 1_500, "late first"))
        assertEquals("a2", store.runs.values.single().attemptId)
        assertEquals("second", store.runs.values.single().error)
        assertEquals(2, store.events.size)
    }

    @Test fun recoveryKeepsArrivedEvidenceAndDoesNotInventCancellation() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.claim(identity, "a1", 1_100)
        recorder.recordStarted(identity, "a1", 1_200)
        assertEquals("unknown", recorder.recover(identity, 1_300)!!.state)
        assertEquals(NavigationClaimResult.UNKNOWN, recorder.claim(identity, "a2", 1_400))
        assertTrue(recorder.recordArrived(identity, "a1", 1_500, null))
        assertEquals("completed", recorder.recover(identity, 1_600)!!.state)
        assertTrue(recorder.recordClosed(identity, 1_700, arrivedManually = true))
        assertEquals("sdk_arrived", store.runs.values.single().step)
        assertEquals(listOf("NAVIGATION_STARTED", "NAVIGATION_ARRIVED"), store.events.values.map { it.kind })
    }

    @Test fun legacyStartedTripWithoutClaimCannotBeExecutedAgain() = runBlocking {
        val store = store().apply { current = current!!.copy(started = true) }
        assertEquals(NavigationClaimResult.UNKNOWN, recorder(store).claim(identity, "a1", 1_100))
        assertEquals("unknown", recorder(store).recover(identity, 1_200)!!.state)
        assertFalse(recorder(store).prepareAttempt(identity, "a2", 1_300))
        assertTrue(store.events.isEmpty())
    }

    @Test fun manualFinishClosesExecutionWithoutSdkArrivalAndCannotBeRetried() = runBlocking {
        val store = store()
        val recorder = recorder(store)
        recorder.claim(identity, "a1", 1_100)
        assertTrue(recorder.recordClosed(identity, 1_200, arrivedManually = true))
        assertEquals("manual_end", store.runs.values.single().step)
        assertEquals("failed", store.runs.values.single().state)
        assertNull(recorder.recordStarted(identity, "a1", 1_300))
        assertFalse(recorder.recordFailed(identity, "a1", 1_400, "late"))
        assertEquals(NavigationClaimResult.COMPLETED, recorder.claim(identity, "a2", 1_500))
        assertTrue(store.events.isEmpty())
    }

    @Test fun confirmedProposedTaskMustMatchOriginalRevisionWithoutRefreshingObservationTime() = runBlocking {
        for (changed in listOf(false, true)) {
            val store = store()
            val proposal = Errand(id = "coffee", title = "买咖啡", placeType = "INDOOR")
            val original = ContextSnapshot.capture(tasks, null, now = 1_000, proposedTasks = listOf(proposal))
            store.current = store.current!!.copy(requestJson = JSONObject(store.current!!.requestJson)
                .put("context_snapshot", original.json()).toString())
            store.currentTasks = tasks + if (changed) proposal.copy(title = "改成牛奶") else proposal
            if (changed) {
                try { recorder(store).claim(identity, "a1", 1_100); fail("changed proposal must be rejected") }
                catch (_: IllegalStateException) { }
                assertTrue(store.runs.isEmpty())
            } else {
                assertEquals(NavigationClaimResult.CLAIMED, recorder(store).claim(identity, "a1", 1_100))
                assertEquals(1_000L, JSONObject(store.runs.values.single().snapshotJson).getLong("captured_at"))
            }
        }
    }

    private class MemoryStore(var current: SavedTrip?, var currentTasks: List<Errand>) : NavigationExecutionStore {
        var failEnqueue = false
        val runs = linkedMapOf<String, CapabilityRun>()
        val history = mutableListOf<RouteHistory>()
        val events = linkedMapOf<String, BusinessEventOutbox>()
        override suspend fun <T> transaction(block: suspend () -> T): T {
            val oldTrip = current
            val oldRuns = runs.toMap()
            val oldHistory = history.toList()
            val oldEvents = events.toMap()
            return try { block() } catch (error: Throwable) {
                current = oldTrip
                runs.clear(); runs.putAll(oldRuns)
                history.clear(); history.addAll(oldHistory)
                events.clear(); events.putAll(oldEvents)
                throw error
            }
        }
        override suspend fun trip() = current
        override suspend fun tasks() = currentTasks
        override suspend fun run(runId: String) = runs[runId]
        override suspend fun save(run: CapabilityRun) { runs[run.runId] = run }
        override suspend fun save(trip: SavedTrip) { current = trip }
        override suspend fun record(history: RouteHistory) { this.history += history }
        override suspend fun enqueue(event: BusinessEventOutbox) {
            if (failEnqueue) throw IOException("outbox unavailable")
            events.putIfAbsent(event.eventId, event)
        }
    }
}
