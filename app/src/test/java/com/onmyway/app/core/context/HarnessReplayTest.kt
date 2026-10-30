package com.onmyway.app.core.context

import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import java.io.File
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Offline replay of production context guards; no Room commit, model, route SDK, or device execution. */
class HarnessReplayTest {
    private val now = 1_800_000_000_000L
    private val origin = Place("campus", "教学楼", 116.40, 39.90)
    private val dorm = Place("dorm", "宿舍", 116.42, 39.91)
    private val parcel = Errand(id = "parcel", title = "取快递", placeType = "PARCEL",
        placeJson = Place("station", "快递站", 116.41, 39.90).json().toString(), createdAt = now)
    private val printing = Errand(id = "printing", title = "打印论文", placeType = "PRINT_SHOP",
        placeJson = Place("print-shop", "打印店", 116.41, 39.91).json().toString(),
        serviceMinutes = 10, createdAt = now)
    private val tasks = listOf(parcel, printing)
    private val trip = SavedTrip(sessionId = "session-campus", planId = "plan-dorm", planVersion = 3,
        requestJson = JSONObject().put("origin", origin.json()).put("destination", dorm.json())
            .put("selected_task_ids", JSONArray(listOf("parcel", "printing"))).toString(),
        resultJson = "{}", plannedAt = now - 1_000)

    @Test fun deletedPrintingRejectsOldContextAndFreshContextKeepsParcelAndDorm() = replay(
        "android_deleted_printing_refresh", "取消打印任务，保留快递和宿舍",
    ) { record ->
        val old = capture()
        record.context("before_change", old, tasks, trip, now, accepted = true)
        val remaining = listOf(parcel)
        record.context("old_context_after_delete", old, remaining, trip, now + 1_000,
            accepted = false, reason = "已删除")
        val fresh = ContextSnapshot.capture(remaining, trip, now = now + 1_000)
        record.context("fresh_context", fresh, remaining, trip, now + 1_000, accepted = true)
        record.same("fresh_dependency_ids", "parcel", taskIds(fresh))
        record.same("old_dependency_ids_preserved", "parcel,printing", taskIds(old))
        record.same("parcel_revision_preserved", revision(old, "parcel"), revision(fresh, "parcel"))
        record.same("destination_preserved", "宿舍", destination(trip))
    }

    @Test fun editedPrintingRejectsOldContextAndFreshContextKeepsOtherTask() = replay(
        "android_edited_printing_refresh", "打印两份论文，快递和宿舍不变",
    ) { record ->
        val old = capture()
        val changed = listOf(parcel, printing.copy(title = "打印两份论文", serviceMinutes = 20))
        record.context("old_context_after_edit", old, changed, trip, now + 1_000,
            accepted = false, reason = "已修改或完成")
        val fresh = ContextSnapshot.capture(changed, trip, now = now + 1_000)
        record.context("fresh_context", fresh, changed, trip, now + 1_000, accepted = true)
        record.same("parcel_revision_preserved", revision(old, "parcel"), revision(fresh, "parcel"))
        record.same("printing_revision_changed", true, revision(old, "printing") != revision(fresh, "printing"))
        record.same("destination_preserved", "宿舍", destination(trip))
    }

    @Test fun unselectedCandidateChangeInvalidatesAutomaticRecommendationContext() = replay(
        "android_unselected_candidate_changed", "自动推荐只选择快递，期间打印任务完成",
    ) { record ->
        val old = ContextSnapshot.capture(tasks, trip, taskScope = "all", now = now)
        record.input.put("selected_task_ids", JSONArray(listOf("parcel")))
        val changed = listOf(parcel, printing.copy(status = "done"))
        record.context("old_all_candidate_context", old, changed, trip, now + 1_000,
            accepted = false, reason = "已修改或完成")
        val fresh = ContextSnapshot.capture(changed, trip, taskScope = "all", now = now + 1_000)
        record.context("fresh_all_candidate_context", fresh, changed, trip, now + 1_000, accepted = true)
        record.same("parcel_revision_preserved", revision(old, "parcel"), revision(fresh, "parcel"))
    }

    @Test fun restoringRequestDoesNotRefreshExpiredSnapshot() = replay(
        "android_restore_expired_snapshot", "恢复先前回宿舍的请求",
    ) { record ->
        val old = capture()
        val persisted = old.json().toString()
        record.input.put("persisted_snapshot", persisted)
        val restored = ContextSnapshot.fromJson(JSONObject(persisted))
        record.same("snapshot_id_preserved", old.json().getString("snapshot_id"), restored.json().getString("snapshot_id"))
        record.same("captured_at_preserved", now, restored.json().getLong("captured_at"))
        record.context("restored_at_ttl_boundary", restored, tasks, trip, now + 30 * 60_000,
            accepted = true)
        record.context("restored_after_ttl", restored, tasks, trip, now + 30 * 60_000 + 1,
            accepted = false, reason = "超过 30 分钟")
    }

    @Test fun restoredCachedLocationKeepsObservationTimeAndExpires() = replay(
        "android_restore_cached_location", "重开路线预览后继续回宿舍",
    ) { record ->
        val observedAt = now - 60_000
        val location = ContextLocation("cached", observedAt, origin.latitude, origin.longitude, 15.0)
        val old = ContextSnapshot.capture(tasks, trip, location = location, now = now)
        val request = JSONObject(trip.requestJson).put("context_snapshot", old.json())
        record.input.put("persisted_request", request)
        val restoredLocation = restoredContextLocation(JSONObject(request.toString()))
        record.same("restored_location_source", "cached", restoredLocation.source)
        record.same("observation_time_preserved", observedAt, restoredLocation.observedAt)
        val reopenedAt = now + 5 * 60_000
        val freshSnapshot = ContextSnapshot.capture(tasks, trip, location = restoredLocation, now = reopenedAt)
        record.context("new_snapshot_with_old_observation", freshSnapshot, tasks, trip, reopenedAt,
            accepted = false, reason = "定位已超过 5 分钟")
    }

    @Test fun missingOrMismatchedOriginEvidenceRemainsUnknown() = replay(
        "android_missing_origin_evidence", "恢复缺少定位来源证据的路线",
    ) { record ->
        val missing = JSONObject(trip.requestJson)
        record.input.put("request_without_snapshot", missing)
        val missingLocation = restoredContextLocation(missing)
        record.same("missing_evidence_source", "unknown", missingLocation.source)
        record.same("missing_evidence_has_no_observation", null, missingLocation.observedAt)
        val snapshot = ContextSnapshot.capture(tasks, trip, now = now,
            location = ContextLocation("device", now, origin.latitude, origin.longitude, 10.0))
        val changedOrigin = JSONObject(trip.requestJson).put("context_snapshot", snapshot.json())
            .put("origin", origin.copy(longitude = 116.48).json())
        record.input.put("request_with_changed_origin", changedOrigin)
        val mismatched = restoredContextLocation(changedOrigin)
        record.same("mismatched_evidence_source", "unknown", mismatched.source)
        record.same("mismatched_evidence_has_no_coordinates", null, mismatched.latitude)
    }

    @Test fun newPlanIdentityRejectsOldSnapshotAndCanCaptureCurrentState() = replay(
        "android_changed_plan_identity", "途中更新路线后尝试确认旧预览",
    ) { record ->
        val old = capture()
        val newTrip = trip.copy(planVersion = 4, sessionId = "session-updated")
        record.context("old_context_for_new_plan", old, tasks, newTrip, now + 1_000,
            accepted = false, reason = "当前行程或计划版本已变化")
        val fresh = ContextSnapshot.capture(tasks, newTrip, now = now + 1_000)
        record.context("fresh_context_for_new_plan", fresh, tasks, newTrip, now + 1_000, accepted = true)
        record.same("current_plan_version", 4, fresh.json().getJSONObject("plan").getInt("plan_version"))
        record.same("old_plan_version_preserved", 3, old.json().getJSONObject("plan").getInt("plan_version"))
        record.same("destination_preserved", "宿舍", destination(newTrip))
    }

    @Test fun unrelatedTaskChangeDoesNotInvalidateExplicitParcelSelection() = replay(
        "android_selected_scope_unrelated_change", "明确只规划快递，打印任务另行修改",
    ) { record ->
        val selected = ContextSnapshot.capture(listOf(parcel), trip, taskScope = "selected", now = now)
        val changed = listOf(parcel, printing.copy(title = "打印两份论文"))
        record.context("selected_parcel_context", selected, changed, trip, now + 1_000, accepted = true)
        record.same("dependency_ids", "parcel", taskIds(selected))
        record.same("destination_preserved", "宿舍", destination(trip))
    }

    private fun capture() = ContextSnapshot.capture(tasks, trip, now = now)
    private fun destination(value: SavedTrip) = JSONObject(value.requestJson).getJSONObject("destination").getString("name")
    private fun taskIds(snapshot: ContextSnapshot): String = snapshot.json().getJSONArray("tasks").let { values ->
        (0 until values.length()).map { values.getJSONObject(it).getString("id") }.sorted().joinToString(",")
    }
    private fun revision(snapshot: ContextSnapshot, id: String): String = snapshot.json().getJSONArray("tasks").let { values ->
        (0 until values.length()).map(values::getJSONObject).single { it.getString("id") == id }.getString("revision")
    }

    private fun replay(id: String, instruction: String, block: (CaseRecord) -> Unit) {
        val record = CaseRecord(id, instruction)
        try {
            block(record)
            record.passed = true
        } catch (failure: Throwable) {
            record.actual.put("failure", JSONObject().put("type", failure.javaClass.simpleName)
                .put("message", failure.message ?: ""))
            throw failure
        } finally {
            synchronized(results) {
                results[id] = record.json()
                writeReport()
            }
        }
    }

    private class CaseRecord(private val id: String, instruction: String) {
        val input = JSONObject().put("instruction", instruction).put("operations", JSONArray())
        val actual = JSONObject()
        private val expected = JSONObject()
        private val checks = JSONArray()
        var passed = false

        fun context(step: String, snapshot: ContextSnapshot, tasks: List<Errand>, trip: SavedTrip?,
                    now: Long, accepted: Boolean, reason: String? = null) {
            input.getJSONArray("operations").put(JSONObject().put("step", step).put("operation", "requireCurrent")
                .put("context_snapshot", snapshot.json()).put("now", now)
                .put("current_state", ContextSnapshot.capture(tasks, trip, now = now).json())
                .put("current_trip_request", trip?.let { JSONObject(it.requestJson) } ?: JSONObject.NULL))
            var rejection: String? = null
            try {
                snapshot.requireCurrent(tasks, trip, now)
            } catch (failure: IllegalStateException) {
                rejection = failure.message.orEmpty()
            }
            val wasAccepted = rejection == null
            expected.put(step, JSONObject().put("accepted", accepted).put("reason_contains", reason ?: JSONObject.NULL))
            actual.put(step, JSONObject().put("accepted", wasAccepted).put("reason", rejection ?: JSONObject.NULL))
            check(step, accepted == wasAccepted && (reason == null || rejection.orEmpty().contains(reason)))
        }

        fun same(step: String, expectedValue: Any?, actualValue: Any?) {
            expected.put(step, expectedValue ?: JSONObject.NULL)
            actual.put(step, actualValue ?: JSONObject.NULL)
            check(step, expectedValue == actualValue)
        }

        private fun check(step: String, matched: Boolean) {
            checks.put(JSONObject().put("name", step).put("passed", matched))
            assertTrue("$id / $step: expected=${expected.get(step)} actual=${actual.get(step)}", matched)
        }

        fun json(): JSONObject = JSONObject().put("id", id).put("passed", passed)
            .put("evidence_scope", "production_context_preconditions_only")
            .put("input", input).put("expected", expected).put("actual", actual).put("checks", checks)
    }

    companion object {
        private val results = sortedMapOf<String, JSONObject>()
        private val runId = System.getenv("ONMYWAY_REPLAY_RUN_ID")?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        @BeforeClass @JvmStatic fun initializeReport() = synchronized(results) {
            results.clear()
            writeReport()
        }

        private fun writeReport() {
            val project = File(checkNotNull(System.getProperty("user.dir")))
            val app = if (File(project, "src/main/AndroidManifest.xml").isFile) project else File(project, "app")
            val reportFile = File(app, "build/reports/harness-replay/android-cases.json")
            val reportDirectory = checkNotNull(reportFile.parentFile)
            check(reportDirectory.isDirectory || reportDirectory.mkdirs()) { "Cannot create replay report directory" }
            val passed = results.values.count { it.getBoolean("passed") }
            val report = JSONObject().put("schema_version", 1).put("run_id", runId)
                .put("generated_at", Instant.now().toString()).put("mode", "offline_jvm")
                .put("evidence_scope", "ContextSnapshot and restoredContextLocation on JVM; no Room transaction, real model, route calculation, SDK or device execution")
                .put("summary", JSONObject().put("total", results.size).put("passed", passed).put("failed", results.size - passed))
                .put("cases", JSONArray(results.values.toList()))
            reportFile.writeText(report.toString(2) + "\n", Charsets.UTF_8)
        }
    }
}
