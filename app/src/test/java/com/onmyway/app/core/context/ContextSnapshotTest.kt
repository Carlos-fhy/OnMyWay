package com.onmyway.app.core.context

import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextSnapshotTest {
    private val now = 1_800_000_000_000L
    private val parcel = Errand(id = "parcel", title = "取快递", placeJson = Place("station", "快递站", 116.4, 39.9).json().toString())
    private val printing = Errand(id = "printing", title = "打印论文", serviceMinutes = 10)
    private val tasks = listOf(parcel, printing)
    private val trip = SavedTrip(
        sessionId = "session", planId = "plan", planVersion = 3,
        requestJson = JSONObject().put("destination", Place("dorm", "宿舍", 116.5, 39.8).json()).toString(),
        resultJson = "{}", plannedAt = now - 1_000,
    )

    @Test fun changedPrintingInvalidatesPreviewWithoutChangingParcelOrDorm() {
        val snapshot = capture()
        val failure = stale { snapshot.requireCurrent(listOf(parcel, printing.copy(title = "打印两份论文")), trip, now) }
        assertTrue(failure.contains("打印论文"))
        assertTrue(failure.contains("重新规划"))
        assertEquals(parcel, tasks.first())
        assertEquals("宿舍", JSONObject(trip.requestJson).getJSONObject("destination").getString("name"))
    }

    @Test fun deletedOrCompletedPrintingInvalidatesPreview() {
        val snapshot = capture()
        assertTrue(stale { snapshot.requireCurrent(listOf(parcel), trip, now) }.contains("打印论文"))
        stale { snapshot.requireCurrent(listOf(parcel, printing.copy(status = "done")), trip, now) }
    }

    @Test fun eachDecisionFieldInvalidatesTaskRevision() {
        val changed = listOf(
            printing.copy(priority = 1), printing.copy(serviceMinutes = 20),
            printing.copy(deadline = "2026-10-28T18:00:00+08:00"),
            printing.copy(placeJson = Place("shop", "打印店", 116.3, 39.7).json().toString()),
            printing.copy(placeType = "PRINT_SHOP"), printing.copy(plannedDate = "2026-10-29"),
            printing.copy(reminderAt = now + 60_000),
        )
        changed.forEach { stale { capture().requireCurrent(listOf(parcel, it), trip, now) } }
    }

    @Test fun reminderDeliveryAndBookkeepingDoNotInvalidateDecision() {
        capture().requireCurrent(listOf(parcel, printing.copy(
            updatedAt = now + 1_000, createdAt = 1, completedAt = 2,
            lastRemindedAt = now, reminderCount = 9, reminderPending = true,
        )), trip, now)
    }

    @Test fun taskOrderDoesNotChangeRevisionsOrValidation() {
        val first = capture().json().getJSONArray("tasks")
        val reversed = ContextSnapshot.capture(tasks.reversed(), trip, now = now).json().getJSONArray("tasks")
        assertEquals(first.toString(), reversed.toString())
        capture().requireCurrent(tasks.reversed(), trip, now)
    }

    @Test fun integerCoordinatesRoundTripWithoutChangingTaskRevision() {
        val task = printing.copy(placeJson = Place("print", "打印店", 116.0, 40.0).json().toString())
        val snapshot = ContextSnapshot.capture(listOf(task), trip, now = now)
        ContextSnapshot.fromJson(snapshot.json()).requireCurrent(listOf(task), trip, now)
    }

    @Test fun allScopeDetectsAddedTasksWhileSelectedScopeIgnoresUnrelatedTasks() {
        val extra = Errand(id = "extra", title = "买水")
        capture().requireCurrent(tasks + extra, trip, now)
        val all = ContextSnapshot.capture(tasks, trip, taskScope = "all", now = now)
        stale { all.requireCurrent(tasks + extra, trip, now) }
    }

    @Test fun selectedScopeStillDetectsMissingSelectedTask() {
        val snapshot = ContextSnapshot.capture(listOf(printing), trip, now = now)
        snapshot.requireCurrent(tasks + Errand(id = "other", title = "买水"), trip, now)
        stale { snapshot.requireCurrent(listOf(parcel), trip, now) }
    }

    @Test fun proposedTaskDoesNotRequirePrematurePersistence() {
        val proposed = Errand(id = "new-printing", title = "打印论文")
        val snapshot = ContextSnapshot.capture(listOf(parcel), trip, taskScope = "all", now = now,
            proposedTasks = listOf(proposed))
        snapshot.requireCurrent(listOf(parcel), trip, now)
        assertEquals("new-printing", snapshot.json().getJSONArray("proposed_task_ids").getString(0))
        assertEquals(2, snapshot.json().getJSONArray("tasks").length())
        stale { snapshot.requireCurrent(listOf(parcel, proposed), trip, now) }
    }

    @Test fun proposedIdsMustBeUniqueTaskMembersWithoutStoredOverlap() {
        malformed { capture().json().put("proposed_task_ids", org.json.JSONArray(listOf("missing"))) }
        malformed { capture().json().put("proposed_task_ids", org.json.JSONArray(listOf("printing", "printing"))) }
        try {
            ContextSnapshot.capture(tasks, trip, now = now, proposedTasks = listOf(printing))
            fail("proposed task overlaps stored task")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun planVersionRestorationAndExecutionChangesInvalidatePreview() {
        val changed = listOf(trip.copy(planVersion = 4), trip.copy(needsReplan = true),
            trip.copy(started = true), trip.copy(planId = "other"), trip.copy(sessionId = "other"),
            trip.copy(requestJson = "{}"), trip.copy(resultJson = "{\"distance\":100}"))
        changed.forEach { stale { capture().requireCurrent(tasks, it, now) } }
        stale { capture().requireCurrent(tasks, null, now) }
        stale { ContextSnapshot.capture(tasks, null, now = now).requireCurrent(tasks, trip, now) }
    }

    @Test fun clockRollbackAndExpiredSnapshotCannotExecute() {
        stale { capture().requireCurrent(tasks, trip, now - 1) }
        capture().requireCurrent(tasks, trip, now + 30 * 60_000)
        stale { capture().requireCurrent(tasks, trip, now + 30 * 60_000 + 1) }
    }

    @Test fun staleDeviceLocationCannotExecuteEvenWithFreshSnapshot() {
        val snapshot = ContextSnapshot.capture(tasks, trip, now = now,
            location = ContextLocation("device", now - 5 * 60_000, 39.9, 116.4, 20.0))
        snapshot.requireCurrent(tasks, trip, now)
        stale { snapshot.requireCurrent(tasks, trip, now + 1) }
    }

    @Test fun cachedLocationUsesObservationTimeAndSelectedLocationNeedsNoGpsTimestamp() {
        val cached = ContextSnapshot.capture(tasks, trip, now = now,
            location = ContextLocation("cached", now - 5 * 60_000 - 1, 39.9, 116.4))
        stale { cached.requireCurrent(tasks, trip, now) }
        ContextSnapshot.capture(tasks, trip, now = now,
            location = ContextLocation("selected", latitude = 39.9, longitude = 116.4))
            .requireCurrent(tasks, trip, now)
    }

    @Test fun serializingAndParsingPreservesIndependentSnapshot() {
        val original = capture()
        val json = original.json()
        val parsed = ContextSnapshot.fromJson(json)
        json.getJSONArray("tasks").getJSONObject(0).put("title", "外部修改")
        parsed.requireCurrent(tasks, trip, now)
        assertEquals(original.json().toString(), parsed.json().toString())
        assertEquals("ready", parsed.json().getJSONObject("device").getString("navigation_state"))
    }

    @Test fun unknownSchemaAndMalformedTypesAreRejected() {
        malformed { capture().json().put("schema_version", 2) }
        malformed { capture().json().put("schema_version", "1") }
        malformed { capture().json().put("captured_at", now.toString()) }
        malformed { capture().json().put("snapshot_id", "not-a-uuid") }
        malformed { capture().json().put("task_scope", "other") }
        malformed { capture().json().apply { remove("plan") } }
        malformed { capture().json().apply { getJSONObject("plan").put("plan_version", true) } }
        malformed { capture().json().apply { getJSONObject("location").put("source", "gps_guess") } }
        malformed { capture().json().apply { getJSONObject("device").put("navigation_state", "unknown") } }
    }

    @Test fun alteredTaskFieldsCannotKeepAnOldRevision() {
        malformed { capture().json().apply { getJSONArray("tasks").getJSONObject(0).put("title", "别的任务") } }
        malformed { capture().json().apply { getJSONArray("tasks").getJSONObject(0).put("revision", "A".repeat(64)) } }
    }

    @Test fun duplicateTaskIdsAndImpossibleLocationAreRejected() {
        malformed { capture().json().apply { getJSONArray("tasks").put(getJSONArray("tasks").getJSONObject(0)) } }
        malformed { capture().json().apply { getJSONObject("location").put("source", "device") } }
        malformed { capture().json().apply { getJSONObject("location").put("latitude", 91.0) } }
        try {
            ContextSnapshot.capture(tasks + printing, trip, now = now)
            fail("duplicate task ids accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun oversizedTaskListFailsExplicitlyWithoutTruncation() {
        val hundred = (1..100).map { Errand(id = "task-$it", title = "待办 $it") }
        assertEquals(100, ContextSnapshot.capture(hundred, null, now = now).json().getJSONArray("tasks").length())
        try {
            ContextSnapshot.capture(hundred + printing, null, now = now)
            fail("oversized snapshot accepted")
        } catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("100")) }
    }

    @Test fun planMetadataCannotReuseAnUnchangedRevision() {
        val altered = ContextSnapshot.fromJson(capture().json().apply { getJSONObject("plan").put("plan_version", 4) })
        stale { altered.requireCurrent(tasks, trip, now) }
    }

    @Test fun selectedOriginCannotPretendToBeAGpsObservation() {
        try {
            ContextSnapshot.capture(tasks, trip, now = now,
                location = ContextLocation("selected", now, 39.9, 116.4))
            fail("selected origin carried a GPS timestamp")
        } catch (_: IllegalArgumentException) { }
        malformed { capture().json().apply { remove("proposed_task_ids") } }
    }

    private fun capture() = ContextSnapshot.capture(tasks, trip, now = now)

    @Test fun exportsAndroidWireFixtureForBackendContractCheck() {
        val snapshot = ContextSnapshot.capture(tasks, trip, taskScope = "all", now = now,
            location = ContextLocation("cached", now - 30_000, 39.9, 116.4, 12.0),
            proposedTasks = listOf(Errand(id = "water", title = "买水")))
        snapshot.requireCurrent(tasks, trip, now)
        println("CONTEXT_WIRE_FIXTURE:" + snapshot.json())
    }
    private fun stale(action: () -> Unit): String {
        try { action(); fail("stale snapshot accepted") } catch (failure: IllegalStateException) { return failure.message.orEmpty() }
        error("unreachable")
    }
    private fun malformed(json: () -> JSONObject) {
        try { ContextSnapshot.fromJson(json()); fail("malformed snapshot accepted") } catch (_: IllegalArgumentException) { }
    }
}
