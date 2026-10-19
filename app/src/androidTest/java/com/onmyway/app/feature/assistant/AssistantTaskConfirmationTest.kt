package com.onmyway.app.feature.assistant

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.AgentEvent
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import com.onmyway.app.feature.planning.PlanningDraft
import com.onmyway.app.feature.planning.PlanningDraftTask
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantTaskConfirmationTest {
    private lateinit var app: Application
    private lateinit var isolatedApp: Application
    private lateinit var databaseName: String
    private lateinit var prefs: SharedPreferences
    private lateinit var db: TaskDatabase
    private lateinit var store: AssistantStore
    private val preferenceNames = mutableSetOf<String>()

    @Before fun setUp() {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        databaseName = "assistant_task_confirmation_" + UUID.randomUUID()
        isolatedApp = object : Application() {
            init { attachBaseContext(app) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$databaseName-$name"
                preferenceNames += isolatedName
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
        prefs = isolatedApp.getSharedPreferences("assistant_conversation", 0)
        openDatabase()
    }

    @After fun tearDown() {
        db.close()
        app.deleteDatabase(databaseName)
        app.deleteDatabase("$databaseName-chat-journal")
        app.deleteDatabase("$databaseName-navigation-journal")
        preferenceNames.forEach(app::deleteSharedPreferences)
    }

    private fun openDatabase() {
        db = Room.databaseBuilder(app, TaskDatabase::class.java, databaseName).build()
        store = AssistantStore(db, prefs)
    }

    private fun proposal(draftId: String, title: String = "Print thesis") = JSONObject()
        .put("action", "create_task").put("title", title).put("place_type", "PRINT_SHOP")
        .put("_draft_id", draftId).put("_plan_id", draftId)

    private fun batchProposal(draftId: String, reminderAt: Long) = JSONObject()
        .put("action", "create_tasks").put("_draft_id", draftId).put("_plan_id", "plan-$draftId")
        .put("tasks", org.json.JSONArray().put(JSONObject().put("item_id", "print").put("version", 1)
            .put("title", "Print thesis").put("place_type", "PRINT_SHOP")
            .put("planned_date", "").put("deadline", "")
            .put("reminder_at", reminderAtIso(reminderAt))
            .put("priority", 2).put("service_minutes", 5)
            .put("place", Place("print-shop", "Print shop", 120.0, 31.0).json())))

    private suspend fun confirm(draftId: String, task: Errand = Errand(title = "Print thesis")): Boolean =
        store.confirmTask(draftId, task, "Task saved") { saved ->
            db.agentEvents().append(AgentEvent(type = "ACTION_CREATED", source = "ASSISTANT",
                actionId = saved.id, correlationId = draftId))
        }

    /** Include obsolete auxiliary rows so transaction tests cover every consumed table. */
    private suspend fun seedPending(draftId: String) {
        store.setDraft(proposal(draftId))
        db.assistant().savePlanningDraft(PlanningDraft(draftId = draftId, planId = draftId,
            destination = "Library", mode = "walking", budget = 10, placeId = "library",
            placeName = "Library", longitude = 120.0, latitude = 31.0, address = "Campus", savedAt = 1_000L))
        db.assistant().savePlanningTasks(listOf(PlanningDraftTask("water", true)))
        db.assistant().savePlaceChoices(listOf(
            Place("print-shop", "Print shop", 120.0, 31.0).toClarificationChoice(draftId, 0)))
    }

    private suspend fun assertConsumed() {
        assertNull(db.assistant().draft())
        assertNull(db.assistant().clarification())
        assertNull(db.assistant().planningDraft())
        assertTrue(db.assistant().planningTasks().isEmpty())
        assertTrue(db.assistant().placeChoices().isEmpty())
    }

    private suspend fun assertNoConfirmation() {
        assertTrue(db.tasks().observe().first().isEmpty())
        assertTrue(db.agentEvents().recent().isEmpty())
        assertTrue(db.assistant().recentMessages().isEmpty())
    }

    @Test fun confirmationCommitsOnceAndSurvivesDatabaseReopen() = runBlocking {
        val draftId = UUID.randomUUID().toString()
        seedPending(draftId)
        val edited = Errand(id = "editor-transient-id", title = "Print final thesis", priority = 1,
            serviceMinutes = 12, placeType = "PRINT_SHOP", status = "done", createdAt = 1,
            completedAt = 2, lastRemindedAt = 3, reminderCount = 4, reminderPending = true)
        assertTrue(confirm(draftId, edited))
        val saved = db.tasks().observe().first().single()
        assertNotEquals(edited.id, saved.id)
        assertEquals(edited.title, saved.title)
        assertEquals(edited.priority, saved.priority)
        assertEquals(edited.serviceMinutes, saved.serviceMinutes)
        assertEquals("pending", saved.status)
        assertEquals(0L, saved.completedAt)
        assertEquals(0L, saved.lastRemindedAt)
        assertEquals(0, saved.reminderCount)
        assertFalse(saved.reminderPending)
        assertConsumed()

        db.close()
        openDatabase()
        assertFalse(confirm(draftId, edited.copy(id = "another-transient-id", title = "Duplicate")))
        assertEquals(listOf(saved), db.tasks().observe().first())
        val event = db.agentEvents().recent().single()
        assertEquals("ACTION_CREATED", event.type)
        assertEquals("ASSISTANT", event.source)
        assertEquals(saved.id, event.actionId)
        assertEquals(draftId, event.correlationId)
        val receipt = db.assistant().recentMessages().single()
        assertEquals("assistant-task-confirmed-$draftId", receipt.id)
        assertEquals("Task saved", receipt.text)
        assertEquals("assistant", receipt.role)
        assertConsumed()
        assertNull(store.load().second)
    }

    @Test fun replayAfterTaskDeletionCannotRecreateTaskOrConsumeReplacementDraft() = runBlocking {
        val confirmedId = "confirmed-draft"
        seedPending(confirmedId)
        assertTrue(confirm(confirmedId))
        db.tasks().delete(db.tasks().observe().first().single())
        seedPending("replacement-draft")
        val draft = db.assistant().draft()
        val question = db.assistant().clarification()
        val planning = db.assistant().planningDraft()
        val choices = db.assistant().placeChoices()
        val tasks = db.assistant().planningTasks()

        assertFalse(confirm(confirmedId))
        assertTrue(db.tasks().observe().first().isEmpty())
        assertEquals(draft, db.assistant().draft())
        assertEquals(question, db.assistant().clarification())
        assertEquals(planning, db.assistant().planningDraft())
        assertEquals(choices, db.assistant().placeChoices())
        assertEquals(tasks, db.assistant().planningTasks())
        assertEquals(1, db.agentEvents().recent().size)
        assertEquals(1, db.assistant().recentMessages().size)
    }

    @Test fun missingStaleAndNonTaskDraftsRejectConfirmationWithoutWrites() = runBlocking {
        seedPending("current-draft")
        val before = db.assistant().draft()
        for (expected in listOf("", "previous-draft")) {
            assertTrue(runCatching { confirm(expected) }.isFailure)
            assertEquals(before, db.assistant().draft())
            assertNoConfirmation()
        }
        store.setDraft(null)
        assertTrue(runCatching { confirm("current-draft") }.isFailure)
        assertNoConfirmation()

        store.setDraft(JSONObject().put("action", "plan_route").put("destination", "Library")
            .put("mode", "walking").put("_draft_id", "route-draft"))
        val route = db.assistant().draft()
        assertTrue(runCatching { confirm("route-draft") }.isFailure)
        assertEquals(route, db.assistant().draft())
        assertNoConfirmation()
    }

    @Test fun traceAndReceiptFailuresRollBackTaskAndAllConsumedState() = runBlocking {
        val draftId = "rollback-draft"
        seedPending(draftId)
        val draft = db.assistant().draft()
        val question = db.assistant().clarification()
        val planning = db.assistant().planningDraft()
        val choices = db.assistant().placeChoices()
        val tasks = db.assistant().planningTasks()

        val traceFailure = runCatching {
            store.confirmTask(draftId, Errand(title = "Print thesis"), "Task saved") { saved ->
                db.agentEvents().append(AgentEvent(type = "ACTION_CREATED", source = "ASSISTANT", actionId = saved.id))
                error("Injected trace failure")
            }
        }
        assertTrue(traceFailure.isFailure)
        assertNoConfirmation()
        assertEquals(draft, db.assistant().draft())

        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_task_receipt BEFORE INSERT ON assistant_messages " +
                "WHEN NEW.id LIKE 'assistant-task-confirmed-%' " +
                "BEGIN SELECT RAISE(ABORT, 'Injected receipt failure'); END",
        )
        assertTrue(runCatching { confirm(draftId) }.isFailure)
        assertNoConfirmation()
        assertEquals(draft, db.assistant().draft())
        assertEquals(question, db.assistant().clarification())
        assertEquals(planning, db.assistant().planningDraft())
        assertEquals(choices, db.assistant().placeChoices())
        assertEquals(tasks, db.assistant().planningTasks())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_task_receipt")
        assertTrue(confirm(draftId))
        assertEquals(1, db.tasks().observe().first().size)
        assertEquals(1, db.agentEvents().recent().size)
        assertEquals(1, db.assistant().recentMessages().size)
        assertConsumed()
    }

    private suspend fun withModels(
        scheduler: ((Errand) -> Unit)? = null,
        block: suspend (AppViewModel, AssistantViewModel) -> Unit,
    ) {
        val delivery = NavigationRequestDelivery(app, "$databaseName-navigation-journal")
        val vm = withContext(Dispatchers.Main) {
            if (scheduler == null) AppViewModel(isolatedApp, db, delivery)
            else AppViewModel(isolatedApp, db, delivery, reminderScheduler = scheduler)
        }
        val chat = withContext(Dispatchers.Main) {
            AssistantViewModel(isolatedApp, prefs, db, requestJournalName = "$databaseName-chat-journal")
        }
        try {
            withTimeout(5_000) {
                while (!withContext(Dispatchers.Main) { chat.ready && !vm.restoringTrip }) delay(20)
            }
            block(vm, chat)
        } finally {
            withContext(Dispatchers.Main) {
                chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
            delivery.close()
        }
    }

    @Test fun editorUsesCapturedDraftIdAndSuccessfulSaveRefreshesConversation() = runBlocking {
        withModels { vm, chat ->
            var currentId = ""
            withContext(Dispatchers.Main) {
                chat.proposal = proposal("ignored-old", "Old task")
                val editorDraftId = chat.proposal!!.getString("_draft_id")
                val editorTask = Errand(title = "Old task edited")
                chat.proposal = proposal("ignored-new", "New task")
                currentId = chat.proposal!!.getString("_draft_id")
                assertNotEquals(editorDraftId, currentId)
                assertFalse(chat.saveTask(vm, editorTask, editorDraftId))
                assertFalse(chat.busy)
                assertTrue(chat.error.contains("已变化"))
                assertEquals(currentId, chat.proposal!!.getString("_draft_id"))
            }
            withTimeout(5_000) { while (db.assistant().draft()?.draftId != currentId) delay(20) }
            assertNoConfirmation()
            withContext(Dispatchers.Main) {
                assertTrue(chat.saveTask(vm, Errand(title = "New task edited"), currentId))
                assertFalse(chat.saveTask(vm, Errand(title = "Duplicate tap"), currentId))
            }
            withTimeout(5_000) { while (withContext(Dispatchers.Main) { chat.busy }) delay(20) }
            withContext(Dispatchers.Main) {
                assertEquals("", chat.error)
                assertNull(chat.proposal)
                assertNull(chat.clarification)
                assertTrue(chat.lines.single().text.contains("New task edited"))
            }
            assertEquals("New task edited", db.tasks().observe().first().single().title)
            assertConsumed()
        }
    }

    @Test fun proposalReplacedWhileSavingIsNotClearedByLateUiRefresh() = runBlocking {
        withModels { vm, chat ->
            var replacementId = ""
            withContext(Dispatchers.Main) {
                chat.proposal = proposal("ignored", "First task")
                val confirmedId = chat.proposal!!.getString("_draft_id")
                assertTrue(chat.saveTask(vm, Errand(title = "First task"), confirmedId))
                chat.proposal = proposal("ignored", "Replacement task")
                replacementId = chat.proposal!!.getString("_draft_id")
            }
            withTimeout(5_000) {
                while (withContext(Dispatchers.Main) { chat.busy } || db.assistant().draft()?.draftId != replacementId) delay(20)
            }
            withContext(Dispatchers.Main) {
                assertEquals("", chat.error)
                assertEquals(replacementId, chat.proposal!!.getString("_draft_id"))
            }
            assertEquals("Replacement task", db.assistant().draft()?.title)
            assertEquals(replacementId, db.assistant().clarification()?.draftId)
            assertEquals("First task", db.tasks().observe().first().single().title)
            assertEquals(1, db.assistant().recentMessages().size)
        }
    }

    @Test fun taskConfirmationRespectsLearningSettingWithoutSuppressingReceipt() = runBlocking {
        withModels { vm, _ ->
            store.setDraft(proposal("learning-disabled"))
            withContext(Dispatchers.Main) {
                vm.updateLearningEnabled(false)
                assertTrue(vm.saveAssistantTask(Errand(title = "Private task"), "learning-disabled"))
            }
            assertTrue(db.agentEvents().recent().isEmpty())
            assertEquals(1, db.tasks().observe().first().size)
            assertEquals(1, db.assistant().recentMessages().size)

            store.setDraft(proposal("learning-enabled"))
            withContext(Dispatchers.Main) {
                vm.updateLearningEnabled(true)
                assertTrue(vm.saveAssistantTask(Errand(title = "Learned task"), "learning-enabled"))
            }
            val event = db.agentEvents().recent().single()
            assertEquals("ACTION_CREATED", event.type)
            assertEquals("ASSISTANT", event.source)
            assertEquals("learning-enabled", event.correlationId)
            assertEquals(db.tasks().observe().first().single { it.title == "Learned task" }.id, event.actionId)
            assertEquals(2, db.assistant().recentMessages().size)
            assertConsumed()
        }
    }

    @Test fun modelRemindersArePersistedAndScheduledAfterSingleAndBatchConfirmation() = runBlocking {
        val reminderAt = System.currentTimeMillis() + 3_600_000
        val scheduled = mutableListOf<Errand>()
        withModels(scheduler = { scheduled += it }) { vm, _ ->
            store.setDraft(proposal("single-reminder", "Single reminder"))
            withContext(Dispatchers.Main) {
                assertTrue(vm.saveAssistantTask(Errand(title = "Single reminder", placeType = "PRINT_SHOP",
                    placeJson = Place("print-shop", "Print shop", 120.0, 31.0).json().toString(),
                    reminderAt = reminderAt), "single-reminder"))
            }
            assertEquals(reminderAt, db.tasks().observe().first().single().reminderAt)

            store.setDraft(batchProposal("batch-reminder", reminderAt))
            withContext(Dispatchers.Main) {
                assertTrue(vm.saveAssistantTasks("batch-reminder", mapOf("print" to 1)))
            }
            assertEquals(reminderAt,
                db.tasks().observe().first().single { it.title == "Print thesis" }.reminderAt)
        }
        assertEquals(2, scheduled.size)
        assertTrue(scheduled.all { it.reminderAt == reminderAt })
    }
}
