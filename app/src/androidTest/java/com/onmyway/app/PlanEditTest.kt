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
class PlanEditTest {
    @Test fun interruptedNavigationIsArchivedInsteadOfRestored() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val place = Place("end", "宿舍", 121.0, 31.0)
            val request = JSONObject().put("origin", place.json()).put("destination", place.json())
                .put("mode", "cycling").put("preference", "fastest").put("allow_partial", false)
                .put("max_extra_minutes", 10).put("arrive_by", JSONObject.NULL).put("tasks", JSONArray())
            db.trips().save(SavedTrip(requestJson = request.toString(), resultJson = "{\"stops\":[]}", started = true))
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            assertNull(db.trips().load())
            val archived = db.trips().history().single()
            assertEquals("CANCELLED", archived.state)
            assertEquals("宿舍", Place.from(JSONObject(archived.requestJson).getJSONObject("destination")).name)
            withContext(Dispatchers.Main) {
                assertNull(vm.result)
                assertFalse(vm.tripStarted)
                assertEquals(NavigationState.CANCELLED, vm.navigationState)
            }
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }

    @Test fun cancelAndInvalidReplanKeepPersistedTrip() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val place = Place("test", "测试终点", 121.0, 31.0)
            val request = JSONObject().put("origin", place.json()).put("destination", place.json())
                .put("mode", "walking").put("preference", "fastest").put("allow_partial", false)
                .put("max_extra_minutes", 20).put("arrive_by", JSONObject.NULL)
                .put("tasks", JSONArray().put(JSONObject().put("id", "a")))
            val stored = SavedTrip(requestJson = request.toString(), resultJson = "{\"stops\":[]}", started = false)
            db.trips().save(stored)
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            withContext(Dispatchers.Main) {
                val task = Errand(id = "a", title = "买东西")
                val replacement = place.copy(id = "replacement", name = "另一家门店")
                vm.chooseTripPlace(task.id, replacement)
                assertEquals(replacement, vm.taskForTrip(task).place())
                assertNull(task.place()) // A trip override must not change the task default.
                assertNotNull(vm.result)
                vm.cancelPlanEdit()
                assertTrue(vm.tripPlaces.isEmpty())
                vm.chooseTripPlace(task.id, replacement)
                vm.resetTripPlace(task.id)
                assertNull(vm.taskForTrip(task).place())
                vm.cancelPlanEdit()
                vm.beginPlanEdit()
                vm.destination = place.copy(name = "新终点")
                vm.mode = "cycling"; vm.extra = "50"; vm.partial = true; vm.selected = emptySet()
                vm.inputsChanged()
                vm.arriveBy = "invalid"
                var assistantReceivedResult = false
                vm.planForAssistant { assistantReceivedResult = true }
                assertFalse(assistantReceivedResult) // A rejected plan cannot produce a success card.
                assertNotNull(vm.result)
                assertFalse(vm.tripStarted)
                assertTrue(vm.editingPlan)
                vm.cancelPlanEdit()
                assertEquals(place, vm.destination)
                assertEquals("walking", vm.mode)
                assertEquals("20", vm.extra)
                assertEquals(setOf("a"), vm.selected)
                assertFalse(vm.partial)
                assertFalse(vm.editingPlan)
            }
            assertEquals(stored, db.trips().load())
            withContext(Dispatchers.Main) { vm.launchTrip { true } }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.updatingTrip }) delay(20) }
            withContext(Dispatchers.Main) {
                assertEquals(NavigationState.NAVIGATION_OPENED, vm.navigationState)
                assertTrue(vm.finishTrip(false))
            }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.updatingTrip }) delay(20) }
            assertNull(db.trips().load())
            assertEquals("CANCELLED", db.trips().history().first().state)
            withContext(Dispatchers.Main) {
                assertEquals(NavigationState.CANCELLED, vm.navigationState)
                assertNull(vm.result)
                assertNull(vm.destination)
                assertTrue(vm.selected.isEmpty())
            }
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }
}
