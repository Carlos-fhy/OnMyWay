package com.onmyway.app.feature.assistant

import android.app.Application
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.remote.assistantTripContext
import com.onmyway.app.feature.planning.PlanningChoices
import com.onmyway.app.feature.planning.PlanningDraft
import com.onmyway.app.feature.planning.PlanningDraftTask
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantResponseTransactionTest {
    private lateinit var app: Application
    private lateinit var databaseName: String
    private lateinit var prefs: SharedPreferences
    private lateinit var db: TaskDatabase
    private lateinit var store: AssistantStore

    @Before fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        databaseName = "assistant_response_" + UUID.randomUUID()
        prefs = app.getSharedPreferences(databaseName, 0)
        openDatabase()
    }

    @After fun tearDown() {
        db.close()
        app.deleteDatabase(databaseName)
        app.deleteSharedPreferences(databaseName)
    }

    private fun openDatabase() {
        db = Room.databaseBuilder(app, TaskDatabase::class.java, databaseName).build()
        store = AssistantStore(db, prefs)
    }

    private fun route(draftId: String, destination: String = "Library") = JSONObject()
        .put("action", "plan_route").put("destination", destination).put("mode", "cycling")
        .put("_draft_id", draftId).put("_plan_id", "plan-1")

    private suspend fun seedChoices(draftId: String): Pair<PlanningChoices, ClarificationState> {
        store.setDraft(route(draftId))
        val choices = PlanningChoices(
            PlanningDraft(draftId = draftId, planId = "plan-1", destination = "Library", mode = "cycling",
                budget = 12, placeId = "library", placeName = "Library", longitude = 120.0,
                latitude = 31.0, address = "Campus", savedAt = 1_000L),
            // AssistantDao.planningTasks() has a stable ORDER BY id contract.
            listOf(PlanningDraftTask("parcel", false), PlanningDraftTask("water", true)),
        )
        val clarification = ClarificationState(
            AssistantClarification(draftId = draftId, planId = "plan-1", kind = "DESTINATION",
                prompt = "Choose a library", destination = "Library", mode = "cycling", savedAt = 1_000L),
            listOf(Place("first", "First library", 120.0, 31.0).toClarificationChoice(draftId, 0),
                Place("second", "Second library", 120.1, 31.1).toClarificationChoice(draftId, 1)),
        )
        store.saveChoices(choices)
        store.saveClarification(clarification)
        return choices to clarification
    }

    @Test fun committedResponseSurvivesDatabaseReopenAndIsConsumedOnce() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val user = ChatLine("user", "Go to the library")
        val reply = ChatLine("user", "Choose the route") // Persisted role must be assistant.
        store.add(user) // Sending already persisted the user message.
        assertFalse(store.responseApplied(requestId))
        assertTrue(store.applyResponse(requestId, null, user, reply, route(requestId), true))
        db.close()
        openDatabase()

        assertTrue(store.responseApplied(requestId))
        assertEquals(requestId, db.assistant().draft()?.draftId)
        assertEquals("CONFIRM_ROUTE", db.assistant().clarification()?.kind)
        assertFalse(store.applyResponse(requestId, null, user, reply, route(requestId), true))
        val messages = db.assistant().recentMessages()
        assertEquals(2, messages.size)
        assertEquals(1, messages.count { it.id == user.id })
        assertEquals("assistant", messages.single { it.id == "agent-response-$requestId" }.role)
        assertFalse(messages.any { it.id == reply.id })
    }

    @Test fun replayedOldResponseCannotRemoveNewDraftOrItsChoices() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val user = ChatLine("user", "Go to the library")
        val reply = ChatLine("assistant", "Ready to plan")
        assertTrue(store.applyResponse(requestId, null, user, reply, route(requestId), true))
        val (choices, question) = seedChoices("new-draft")

        assertFalse(store.applyResponse(requestId, requestId, user, reply, null, true))
        assertEquals("new-draft", db.assistant().draft()?.draftId)
        assertEquals(choices, store.planningChoices())
        assertEquals(question, store.clarification())
        assertEquals(2, db.assistant().recentMessages().size)
    }

    @Test fun contextMismatchRejectsAllWritesIncludingNullContext() = runBlocking {
        val (choices, question) = seedChoices("current-draft")
        val user = ChatLine("user", "Old instruction")
        val reply = ChatLine("assistant", "Old result")
        for (expected in listOf(null, "previous-draft")) {
            val requestId = UUID.randomUUID().toString()
            assertFalse(store.applyResponse(requestId, expected, user, reply, route(requestId), true))
            assertFalse(store.responseApplied(requestId))
        }
        assertEquals("current-draft", db.assistant().draft()?.draftId)
        assertEquals(choices, store.planningChoices())
        assertEquals(question, store.clarification())
        assertTrue(db.assistant().recentMessages().isEmpty())

        store.setDraft(null)
        val requestId = UUID.randomUUID().toString()
        assertFalse(store.applyResponse(requestId, "current-draft", user, reply, route(requestId), true))
        assertFalse(store.responseApplied(requestId))
        assertNull(db.assistant().draft())
        assertTrue(db.assistant().recentMessages().isEmpty())
    }

    @Test fun draftWriteFailureRollsBackMessagesAndClearedChoices() = runBlocking {
        val (choices, question) = seedChoices("current-draft")
        val before = db.assistant().draft()
        val requestId = UUID.randomUUID().toString()
        val user = ChatLine("user", "Change destination")
        val reply = ChatLine("assistant", "Here is the new destination")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_response_draft BEFORE INSERT ON assistant_draft " +
                "BEGIN SELECT RAISE(ABORT, 'Injected draft write failure'); END",
        )

        val failed = runCatching {
            store.applyResponse(requestId, "current-draft", user, reply, route(requestId, "Dormitory"), true)
        }
        assertTrue(failed.isFailure)
        assertFalse(store.responseApplied(requestId))
        assertTrue(db.assistant().recentMessages().isEmpty())
        assertEquals(before, db.assistant().draft())
        assertEquals(choices, store.planningChoices())
        assertEquals(question, store.clarification())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_response_draft")
        assertTrue(store.applyResponse(requestId, "current-draft", user, reply, route(requestId, "Dormitory"), true))
        assertEquals("Dormitory", db.assistant().draft()?.destination)
        assertNull(store.planningChoices())
        assertTrue(db.assistant().placeChoices().isEmpty())
        assertEquals("CONFIRM_ROUTE", store.clarification()?.question?.kind)
    }

    @Test fun replyOnlyKeepsDraftAndUserChoicesUnchanged() = runBlocking {
        val (choices, question) = seedChoices("current-draft")
        val before = db.assistant().draft()
        val requestId = UUID.randomUUID().toString()
        assertTrue(store.applyResponse(requestId, "current-draft", ChatLine("user", "What did I choose?"),
            ChatLine("assistant", "Water is selected"), null, false))
        assertTrue(store.responseApplied(requestId))
        assertEquals(before, db.assistant().draft())
        assertEquals(choices, store.planningChoices())
        assertEquals(question, store.clarification())
        assertEquals(2, db.assistant().recentMessages().size)
    }

    @Test fun invalidRequestIdCannotCreateMessageReceiptOrReplaceDraft() = runBlocking {
        for (requestId in listOf("", "request-id", "1-1-1-1-1")) {
            assertTrue(runCatching { store.responseApplied(requestId) }.isFailure)
            assertTrue(runCatching {
                store.applyResponse(requestId, null, ChatLine("user", "Go to the library"),
                    ChatLine("assistant", "Ready"), route("new-draft"), true)
            }.isFailure)
        }
        assertTrue(db.assistant().recentMessages().isEmpty())
        assertNull(db.assistant().draft())
    }

    @Test fun tripCreatedAfterRequestRejectsResponseAndKeepsCurrentChoices() = runBlocking {
        val (choices, question) = seedChoices("current-draft")
        val expectedTrip = assistantTripContext(null)
        val trip = SavedTrip(requestJson = "{\"mode\":\"cycling\"}", resultJson = "{}")
        db.trips().save(trip)
        val requestId = UUID.randomUUID().toString()

        assertFalse(store.applyResponse(requestId, "current-draft", ChatLine("user", "Change destination"),
            ChatLine("assistant", "Old response"), route(requestId, "Dormitory"), true,
            expectedTripContext = expectedTrip))
        assertFalse(store.responseApplied(requestId))
        assertTrue(db.assistant().recentMessages().isEmpty())
        assertEquals("current-draft", db.assistant().draft()?.draftId)
        assertEquals(choices, store.planningChoices())
        assertEquals(question, store.clarification())
        assertEquals(trip, db.trips().load())
    }

    @Test fun tripStartedAfterRequestRejectsResponseInsideTransaction() = runBlocking {
        val trip = SavedTrip(requestJson = "{\"mode\":\"cycling\"}", resultJson = "{}", started = false)
        db.trips().save(trip)
        val expectedTrip = assistantTripContext(db.trips().load())
        val navigating = trip.copy(started = true, startedAt = System.currentTimeMillis())
        db.trips().save(navigating)
        val requestId = UUID.randomUUID().toString()

        assertFalse(store.applyResponse(requestId, null, ChatLine("user", "Change destination"),
            ChatLine("assistant", "Old response"), route(requestId, "Dormitory"), true,
            expectedTripContext = expectedTrip))
        assertFalse(store.responseApplied(requestId))
        assertTrue(db.assistant().recentMessages().isEmpty())
        assertNull(db.assistant().draft())
        assertEquals(navigating, db.trips().load())
    }
}
