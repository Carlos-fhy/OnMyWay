package com.onmyway.app.feature.navigation

import androidx.room.withTransaction
import com.onmyway.app.data.event.BusinessEventFactory
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.BusinessEventOutbox
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import org.json.JSONObject

internal data class NavigationExecutionIdentity(val sessionId: String, val planId: String, val planVersion: Int) {
    fun matches(trip: SavedTrip): Boolean =
        sessionId == trip.sessionId && planId == trip.planId && planVersion == trip.planVersion
}

internal interface NavigationStartStore {
    suspend fun transaction(block: suspend () -> SavedTrip): SavedTrip
    suspend fun load(): SavedTrip?
    suspend fun save(trip: SavedTrip)
    suspend fun record(history: RouteHistory)
    suspend fun enqueue(event: BusinessEventOutbox)
}

internal class RoomNavigationStartStore(private val database: TaskDatabase) : NavigationStartStore {
    override suspend fun transaction(block: suspend () -> SavedTrip) = database.withTransaction { block() }
    override suspend fun load() = database.trips().load()
    override suspend fun save(trip: SavedTrip) = database.trips().save(trip)
    override suspend fun record(history: RouteHistory) = database.trips().record(history)
    override suspend fun enqueue(event: BusinessEventOutbox) { database.businessEvents().enqueue(event) }
}

/** Called only after the SDK confirms start. The durable state and outbox commit together. */
internal class NavigationStartRecorder(private val store: NavigationStartStore, private val correlationId: String) {
    suspend fun record(identity: NavigationExecutionIdentity, observedAt: Long, attemptId: String = ""): SavedTrip = store.transaction {
        val trip = checkNotNull(store.load()) { "导航方案已结束" }
        check(identity.matches(trip)) { "导航方案已变化" }
        check(!trip.needsReplan || trip.started) { "待办已变化，请重新规划" }
        val opened = trip.copy(started = true, startedAt = trip.startedAt.takeIf { it > 0 } ?: observedAt)
        store.save(opened)
        if (!trip.started) store.record(RouteHistory(
            sessionId = trip.sessionId, planId = trip.planId, planVersion = trip.planVersion,
            state = NavigationState.NAVIGATION_OPENED.name, timestamp = observedAt,
            requestJson = trip.requestJson, resultJson = trip.resultJson,
        ))
        store.enqueue(BusinessEventFactory.navigationStarted(
            sessionId = trip.sessionId, planId = trip.planId, planVersion = trip.planVersion,
            request = JSONObject(trip.requestJson), correlationId = correlationId, occurredAt = observedAt,
            attemptId = attemptId,
        ))
        opened
    }
}
