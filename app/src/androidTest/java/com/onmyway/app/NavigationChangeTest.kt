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
            val stored = SavedTrip(requestJson = body.toString(), resultJson = "{\"stops\":[]}", started = true, startedAt = 123)
            db.trips().save(stored)
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
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
            assertEquals(1, db.trips().history().size)
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }
}
