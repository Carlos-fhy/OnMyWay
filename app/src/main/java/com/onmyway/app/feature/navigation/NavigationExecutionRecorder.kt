package com.onmyway.app.feature.navigation

import androidx.room.withTransaction
import com.onmyway.app.core.context.ContextSnapshot
import com.onmyway.app.core.model.TripMetrics
import com.onmyway.app.data.event.BusinessEventFactory
import com.onmyway.app.data.execution.CapabilityRegistry
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.BusinessEventOutbox
import com.onmyway.app.data.local.entity.CapabilityRun
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import org.json.JSONArray
import org.json.JSONObject

internal interface NavigationExecutionStore {
    suspend fun <T> transaction(block: suspend () -> T): T
    suspend fun trip(): SavedTrip?
    suspend fun tasks(): List<Errand>
    suspend fun run(runId: String): CapabilityRun?
    suspend fun save(run: CapabilityRun)
    suspend fun save(trip: SavedTrip)
    suspend fun record(history: RouteHistory)
    suspend fun enqueue(event: BusinessEventOutbox)
}

internal enum class NavigationClaimResult { CLAIMED, ALREADY_RUNNING, UNKNOWN, COMPLETED, FAILED }

internal class RoomNavigationExecutionStore(private val database: TaskDatabase) : NavigationExecutionStore {
    override suspend fun <T> transaction(block: suspend () -> T) = database.withTransaction { block() }
    override suspend fun trip() = database.trips().load()
    override suspend fun tasks() = database.tasks().all()
    override suspend fun run(runId: String) = database.capabilityRuns().load(runId)
    override suspend fun save(run: CapabilityRun) = database.capabilityRuns().save(run)
    override suspend fun save(trip: SavedTrip) = database.trips().save(trip)
    override suspend fun record(history: RouteHistory) = database.trips().record(history)
    override suspend fun enqueue(event: BusinessEventOutbox) { database.businessEvents().enqueue(event) }
}

/** A claim authorizes one SDK attempt; only matching device receipts advance it. */
internal class NavigationExecutionRecorder(private val store: NavigationExecutionStore, private val correlationId: String) {
    suspend fun prepareAttempt(identity: NavigationExecutionIdentity, attemptId: String, observedAt: Long): Boolean = store.transaction {
        require(attemptId.isNotBlank() && observedAt > 0)
        val trip = currentTrip(identity)
        val existing = store.run(runId(identity))
        if (existing == null && trip.started) {
            store.save(legacyUnknown(trip, observedAt))
            return@transaction false
        }
        if (existing != null && (existing.state in setOf("running", "unknown", "completed") ||
                existing.step in closedSteps ||
                existing.attemptId == attemptId && (existing.state == "failed" || existing.step == "paused"))) {
            return@transaction false
        }
        requireCurrentInputs(trip, observedAt)
        val run = existing ?: newRun(trip, observedAt)
        store.save(run.copy(state = "waiting_confirmation", step = "preparing", attemptId = attemptId,
            resultJson = "", error = "", updatedAt = observedAt))
        true
    }

    /** Recovery observes loss of the process owner; it never fabricates a device cancellation. */
    suspend fun recover(identity: NavigationExecutionIdentity, observedAt: Long): CapabilityRun? = store.transaction {
        val trip = store.trip()?.takeIf(identity::matches) ?: return@transaction null
        val existing = store.run(runId(identity))
        val recovered = when {
            existing == null && trip.started -> legacyUnknown(trip, observedAt)
            existing?.state == "running" -> existing.copy(state = "unknown", step = "reconcile_required",
                error = "导航进程已重建，请核对执行记录后重新规划。", updatedAt = observedAt)
            else -> existing
        }
        if (recovered != null && recovered != existing) store.save(recovered)
        recovered
    }

    /** User closure is terminal but is not proof of SDK arrival. The caller owns its user event. */
    suspend fun recordClosed(identity: NavigationExecutionIdentity, observedAt: Long, arrivedManually: Boolean): Boolean = store.transaction {
        val trip = store.trip()?.takeIf(identity::matches) ?: return@transaction false
        val existing = store.run(runId(identity))
        if (existing?.state == "completed" || existing?.step in closedSteps) return@transaction true
        val run = existing ?: newRun(trip, observedAt)
        store.save(run.copy(state = "failed", step = if (arrivedManually) "manual_end" else "user_cancelled",
            resultJson = result(if (arrivedManually) "closed" else "cancelled", observedAt), error = "",
            updatedAt = observedAt))
        true
    }
    suspend fun prepare(identity: NavigationExecutionIdentity, observedAt: Long): CapabilityRun = store.transaction {
        val trip = currentTrip(identity)
        store.run(runId(identity))?.let { return@transaction it }
        requireCurrentInputs(trip, observedAt)
        newRun(trip, observedAt).also { store.save(it) }
    }

    suspend fun claim(identity: NavigationExecutionIdentity, attemptId: String, observedAt: Long): NavigationClaimResult = store.transaction {
        require(attemptId.isNotBlank() && observedAt > 0)
        val trip = currentTrip(identity)
        val existing = store.run(runId(identity))
        if (existing == null && trip.started) {
            store.save(legacyUnknown(trip, observedAt))
            return@transaction NavigationClaimResult.UNKNOWN
        }
        if (existing?.step in closedSteps) return@transaction NavigationClaimResult.COMPLETED
        when (existing?.state) {
            "completed" -> return@transaction NavigationClaimResult.COMPLETED
            "unknown" -> return@transaction NavigationClaimResult.UNKNOWN
            "running" -> {
                if (existing.step == "sdk_started" || existing.attemptId == attemptId) {
                    return@transaction NavigationClaimResult.ALREADY_RUNNING
                }
                // Another controller cannot tell whether the claimed SDK call happened.
                store.save(existing.copy(state = "unknown", step = "reconcile_required",
                    error = "导航启动结果尚未核对", updatedAt = observedAt))
                return@transaction NavigationClaimResult.UNKNOWN
            }
            "failed" -> if (existing.attemptId == attemptId) {
                return@transaction NavigationClaimResult.FAILED
            }
            "waiting_confirmation" -> if (existing.attemptId == attemptId && existing.step == "paused") {
                return@transaction NavigationClaimResult.FAILED
            }
            null -> Unit
            else -> return@transaction NavigationClaimResult.UNKNOWN
        }
        requireCurrentInputs(trip, observedAt)
        val run = existing ?: newRun(trip, observedAt)
        store.save(run.copy(state = "running", step = "claimed", attemptId = attemptId,
            error = "", resultJson = "", updatedAt = observedAt))
        NavigationClaimResult.CLAIMED
    }

    suspend fun recordStarted(identity: NavigationExecutionIdentity, attemptId: String, observedAt: Long): SavedTrip? = store.transaction {
        val (trip, run) = matchingAttempt(identity, attemptId) ?: return@transaction null
        if (run.state !in setOf("running", "unknown")) return@transaction null
        if (run.state == "running" && run.step == "sdk_started") return@transaction trip
        val started = trip.copy(started = true, startedAt = trip.startedAt.takeIf { it > 0 } ?: observedAt)
        store.save(started)
        if (!trip.started) store.record(history(trip, NavigationState.NAVIGATION_OPENED, observedAt))
        store.enqueue(BusinessEventFactory.navigationStarted(
            sessionId = trip.sessionId, planId = trip.planId, planVersion = trip.planVersion,
            request = JSONObject(trip.requestJson), correlationId = run.correlationId, occurredAt = observedAt,
            attemptId = attemptId, executionId = run.runId,
        ))
        store.save(run.copy(state = "running", step = "sdk_started", error = "",
            resultJson = result("started", observedAt), updatedAt = observedAt))
        started
    }

    suspend fun recordArrived(identity: NavigationExecutionIdentity, attemptId: String, observedAt: Long, metrics: TripMetrics?): Boolean = store.transaction {
        val (trip, run) = matchingAttempt(identity, attemptId) ?: return@transaction false
        if (run.state !in setOf("running", "unknown") ||
            runCatching { JSONObject(run.resultJson).optString("state") }.getOrNull() != "started") return@transaction false
        val route = JSONObject(trip.resultJson)
        val observedMetrics = metrics?.copy(
            predictedSeconds = metrics.predictedSeconds ?: route.optInt("total_seconds", -1).takeIf { it >= 0 },
            predictedMeters = metrics.predictedMeters ?: route.optInt("total_meters", -1).takeIf { it >= 0 },
        )
        store.record(history(trip, NavigationState.ARRIVED, observedAt, withNavigationMetrics(trip.resultJson, observedMetrics)))
        store.enqueue(BusinessEventFactory.navigationArrived(
            sessionId = trip.sessionId, planId = trip.planId, planVersion = trip.planVersion,
            request = JSONObject(trip.requestJson), metrics = observedMetrics, correlationId = run.correlationId,
            occurredAt = observedAt, executionId = run.runId, attemptId = attemptId,
        ))
        store.save(run.copy(state = "completed", step = "sdk_arrived", error = "",
            resultJson = result("arrived", observedAt), updatedAt = observedAt))
        true
    }

    suspend fun recordFailed(identity: NavigationExecutionIdentity, attemptId: String, observedAt: Long, reason: String): Boolean = store.transaction {
        require(attemptId.isNotBlank() && observedAt > 0)
        val (trip, run) = matchingAttempt(identity, attemptId) ?: return@transaction false
        val preparation = run.state == "waiting_confirmation" && run.step == "preparing"
        if (!preparation && run.state !in setOf("running", "unknown")) return@transaction false
        store.record(history(trip, NavigationState.FAILED, observedAt))
        store.enqueue(BusinessEventFactory.navigationFailed(
            sessionId = trip.sessionId, planId = trip.planId, planVersion = trip.planVersion,
            request = JSONObject(trip.requestJson), reason = reason.take(300), correlationId = run.correlationId,
            occurredAt = observedAt, executionId = run.runId, attemptId = attemptId,
        ))
        store.save(run.copy(state = "failed", step = if (preparation) "preparation_failed" else "sdk_failed",
            attemptId = attemptId, error = reason.take(300), resultJson = result("failed", observedAt), updatedAt = observedAt))
        true
    }

    suspend fun markUnknown(identity: NavigationExecutionIdentity, attemptId: String, observedAt: Long, reason: String): Boolean = store.transaction {
        val (_, run) = matchingAttempt(identity, attemptId) ?: return@transaction false
        if (run.state !in setOf("running", "unknown")) return@transaction false
        store.save(run.copy(state = "unknown", step = "reconcile_required", error = reason.take(300), updatedAt = observedAt))
        true
    }

    suspend fun recordPaused(identity: NavigationExecutionIdentity, attemptId: String, observedAt: Long): Boolean = store.transaction {
        val (trip, run) = matchingAttempt(identity, attemptId) ?: return@transaction false
        if (run.state != "running") return@transaction false
        store.record(history(trip, NavigationState.PAUSED, observedAt))
        store.save(run.copy(state = "waiting_confirmation", step = "paused", error = "", updatedAt = observedAt))
        true
    }

    private suspend fun matchingAttempt(identity: NavigationExecutionIdentity, attemptId: String): Pair<SavedTrip, CapabilityRun>? {
        val trip = store.trip()?.takeIf(identity::matches) ?: return null
        val run = store.run(runId(identity))?.takeIf { it.attemptId == attemptId } ?: return null
        return trip to run
    }

    private suspend fun currentTrip(identity: NavigationExecutionIdentity): SavedTrip {
        CapabilityRegistry.require("start_navigation", 1).validate(JSONObject()
            .put("plan_id", identity.planId).put("plan_version", identity.planVersion).put("session_id", identity.sessionId))
        check(identity.sessionId.isNotBlank() && identity.planId.isNotBlank() && identity.planVersion > 0) {
            "方案版本无法核对，请重新规划。"
        }
        return checkNotNull(store.trip()) { "导航方案已结束。" }.also {
            check(identity.matches(it)) { "导航方案已变化，请重新规划。" }
        }
    }

    private suspend fun requireCurrentInputs(trip: SavedTrip, now: Long) {
        check(!trip.needsReplan) { "待办已变化，请重新规划。" }
        val context = JSONObject(trip.requestJson).optJSONObject("context_snapshot")
        check(context != null) { "旧方案缺少任务版本，请重新规划。" }
        // The decision snapshot refers to the predecessor plan. Keep its task revisions
        // and observed times, while checking execution against this confirmed plan.
        val currentPlan = ContextSnapshot.capture(emptyList(), trip, now = now).json()
        context.put("plan", currentPlan.get("plan")).put("device", currentPlan.get("device"))
        val currentTasks = store.tasks()
        val proposed = context.getJSONArray("proposed_task_ids")
        if (proposed.length() > 0) {
            val expectedTasks = context.getJSONArray("tasks")
            val expected = (0 until expectedTasks.length()).map { expectedTasks.getJSONObject(it) }
                .associateBy { it.getString("id") }
            val current = currentTasks.associateBy { it.id }
            for (index in 0 until proposed.length()) {
                val id = proposed.getString(index)
                val task = checkNotNull(current[id]) { "新增待办尚未正式保存，请重新确认方案。" }
                val revision = ContextSnapshot.capture(listOf(task), null, now = now).json()
                    .getJSONArray("tasks").getJSONObject(0).getString("revision")
                check(revision == expected[id]?.getString("revision")) { "新增待办已修改，请重新规划。" }
            }
            // The exact proposed content was committed at confirmation; keep its original
            // observation time and revisions, but now require it to exist in local storage.
            context.put("proposed_task_ids", JSONArray())
        }
        ContextSnapshot.fromJson(context).requireCurrent(currentTasks, trip, now)
    }

    private fun newRun(trip: SavedTrip, observedAt: Long): CapabilityRun = CapabilityRun(
        runId = runId(NavigationExecutionIdentity(trip.sessionId, trip.planId, trip.planVersion)),
        capabilityId = "start_navigation", executor = "android", state = "waiting_confirmation", step = "awaiting_start",
        snapshotJson = JSONObject(trip.requestJson).optJSONObject("context_snapshot")?.toString().orEmpty(),
        correlationId = correlationId, planId = trip.planId, planVersion = trip.planVersion, sessionId = trip.sessionId,
        createdAt = observedAt, updatedAt = observedAt,
    )

    private fun result(state: String, observedAt: Long) = JSONObject().put("state", state).put("observed_at", observedAt).toString()
    private fun legacyUnknown(trip: SavedTrip, observedAt: Long) = newRun(trip, observedAt).copy(
        state = "unknown", step = "reconcile_required", error = "旧导航缺少启动回执，请结束并重新规划。",
    )
    private fun history(trip: SavedTrip, state: NavigationState, observedAt: Long, resultJson: String = trip.resultJson) = RouteHistory(
        sessionId = trip.sessionId, planId = trip.planId, planVersion = trip.planVersion,
        state = state.name, timestamp = observedAt, requestJson = trip.requestJson, resultJson = resultJson,
    )

    companion object {
        private val closedSteps = setOf("manual_end", "user_cancelled")
        fun runId(identity: NavigationExecutionIdentity): String = java.util.UUID.nameUUIDFromBytes(
            JSONArray().put("onmyway:start_navigation:1").put(identity.sessionId).put(identity.planId)
                .put(identity.planVersion).toString().toByteArray(Charsets.UTF_8),
        ).toString()
    }
}
