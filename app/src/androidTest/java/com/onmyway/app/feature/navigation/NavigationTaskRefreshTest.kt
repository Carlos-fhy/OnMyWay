package com.onmyway.app.feature.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.refreshedNavigationTasks
import com.onmyway.app.core.state.retainedNavigationTasks
import com.onmyway.app.data.local.entity.Errand
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTaskRefreshTest {
    private val oldPlace = Place("old", "Old shop", 120.0, 31.0)
    private val newPlace = Place("new", "New shop", 120.1, 31.1)

    @Test fun previewUsesChangedFixedPlaceDeadlineAndServiceDuration() {
        val original = Errand(id = "task", title = "Print", placeType = "PRINT_SHOP",
            placeJson = oldPlace.json().toString(), serviceMinutes = 5, deadline = "2026-10-10T15:00:00+08:00")
        val current = original.copy(title = "Print final thesis", placeJson = newPlace.json().toString(),
            priority = 1, serviceMinutes = 20, deadline = "2026-10-10T18:00:00+08:00")
        val request = JSONArray().put(original.json())
        val selected = retainedNavigationTasks(request, JSONObject().put("stops", JSONArray()), setOf(original.id))
        val refreshed = refreshedNavigationTasks(request, JSONArray(selected), listOf(current)).single()

        assertEquals(newPlace.id, refreshed.getJSONObject("place").getString("id"))
        assertEquals(current.title, refreshed.getString("title"))
        assertEquals(current.deadline, refreshed.getString("deadline"))
        assertEquals(20, refreshed.getInt("service_minutes"))
        assertEquals(1, refreshed.getInt("priority"))
        assertEquals(oldPlace.id, request.getJSONObject(0).getJSONObject("place").getString("id"))
        assertEquals(5, selected.single().getInt("service_minutes"))
    }

    @Test fun compatibleGenericTaskKeepsActualStopAndRefreshesConstraints() {
        val original = Errand(id = "task", title = "Print", placeType = "PRINT_SHOP")
        val current = original.copy(serviceMinutes = 12, deadline = "2026-10-10T17:00:00+08:00")
        val request = JSONArray().put(original.json())
        val route = JSONObject().put("stops", JSONArray().put(JSONObject()
            .put("place", oldPlace.json()).put("task_ids", JSONArray().put(original.id))))
        val selected = retainedNavigationTasks(request, route, setOf(original.id))
        val refreshed = refreshedNavigationTasks(request, JSONArray(selected), listOf(current)).single()

        assertEquals(oldPlace.id, refreshed.getJSONObject("place").getString("id"))
        assertTrue(refreshed.isNull("place_type"))
        assertEquals(12, refreshed.getInt("service_minutes"))
        assertEquals(current.deadline, refreshed.getString("deadline"))
        assertTrue(request.getJSONObject(0).isNull("place"))
    }

    @Test fun changedGenericTypeOrClearedFixedPlaceDoesNotReuseOldStop() {
        val original = Errand(id = "task", title = "Print", placeType = "PRINT_SHOP")
        val selected = JSONArray().put(original.json().put("place", oldPlace.json()).put("place_type", JSONObject.NULL))
        val changedType = refreshedNavigationTasks(JSONArray().put(original.json()), selected,
            listOf(original.copy(placeType = "PHARMACY"))).single()
        assertTrue(changedType.isNull("place"))
        assertEquals("PHARMACY", changedType.getString("place_type"))

        val fixedOriginal = original.copy(placeJson = oldPlace.json().toString())
        val cleared = refreshedNavigationTasks(JSONArray().put(fixedOriginal.json()), selected, listOf(original)).single()
        assertTrue(cleared.isNull("place"))
        assertEquals("PRINT_SHOP", cleared.getString("place_type"))
    }
}
