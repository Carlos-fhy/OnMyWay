package com.onmyway.app.feature.assistant

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.RouteHistory
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantConfirmationBoundaryTest {
    private suspend fun withAssistant(block: suspend (AssistantViewModel, TaskDatabase) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "confirmation_boundary_" + UUID.randomUUID()
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val chat = withContext(Dispatchers.Main) {
            AssistantViewModel(app, app.getSharedPreferences(prefsName, 0), db)
        }
        try {
            withTimeout(5000) { while (!withContext(Dispatchers.Main) { chat.ready }) delay(20) }
            block(chat, db)
        } finally {
            withContext(Dispatchers.Main) { chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            db.close()
            app.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun lateConfirmationPreservesReplacementDraftAndEditor() = runBlocking {
        withAssistant { chat, db ->
            var replacementId = ""
            withContext(Dispatchers.Main) {
                chat.proposal = JSONObject().put("action", "plan_route").put("destination", "Library").put("mode", "walking")
                val confirmedId = chat.proposal!!.getString("_draft_id")
                chat.proposal = JSONObject().put("action", "plan_route").put("destination", "Dorm").put("mode", "cycling")
                replacementId = chat.proposal!!.getString("_draft_id")
                chat.preparingRoute = true
                chat.completeRoute(confirmedId, JSONObject().put("extra_seconds", 0), "confirmed-version", "Saved route")
                assertEquals(replacementId, chat.proposal!!.getString("_draft_id"))
                assertTrue(chat.preparingRoute)
                assertNull(chat.routeResult)
            }
            withTimeout(5000) {
                while (db.assistant().draft()?.draftId != replacementId || db.assistant().recentMessages().isEmpty()) delay(20)
            }
            assertEquals("Dorm", db.assistant().draft()?.destination)
            assertEquals("confirmed-version", db.assistant().recentMessages().single().routeHistoryId)
        }
    }

    @Test fun matchingConfirmationConsumesOnlyItsOwnProposal() = runBlocking {
        withAssistant { chat, db ->
            withContext(Dispatchers.Main) {
                chat.proposal = JSONObject().put("action", "plan_route").put("destination", "Library").put("mode", "walking")
                chat.preparingRoute = true
                val route = JSONObject().put("extra_seconds", 0)
                chat.completeRoute(chat.proposal!!.getString("_draft_id"), route, "confirmed-version", "Saved route")
                assertNull(chat.proposal)
                assertFalse(chat.preparingRoute)
                assertSame(route, chat.routeResult)
            }
            withTimeout(5000) {
                while (db.assistant().recentMessages().isEmpty()) delay(20)
            }
            assertNull(db.assistant().draft())
        }
    }

    @Test fun historyRepeatDoesNotReplaceProposalWhileAssistantIsWorking() = runBlocking {
        withAssistant { chat, db ->
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }
            try {
                withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
                withContext(Dispatchers.Main) {
                    chat.proposal = JSONObject().put("action", "plan_route").put("destination", "Library").put("mode", "walking")
                    val expected = chat.proposal
                    chat.busy = true
                    // An intentionally empty request must never be parsed while a replacement is forbidden.
                    val old = RouteHistory(sessionId = "old", state = "CANCELLED", timestamp = 1L,
                        requestJson = "{}", resultJson = "{}")
                    assertFalse(chat.repeatRoute(old, vm))
                    assertSame(expected, chat.proposal)
                    chat.busy = false
                }
            } finally {
                withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        }
    }
}
