package com.onmyway.app

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NavigationConfirmationBoundaryTest {
    private val end = Place("end", "Destination", 121.01, 31.01)
    private val shop = Place("shop", "Selected shop", 121.005, 31.005)

    private fun body(tasks: List<Errand> = emptyList()) = JSONObject()
        .put("origin", Place("origin", "Origin", 121.0, 31.0).json())
        .put("destination", end.json()).put("mode", "walking")
        .put("preference", "fastest").put("allow_partial", false)
        .put("max_extra_minutes", JSONObject.NULL).put("arrive_by", JSONObject.NULL)
        .put("tasks", JSONArray(tasks.map(Errand::json)))

    private suspend fun withModel(
        tasks: List<Errand> = emptyList(),
        tools: NavigationRouteTools? = null,
        block: suspend (AppViewModel, TaskDatabase, SavedTrip, NavigationRequestDelivery) -> Unit,
    ) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val journalName = "navigation-boundary-${UUID.randomUUID()}.db"
        val delivery = NavigationRequestDelivery(app, journalName)
        var model: AppViewModel? = null
        try {
            tasks.forEach { db.tasks().save(it) }
            // An orphaned started trip is intentionally ended on process recreation. This fixture
            // tests confirmation transactions without claiming a live SDK navigation session.
            val trip = SavedTrip(requestJson = body(tasks).toString(), resultJson = "{\"stops\":[]}",
                started = false, startedAt = 123L)
            db.trips().save(trip)
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db, delivery, tools) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(10) }
            assertEquals(trip, withContext(Dispatchers.Main) { vm.planningBase() })
            withContext(Dispatchers.Main) { block(vm, db, trip, delivery) }
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
            delivery.close()
            db.close()
            app.deleteDatabase(journalName)
        }
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        try { block(); fail("Invalid confirmation was accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun supersededAndMissingPreviewCannotBeConfirmed() = runBlocking {
        withModel { vm, db, trip, _ ->
            val change = NavigationChange(trip, body(), JSONObject("{\"stops\":[]}"), emptyList())
            expectRejected { vm.confirmNavigationChange(change) }
            vm.navigationConversation.draft = change.copy(versionId = UUID.randomUUID().toString())
            expectRejected { vm.confirmNavigationChange(change) }
            assertEquals(trip, db.trips().load())
            assertTrue(db.trips().history().isEmpty())
        }
    }

    @Test fun changedDatabaseTripIsRejectedEvenWhenViewModelStillHasOldTrip() = runBlocking {
        withModel { vm, db, trip, _ ->
            val change = NavigationChange(trip, body(), JSONObject("{\"stops\":[]}"), emptyList())
            vm.navigationConversation.draft = change
            val updated = trip.copy(resultJson = "{\"stops\":[],\"total_seconds\":900}")
            db.trips().save(updated)
            expectRejected { vm.confirmNavigationChange(change) }
            assertEquals(updated, db.trips().load())
            assertTrue(db.trips().history().isEmpty())
        }
    }

    @Test fun changedDatabaseTaskIsRejectedWithoutWaitingForObservedTaskList() = runBlocking {
        val task = Errand(id = "task", title = "Original", placeType = "SUPERMARKET")
        withModel(listOf(task)) { vm, db, trip, _ ->
            val change = NavigationChange(trip, body(listOf(task)), JSONObject("{\"stops\":[]}"), listOf(task))
            vm.navigationConversation.draft = change
            val updated = task.copy(title = "Changed", updatedAt = task.updatedAt + 1)
            db.tasks().save(updated)
            expectRejected { vm.confirmNavigationChange(change) }
            assertEquals(updated, db.tasks().get(task.id))
            assertEquals(trip, db.trips().load())
            assertTrue(db.trips().history().isEmpty())
        }
    }

    @Test fun addedTaskCannotOverwriteAnExistingTask() = runBlocking {
        withModel { vm, db, trip, _ ->
            val proposed = Errand(id = "collision", title = "New stop", placeJson = shop.json().toString())
            val change = NavigationChange(trip, body(listOf(proposed)), JSONObject("{\"stops\":[]}"),
                emptyList(), proposed)
            vm.navigationConversation.draft = change
            val existing = proposed.copy(title = "Already saved")
            db.tasks().save(existing)
            expectRejected { vm.confirmNavigationChange(change) }
            assertEquals(existing, db.tasks().get(existing.id))
            assertEquals(trip, db.trips().load())
            assertTrue(db.trips().history().isEmpty())
        }
    }

    @Test fun expiredAndFuturePreviewsCannotBeConfirmed() = runBlocking {
        withModel { vm, db, trip, _ ->
            for (createdAt in listOf(System.currentTimeMillis() - 61_000L, System.currentTimeMillis() + 61_000L, -1L)) {
                val change = NavigationChange(trip, body(), JSONObject("{\"stops\":[]}"), emptyList(),
                    createdAt = createdAt)
                vm.navigationConversation.draft = change
                expectRejected { vm.confirmNavigationChange(change) }
            }
            assertEquals(trip, db.trips().load())
            assertTrue(db.trips().history().isEmpty())
        }
    }

    @Test fun recordedVersionCannotBeConfirmedAgainEvenWhenTripStillMatches() = runBlocking {
        withModel { vm, db, trip, _ ->
            val change = NavigationChange(trip, body(), JSONObject("{\"stops\":[]}"), emptyList())
            vm.navigationConversation.draft = change
            db.trips().record(RouteHistory(id = change.versionId, sessionId = trip.sessionId,
                planId = trip.planId, state = NavigationState.NAVIGATION_OPENED.name,
                timestamp = trip.plannedAt, requestJson = trip.requestJson, resultJson = trip.resultJson))
            expectRejected { vm.confirmNavigationChange(change) }
            assertEquals(trip, db.trips().load())
            assertEquals(1, db.trips().history().size)
        }
    }

    @Test fun confirmationUsesImmutablePreviewAndPersistsClearedPendingProposal() = runBlocking {
        val task = Errand(id = "task", title = "Buy water", placeJson = shop.json().toString())
        withModel(listOf(task)) { vm, db, trip, delivery ->
            val request = body(listOf(task))
            val route = JSONObject("{\"stops\":[],\"total_seconds\":100}")
            val taskList = mutableListOf(task)
            val change = NavigationChange(trip, request, route, taskList)
            vm.navigationConversation.draft = change
            vm.navigationConversation.setProposal(request, null)
            val expectedBody = change.bodyJson
            val expectedRoute = change.routeJson
            request.put("destination", end.copy(id = "wrong", name = "Wrong destination").json())
            route.put("total_seconds", 999)
            change.body.put("mode", "cycling")
            change.route.put("total_seconds", 888)
            taskList.clear()
            assertTrue(vm.tasks.value.isEmpty()) // Confirmation must read Room, not this inactive flow.
            vm.confirmNavigationChange(change)
            assertEquals(expectedBody, db.trips().load()?.requestJson)
            assertEquals(expectedRoute, db.trips().load()?.resultJson)
            assertNotNull(db.trips().historyById(change.versionId))
            assertNull(vm.navigationConversation.draft)
            assertNull(vm.navigationConversation.proposedBody)
            assertTrue(delivery.loadContext()!!.isNull("proposed_body"))
            assertEquals(assistantTripContext(db.trips().load()), delivery.loadContext()!!.getString("base_fingerprint"))
        }
    }

    @Test fun recoveryRejectsDifferentRoutePlanTimeAndLegacyContext() {
        val trip = SavedTrip(requestJson = body().toString(), resultJson = "{\"stops\":[]}")
        val state = NavigationConversationState().apply { setProposal(body(), null) }
        val snapshot = state.snapshot(trip)
        assertTrue(NavigationConversationState().restore(snapshot, trip))
        assertFalse(NavigationConversationState().restore(snapshot, trip.copy(resultJson = "{\"stops\":[],\"total_seconds\":99}")))
        assertFalse(NavigationConversationState().restore(snapshot, trip.copy(plannedAt = trip.plannedAt + 1)))
        val legacy = JSONObject(snapshot.toString()).apply { remove("base_fingerprint") }
        assertFalse(NavigationConversationState().restore(legacy, trip))
    }

    @Test fun recalculationUsesCurrentTaskFieldsAndKeepsACompatibleSelectedStop() = runBlocking {
        val original = Errand(id = "task", title = "Old title", placeType = "SUPERMARKET", serviceMinutes = 5)
        var plannedBody: JSONObject? = null
        val tools = object : NavigationRouteTools {
            override suspend fun origin() = Place("current", "Current location", 121.002, 31.002)
            override suspend fun search(server: String, keyword: String, city: String) = emptyList<Place>()
            override suspend fun plan(server: String, body: JSONObject): JSONObject {
                plannedBody = JSONObject(body.toString())
                return JSONObject("{\"stops\":[],\"total_seconds\":100}")
            }
        }
        withModel(listOf(original), tools) { vm, db, _, _ ->
            val proposed = body(listOf(original))
            proposed.getJSONArray("tasks").getJSONObject(0).put("place", shop.json()).put("place_type", JSONObject.NULL)
            vm.navigationConversation.setProposal(proposed, null)
            val current = original.copy(title = "Current title", serviceMinutes = 20, updatedAt = original.updatedAt + 1)
            db.tasks().save(current)
            val change = vm.recalculateNavigationProposal()
            val requested = plannedBody!!.getJSONArray("tasks").getJSONObject(0)
            assertEquals(current.title, requested.getString("title"))
            assertEquals(current.serviceMinutes, requested.getInt("service_minutes"))
            assertEquals(shop.id, requested.getJSONObject("place").getString("id"))
            assertEquals(listOf(current), change.tasks)
            assertEquals(change.versionId, vm.navigationConversation.draft?.versionId)
            assertEquals("current", plannedBody!!.getJSONObject("origin").getString("id"))
            vm.confirmNavigationChange(change)
            assertEquals(current.title, JSONObject(db.trips().load()!!.requestJson).getJSONArray("tasks").getJSONObject(0).getString("title"))
        }
    }

    @Test fun recalculationUsesAChangedFixedPlaceInsteadOfTheOldSelectedStop() = runBlocking {
        val original = Errand(id = "task", title = "Pick up parcel", placeType = "PARCEL_LOCKER",
            placeJson = shop.json().toString())
        val replacement = Place("new-locker", "New locker", 121.008, 31.008)
        val tools = object : NavigationRouteTools {
            override suspend fun origin() = Place("current", "Current location", 121.002, 31.002)
            override suspend fun search(server: String, keyword: String, city: String) = emptyList<Place>()
            override suspend fun plan(server: String, body: JSONObject) = JSONObject("{\"stops\":[]}")
        }
        withModel(listOf(original), tools) { vm, db, _, _ ->
            vm.navigationConversation.setProposal(body(listOf(original)), null)
            val current = original.copy(placeJson = replacement.json().toString(), updatedAt = original.updatedAt + 1)
            db.tasks().save(current)
            val change = vm.recalculateNavigationProposal()
            val requested = change.body.getJSONArray("tasks").getJSONObject(0)
            assertEquals(replacement.id, requested.getJSONObject("place").getString("id"))
            assertEquals(listOf(current), change.tasks)
        }
    }
}
