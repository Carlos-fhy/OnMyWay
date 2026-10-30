package com.onmyway.app.feature.navigation

import com.onmyway.app.core.context.ContextSnapshot
import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import org.junit.Assert.*
import org.junit.Test

class NavigationChoiceContextTest {
    @Test fun recoveredDestinationChoicesRetainOriginalTaskVersions() {
        val printing = Errand(id = "print", title = "打印论文")
        val trip = SavedTrip(requestJson = "{}", resultJson = "{}")
        val context = ContextSnapshot.capture(listOf(printing), trip)
        val state = NavigationConversationState()
        state.setChoices(listOf(Place("dorm", "宿舍", 120.0, 31.0)), setOf(printing.id), "回宿舍", null)
        val saved = state.snapshot(trip).put("choice_context", context.json())
        val restored = NavigationConversationState()
        assertTrue(restored.restore(saved, trip))
        val preserved = restored.snapshot(trip).optJSONObject("choice_context")
        assertNotNull("Recovered choice lost the context of the original decision", preserved)
        val original = ContextSnapshot.fromJson(checkNotNull(preserved))
        assertEquals(context.json().getLong("captured_at"), original.json().getLong("captured_at"))
        try {
            original.requireCurrent(listOf(printing.copy(title = "打印两份论文")), trip)
            fail("Choosing a destination must not renew stale task versions")
        } catch (_: IllegalStateException) { }
    }
}
