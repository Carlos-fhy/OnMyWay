package com.onmyway.app

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AssistantConversationTest {
    @Test fun planningChoicesSurviveStoreRecreationAndClearWithIntent() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "planning_choices_test_" + UUID.randomUUID()
        val prefs = app.getSharedPreferences(prefsName, 0)
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        try {
            val store = AssistantStore(db, prefs)
            store.setDraft(JSONObject().put("action", "plan_route").put("destination", "Library").put("mode", "cycling").put("_draft_id", "first"))
            val saved = PlanningChoices(PlanningDraft(draftId = "first", destination = "Library", mode = "cycling", budget = 17,
                placeId = "poi", placeName = "Library", longitude = 120.0, latitude = 31.0,
                address = "Campus", savedAt = 1000),
                listOf(PlanningDraftTask("a", true), PlanningDraftTask("b", false)))
            store.saveChoices(saved)
            assertEquals(saved, AssistantStore(db, prefs).planningChoices())
            // Same destination and mode are a new request, not permission for the old writer.
            store.setDraft(JSONObject().put("action", "plan_route").put("destination", "Library")
                .put("mode", "cycling").put("_draft_id", "second"))
            val fresh = saved.copy(draft = saved.draft.copy(draftId = "second", planId = "second", budget = 8))
            store.saveChoices(fresh)
            store.saveChoices(saved)
            assertEquals(fresh, AssistantStore(db, prefs).planningChoices())

            store.setDraft(JSONObject().put("action", "plan_route").put("destination", "Dorm").put("mode", "walking"))
            assertNull(store.planningChoices())
            store.saveChoices(saved) // Late writes from the old intent must be ignored.
            assertNull(store.planningChoices())
            assertTrue(db.assistant().planningTasks().isEmpty())
            store.setDraft(null)
            assertNull(db.assistant().planningDraft())
        } finally {
            db.close()
            app.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun legacyConversationMigratesAndDraftSurvivesRecreation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "assistant_conversation_test_" + UUID.randomUUID()
        val prefs = app.getSharedPreferences(prefsName, 0)
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val old = JSONArray().put(JSONObject().put("id", "legacy-1").put("role", "assistant")
            .put("text", "先看看顺路事项").put("created_at", 1000L)
            .put("route_history_id", "test-route-id"))
        prefs.edit().putString("lines", old.toString()).commit()
        val original = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db) }
        try {
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { original.ready }) delay(20) }
            assertFalse(prefs.contains("lines"))
            assertEquals("test-route-id", original.lines.single().routeHistoryId)
            withContext(Dispatchers.Main) {
                original.record("路线已算好")
                original.proposal = JSONObject().put("action", "plan_route")
                    .put("destination", "图书馆").put("mode", "cycling")
            }
            withTimeout(5_000) {
                while (db.assistant().draft() == null || db.assistant().recentMessages().size < 2) delay(20)
            }
            val reopened = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db) }
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { reopened.ready }) delay(20) }
            assertEquals("图书馆", reopened.proposal?.optString("destination"))
            assertEquals("cycling", reopened.proposal?.optString("mode"))
            assertTrue(reopened.lines.any { it.text == "路线已算好" })
            withContext(Dispatchers.Main) { reopened.routeEnded() }
            withTimeout(5_000) { while (db.assistant().draft() != null) delay(20) }
            val afterTrip = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db) }
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { afterTrip.ready }) delay(20) }
            assertNull(afterTrip.proposal)
            assertTrue(afterTrip.lines.any { it.text == "先看看顺路事项" })
            withContext(Dispatchers.Main) {
                reopened.viewModelScope.cancel()
                afterTrip.viewModelScope.cancel()
            }
        } finally {
            withContext(Dispatchers.Main) { original.viewModelScope.cancel() }
            db.close()
            app.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun expiredLegacyIntentSurvivesButHasNoExecutablePreview() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "assistant_conversation_test_" + UUID.randomUUID()
        val prefs = app.getSharedPreferences(prefsName, 0)
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        prefs.edit().putString("lines", JSONArray().put(JSONObject().put("id", "old-line")
            .put("role", "user").put("text", "去图书馆").put("created_at", 1000L)).toString())
            .putString("proposal", JSONObject().put("action", "plan_route")
                .put("destination", "图书馆").put("mode", "walking").toString())
            .putLong("proposal_saved_at", System.currentTimeMillis() - 31 * 60_000L).commit()
        val chat = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db) }
        try {
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { chat.ready }) delay(20) }
            assertEquals("图书馆", chat.proposal?.optString("destination"))
            assertTrue(chat.lines.any { it.text == "去图书馆" })
            assertNull(chat.automatic.preview)
            assertFalse(chat.automatic.canConfirm)
            assertNotNull(db.assistant().draft())
        } finally {
            withContext(Dispatchers.Main) { chat.viewModelScope.cancel() }
            db.close()
            app.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun corruptLegacyConversationDoesNotBlockValidDraftMigration() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "assistant_corrupt_legacy_test_" + UUID.randomUUID()
        val prefs = app.getSharedPreferences(prefsName, 0)
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        prefs.edit()
            .putString("lines", "{not an array")
            .putString("proposal", JSONObject().put("action", "create_task")
                .put("title", "Buy water").toString())
            .putString("proposal_saved_at", "invalid timestamp")
            .commit()
        try {
            val store = AssistantStore(db, prefs)
            val (lines, proposal) = store.load()
            assertTrue(lines.isEmpty())
            assertEquals("Buy water", proposal?.optString("title"))
            assertTrue(store.legacyWarning)
            assertEquals("{not an array", prefs.getString("lines", null))
            assertFalse(prefs.contains("proposal"))
            assertEquals(1, db.assistant().draft()?.id)
            val reopened = AssistantStore(db, prefs)
            assertEquals("Buy water", reopened.load().second?.optString("title"))
            assertTrue(reopened.legacyWarning)
        } finally {
            db.close()
            app.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun partiallyCorruptLegacyArrayKeepsRawDataWithoutDuplicatingValidMessage() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "assistant_partial_legacy_test_" + UUID.randomUUID()
        val prefs = app.getSharedPreferences(prefsName, 0)
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val raw = JSONArray().put(JSONObject().put("role", "user")
            .put("text", "A valid message").put("created_at", 1000L))
            .put(JSONObject().put("unexpected", true)).toString()
        prefs.edit().putString("lines", raw).commit()
        try {
            val first = AssistantStore(db, prefs)
            assertEquals(1, first.load().first.size)
            assertTrue(first.legacyWarning)
            assertEquals(raw, prefs.getString("lines", null))
            val second = AssistantStore(db, prefs)
            assertEquals(1, second.load().first.size)
            assertEquals(1, db.assistant().recentMessages().size)
            assertTrue(second.legacyWarning)
        } finally {
            db.close()
            app.deleteSharedPreferences(prefsName)
        }
    }
    @Test fun destinationQuestionRestoresCandidatesInDisplayedOrderWithoutExecutableRoute() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "assistant_candidates_" + UUID.randomUUID()
        val prefs = app.getSharedPreferences(prefsName, 0)
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AssistantViewModel? = null
        try {
            val store = AssistantStore(db, prefs)
            store.setDraft(JSONObject().put("action", "plan_route").put("destination", "Library")
                .put("mode", "cycling").put("_draft_id", "draft-1").put("_plan_id", "plan-1"))
            val first = Place("first", "First library", 120.0, 31.0)
            val second = Place("second", "Second library", 120.1, 31.1)
            val question = AssistantClarification(draftId = "draft-1", planId = "plan-1",
                kind = "DESTINATION", prompt = "请选择地点", destination = "Library", mode = "cycling", savedAt = 1000)
            // Persistence order differs from the order shown to the user.
            store.saveClarification(ClarificationState(question, listOf(
                second.toClarificationChoice("draft-1", 1), first.toClarificationChoice("draft-1", 0))))
            val reopened = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db) }.also { model = it }
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { reopened.ready }) delay(20) }
            withContext(Dispatchers.Main) {
                assertEquals("draft-1", reopened.proposal?.optString("_draft_id"))
                assertEquals("plan-1", reopened.proposal?.optString("_plan_id"))
                assertEquals(question, reopened.clarification?.question)
                assertEquals(listOf(first, second), reopened.automatic.choices)
                assertEquals(second, resolvePlaceReply("第二个", reopened.automatic.choices))
                assertNull(reopened.automatic.preview)
                assertFalse(reopened.automatic.canConfirm)
            }
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close(); app.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun lateQuestionCannotReplaceCurrentDraftOrAnotherPlan() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "assistant_late_question_" + UUID.randomUUID()
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        try {
            val store = AssistantStore(db, app.getSharedPreferences(prefsName, 0))
            val proposal = JSONObject().put("action", "plan_route").put("destination", "Library").put("mode", "cycling")
            store.setDraft(JSONObject(proposal.toString()).put("_draft_id", "old").put("_plan_id", "plan"))
            val old = store.clarification()!!
            store.setDraft(JSONObject(proposal.toString()).put("_draft_id", "new").put("_plan_id", "plan"))
            val current = store.clarification()!!
            store.saveClarification(old.copy(choices = listOf(Place("old-place", "Old", 120.0, 31.0).toClarificationChoice("old", 0))))
            assertEquals(current, store.clarification())
            store.saveClarification(current.copy(question = current.question.copy(planId = "different-plan")))
            assertEquals(current, store.clarification())
            assertTrue(db.assistant().placeChoices().isEmpty())
            store.setDraft(null)
            store.saveClarification(current)
            assertNull(store.clarification())
            assertNull(db.assistant().clarification())
            assertTrue(db.assistant().placeChoices().isEmpty())
        } finally { db.close(); app.deleteSharedPreferences(prefsName) }
    }

    @Test fun choosingTravelModeCreatesNewDraftButKeepsPlanAcrossRecreation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "assistant_mode_identity_" + UUID.randomUUID()
        val prefs = app.getSharedPreferences(prefsName, 0)
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val models = mutableListOf<AssistantViewModel>()
        try {
            val original = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db) }.also { models.add(it) }
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { original.ready }) delay(20) }
            withContext(Dispatchers.Main) {
                original.proposal = JSONObject().put("action", "plan_route").put("destination", "Library")
            }
            withTimeout(5_000) { while (db.assistant().clarification()?.kind != "MODE") delay(20) }
            val before = db.assistant().draft()!!
            withContext(Dispatchers.Main) { original.chooseMode("cycling") }
            withTimeout(5_000) { while (db.assistant().draft()?.mode != "cycling") delay(20) }
            val after = db.assistant().draft()!!
            assertNotEquals(before.draftId, after.draftId)
            assertEquals(before.planId, after.planId)
            assertEquals("CONFIRM_ROUTE", db.assistant().clarification()?.kind)
            val reopened = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db) }.also { models.add(it) }
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { reopened.ready }) delay(20) }
            assertEquals(after.draftId, reopened.proposal?.optString("_draft_id"))
            assertEquals(before.planId, reopened.proposal?.optString("_plan_id"))
            assertEquals("cycling", reopened.proposal?.optString("mode"))
            withContext(Dispatchers.Main) { reopened.chooseMode("unsupported") }
            assertEquals(after.draftId, reopened.proposal?.optString("_draft_id"))
        } finally {
            withContext(Dispatchers.Main) { models.forEach { it.viewModelScope.cancel() } }
            db.close(); app.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun taskClarificationDistinguishesFixedPlaceFromConfirmation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val prefsName = "assistant_task_question_" + UUID.randomUUID()
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        try {
            val store = AssistantStore(db, app.getSharedPreferences(prefsName, 0))
            store.setDraft(JSONObject().put("action", "create_task").put("title", "取快递").put("place_type", "PARCEL"))
            assertEquals("TASK_PLACE", store.clarification()?.question?.kind)
            store.setDraft(JSONObject().put("action", "create_task").put("title", "买水").put("place_type", "SUPERMARKET"))
            assertEquals("CONFIRM_TASK", store.clarification()?.question?.kind)
        } finally { db.close(); app.deleteSharedPreferences(prefsName) }
    }

}
