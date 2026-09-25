package com.onmyway.app

import android.app.Application
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationChangeTest {
    @Test fun confirmationPreservesSessionAndRejectsOldPreview() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val place = Place("end", "end", 121.0, 31.0)
            val body = JSONObject().put("origin", place.json()).put("destination", place.json())
                .put("mode", "walking").put("preference", "fastest").put("allow_partial", false)
                .put("max_extra_minutes", 20).put("arrive_by", JSONObject.NULL).put("tasks", JSONArray())
            val initial = SavedTrip(requestJson = body.toString(), resultJson = "{\"stops\":[]}", startedAt = 123)
            db.trips().save(initial)
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            withContext(Dispatchers.Main) { vm.launchTrip { true } }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.updatingTrip }) delay(20) }
            val stored = db.trips().load()!!
            val newDestination = place.copy(id = "new", name = "new destination")
            val changedBody = JSONObject(body.toString()).put("destination", newDestination.json())
            val change = NavigationChange(stored, changedBody, JSONObject("{\"stops\":[],\"total_seconds\":100}"), emptyList())
            assertEquals(stored, db.trips().load()) // A draft alone has no side effects.
            withContext(Dispatchers.Main) {
                val memory = vm.navigationConversation
                memory.draft = NavigationChange(stored, body, JSONObject("{\"stops\":[]}"), emptyList())
                memory.record("user", "之前的调整")
                try { vm.previewNavigationChange("直接去目的地"); fail("unchanged route accepted") }
                catch (_: IllegalStateException) { }
                assertNull(memory.draft)
                assertNotNull(memory.proposedBody)
                assertTrue(memory.history.any { it.second == "之前的调整" })
                vm.confirmNavigationChange(change)
                assertNull(memory.proposedBody)
                assertTrue(vm.tripStarted)
                assertEquals(newDestination, vm.destination)
                assertFalse(vm.editingPlan)
                try { vm.confirmNavigationChange(change); fail("stale preview accepted") }
                catch (_: IllegalStateException) { }
            }
            val updated = db.trips().load()!!
            assertEquals(stored.sessionId, updated.sessionId)
            assertEquals(123L, updated.startedAt)
            assertTrue(updated.started)
            assertEquals(newDestination, Place.from(JSONObject(updated.requestJson).getJSONObject("destination")))
            assertEquals(2, db.trips().history().size)
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }

    @Test fun confirmedRouteStopsAreKeptAsActualPlacesWhenAddingAnotherStop() {
        val planned = JSONObject().put("id", "print").put("title", "打印论文")
            .put("place", JSONObject.NULL).put("place_type", "PRINT_SHOP")
        val untouched = JSONObject().put("id", "other").put("title", "其他")
            .put("place", JSONObject.NULL).put("place_type", "SUPERMARKET")
        val tasks = JSONArray().put(planned).put(untouched)
        val shop = Place("shop", "校内打印店", 120.576, 31.249)
        val stop = JSONObject().put("place", shop.json()).put("task_ids", JSONArray().put("print"))
        val actual = JSONObject().put("stops", JSONArray().put(stop))
        val kept = retainedNavigationTasks(tasks, actual, setOf("print", "other"))
        assertEquals(shop.id, kept[0].getJSONObject("place").getString("id"))
        assertTrue(kept[0].isNull("place_type"))
        assertTrue(kept[1].isNull("place"))
        assertTrue(planned.isNull("place")) // The saved trip stays unchanged until confirmation.
    }

    @Test fun newStopSurvivesDestinationChoiceAndSessionRecovery() {
        val destination = Place("new-end", "上方山森林公园", 120.0, 31.0)
        val newTask = Errand(id = "water", title = "买瓶水", placeType = "SUPERMARKET")
        val state = NavigationConversationState()
        state.setChoices(listOf(destination), setOf("parcel"), "取完快递去上方山，中途买水", newTask)
        val request = JSONObject().put("tasks", JSONArray())
        val trip = SavedTrip(requestJson = request.toString(), resultJson = "{}")
        val restored = NavigationConversationState()
        assertTrue(restored.restore(state.snapshot(trip), trip))
        assertEquals(setOf("parcel"), restored.pendingIds)
        assertEquals(newTask.id, restored.pendingChoiceNewTask?.id)
        assertEquals(destination.id, restored.places.single().id)
        restored.invalidatePreview()
        assertNull(restored.pendingChoiceNewTask)
    }

    @Test fun addedStopIsSavedOnlyOnConfirmationAndRecoveredPreviewNeedsRecalculation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val origin = Place("origin", "origin", 121.0, 31.0)
            val destination = Place("end", "end", 121.01, 31.01)
            val shop = Place("shop", "shop", 121.005, 31.005)
            val body = JSONObject().put("origin", origin.json()).put("destination", destination.json())
                .put("mode", "walking").put("preference", "fastest").put("allow_partial", false)
                .put("max_extra_minutes", JSONObject.NULL).put("arrive_by", JSONObject.NULL)
                .put("tasks", JSONArray())
            val stored = SavedTrip(requestJson = body.toString(), resultJson = "{\"stops\":[]}")
            db.trips().save(stored)
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            val newTask = Errand(id = "new-stop", title = "买瓶水", placeType = "SUPERMARKET", placeJson = shop.json().toString())
            val changedBody = JSONObject(body.toString()).put("tasks", JSONArray().put(newTask.json()))
            val state = NavigationConversationState()
            state.record("user", "顺路买瓶水")
            val change = NavigationChange(stored, changedBody, JSONObject("{\"stops\":[],\"total_seconds\":100}"), emptyList(), newTask)
            state.draft = change
            state.setProposal(changedBody, newTask)
            val restored = NavigationConversationState()
            assertTrue(restored.restore(state.snapshot(stored), stored))
            assertNull(restored.draft)
            assertEquals(newTask.id, restored.pendingNewTask?.id)
            assertFalse(restored.restore(state.snapshot(stored), stored.copy(sessionId = "different")))
            assertNull(db.tasks().get(newTask.id))
            assertEquals(stored, db.trips().load())
            withContext(Dispatchers.Main) { vm.confirmNavigationChange(change) }
            assertEquals(newTask.id, db.tasks().get(newTask.id)?.id)
            assertTrue(db.trips().load()!!.requestJson.contains(newTask.id))
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }
}
