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
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.remote.AssistantRequestTransport
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardActionBoundaryTest {
    private lateinit var app: Application
    private lateinit var isolatedApp: Application
    private lateinit var databaseName: String
    private lateinit var prefs: SharedPreferences
    private lateinit var db: TaskDatabase
    private lateinit var chat: AssistantViewModel
    private lateinit var taskViewModel: AppViewModel
    private lateinit var navigationDelivery: NavigationRequestDelivery
    private val preferenceNames = mutableSetOf<String>()
    private val submittedRequests = CopyOnWriteArrayList<String>()
    private val transport = object : AssistantRequestTransport {
        override suspend fun submit(server: String, body: JSONObject): JSONObject {
            submittedRequests += body.toString()
            throw IOException("Injected interrupted response after request capture")
        }
        override suspend fun status(server: String, body: JSONObject): JSONObject = error("Unexpected recovery")
    }

    @Before fun setUp() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        databaseName = "card-action-boundary-${UUID.randomUUID()}"
        isolatedApp = object : Application() {
            init { attachBaseContext(app) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$databaseName-$name"
                preferenceNames += isolatedName
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
        prefs = isolatedApp.getSharedPreferences("assistant_conversation", 0)
        db = Room.databaseBuilder(app, TaskDatabase::class.java, databaseName).build()
        chat = withContext(Dispatchers.Main) {
            AssistantViewModel(isolatedApp, prefs, db, requestTransport = transport,
                requestJournalName = "$databaseName-requests")
        }
        withTimeout(5_000) {
            while (!withContext(Dispatchers.Main) { chat.ready }) delay(20)
        }
    }

    @After fun tearDown() = runBlocking {
        if (::chat.isInitialized) withContext(Dispatchers.Main) {
            chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::taskViewModel.isInitialized) withContext(Dispatchers.Main) {
            taskViewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::navigationDelivery.isInitialized) navigationDelivery.close()
        if (::db.isInitialized) db.close()
        if (::databaseName.isInitialized) {
            app.deleteDatabase(databaseName)
            app.deleteDatabase("$databaseName-requests")
            app.deleteDatabase("$databaseName-navigation")
        }
        preferenceNames.forEach(app::deleteSharedPreferences)
    }

    private fun route() = JSONObject().put("action", "plan_route")
        .put("destination", "测试宿舍").put("mode", "walking")

    private suspend fun openTaskViewModel() {
        navigationDelivery = NavigationRequestDelivery(isolatedApp, "$databaseName-navigation")
        taskViewModel = withContext(Dispatchers.Main) {
            AppViewModel(isolatedApp, db, navigationDelivery = navigationDelivery)
        }
        withTimeout(5_000) {
            while (withContext(Dispatchers.Main) { taskViewModel.restoringTrip }) delay(20)
        }
    }

    private suspend fun show(proposal: JSONObject): String {
        val id = withContext(Dispatchers.Main) {
            chat.proposal = proposal
            chat.proposal!!.getString("_draft_id")
        }
        withTimeout(5_000) { while (db.assistant().draft()?.draftId != id) delay(20) }
        return id
    }

    @Test fun routeModeActionCannotMutateCurrentSingleTaskDraft() = runBlocking {
        show(route())
        val id = show(JSONObject().put("action", "create_task")
            .put("title", "打印论文").put("place_type", "PRINT_SHOP"))
        val before = db.assistant().draft()
        withContext(Dispatchers.Main) {
            chat.chooseMode("cycling")
            assertEquals(id, chat.proposal!!.getString("_draft_id"))
            assertEquals("create_task", chat.proposal!!.getString("action"))
        }
        assertEquals(before, db.assistant().draft())
    }

    @Test fun routeModeActionCannotMutateCurrentMultiTaskDraft() = runBlocking {
        show(route())
        val id = show(JSONObject().put("action", "create_tasks").put("tasks", JSONArray()
            .put(JSONObject().put("item_id", "print").put("version", 1)
                .put("title", "打印论文").put("place_type", "PRINT_SHOP")
                .put("planned_date", "2026-10-19").put("deadline", "").put("priority", 3))))
        val before = db.assistant().draft()
        val beforeItems = db.assistant().draftItems()
        withContext(Dispatchers.Main) {
            chat.chooseMode("cycling")
            assertEquals(id, chat.proposal!!.getString("_draft_id"))
            assertEquals("create_tasks", chat.proposal!!.getString("action"))
        }
        assertEquals(before, db.assistant().draft())
        assertEquals(beforeItems, db.assistant().draftItems())
    }

    @Test fun currentRouteModeActionStillCreatesNewRevision() = runBlocking {
        val initialId = show(route())
        val changedId = withContext(Dispatchers.Main) {
            chat.chooseMode("cycling")
            assertEquals("cycling", chat.proposal!!.getString("mode"))
            chat.proposal!!.getString("_draft_id")
        }
        assertNotEquals(initialId, changedId)
        withTimeout(5_000) { while (db.assistant().draft()?.draftId != changedId) delay(20) }
        assertEquals("cycling", db.assistant().draft()!!.mode)
    }

    @Test fun capturedOldRouteModeCannotChangeNewerRoute() = runBlocking {
        val oldId = show(route())
        val currentId = show(route().put("destination", "测试新宿舍"))
        val before = db.assistant().draft()
        withContext(Dispatchers.Main) {
            chat.chooseMode("cycling", oldId)
            assertEquals(currentId, chat.proposal!!.getString("_draft_id"))
            assertEquals("walking", chat.proposal!!.getString("mode"))
        }
        assertEquals(before, db.assistant().draft())
    }

    @Test fun staleCancelTokensCannotConsumeCurrentDraft() = runBlocking {
        val oldId = show(route())
        val oldPlan = withContext(Dispatchers.Main) { chat.proposal!!.getString("_plan_id") }
        val currentId = show(JSONObject().put("action", "create_task")
            .put("title", "打印论文").put("place_type", "PRINT_SHOP"))
        val currentPlan = withContext(Dispatchers.Main) { chat.proposal!!.getString("_plan_id") }
        val before = db.assistant().draft()
        withContext(Dispatchers.Main) {
            assertFalse(chat.dismissDraft(oldId, oldPlan))
            assertFalse(chat.dismissDraft(currentId, "another-plan"))
            assertEquals(currentId, chat.proposal!!.getString("_draft_id"))
            assertEquals(currentPlan, chat.proposal!!.getString("_plan_id"))
        }
        assertEquals(before, db.assistant().draft())
    }

    @Test fun databaseReplacementRejectsCancelWithoutFalseSuccessMessage() = runBlocking {
        val oldId = show(route())
        val oldPlan = withContext(Dispatchers.Main) { chat.proposal!!.getString("_plan_id") }
        AssistantStore(db, prefs).setDraft(route().put("_draft_id", "new-route")
            .put("_plan_id", "new-plan").put("destination", "测试新宿舍"))
        withContext(Dispatchers.Main) { assertTrue(chat.dismissDraft(oldId, oldPlan)) }
        withTimeout(5_000) { while (withContext(Dispatchers.Main) { chat.busy }) delay(20) }
        assertEquals("new-route", db.assistant().draft()!!.draftId)
        assertEquals("new-plan", db.assistant().draft()!!.planId)
        withContext(Dispatchers.Main) {
            assertEquals("new-route", chat.proposal!!.getString("_draft_id"))
            assertTrue(chat.lines.none { "已取消" in it.text })
        }
        assertTrue(db.assistant().recentMessages().none { "已取消" in it.text })
    }

    @Test fun currentCancelKeepsCompletedParcelAndScheduledPrintUntouched() = runBlocking {
        val parcel = Errand(id = "parcel", title = "取快递", status = "done", completedAt = 1_000)
        val print = Errand(id = "print", title = "打印论文", plannedDate = "2026-10-19",
            deadline = "2026-10-20T18:00:00+08:00", reminderAt = System.currentTimeMillis() + 3_600_000)
        db.tasks().save(parcel)
        db.tasks().save(print)
        val id = show(route())
        val plan = withContext(Dispatchers.Main) { chat.proposal!!.getString("_plan_id") }
        withContext(Dispatchers.Main) { assertTrue(chat.dismissDraft(id, plan)) }
        withTimeout(5_000) { while (withContext(Dispatchers.Main) { chat.busy }) delay(20) }
        assertNull(db.assistant().draft())
        assertNull(db.assistant().clarification())
        assertTrue(db.assistant().draftItems().isEmpty())
        assertEquals(parcel, db.tasks().get("parcel"))
        assertEquals(print, db.tasks().get("print"))
        withContext(Dispatchers.Main) {
            assertNull(chat.proposal)
            assertTrue(chat.lines.any { "已取消待确认建议" in it.text })
        }
    }

    @Test fun storeCancelChecksDraftAndPlanBeforeClearingAuxiliaryRows() = runBlocking {
        val id = show(route())
        val current = db.assistant().draft()!!
        val beforeQuestion = db.assistant().clarification()
        val store = AssistantStore(db, prefs)
        assertFalse(store.cancelDraft("older-draft", current.planId))
        assertFalse(store.cancelDraft(id, "older-plan"))
        assertFalse(store.cancelDraft("", current.planId))
        assertEquals(current, db.assistant().draft())
        assertEquals(beforeQuestion, db.assistant().clarification())
        assertTrue(store.cancelDraft(id, current.planId))
        assertNull(db.assistant().draft())
        assertNull(db.assistant().clarification())
        assertFalse(store.cancelDraft(id, current.planId))
    }

    @Test fun unsupportedVersionCannotReplaceOrOperateCurrentDraft() = runBlocking {
        val id = show(route())
        val before = db.assistant().draft()
        withContext(Dispatchers.Main) {
            chat.proposal = JSONObject().put("action", "create_task").put("title", "不支持的卡片")
                .put("place_type", "PRINT_SHOP").put("_card_version", 99)
            assertEquals(id, chat.proposal!!.getString("_draft_id"))
            val plan = chat.proposal!!.getString("_plan_id")
            chat.proposal!!.put("_card_version", 99)
            chat.chooseMode("cycling", id)
            assertEquals("walking", chat.proposal!!.getString("mode"))
            assertEquals(id, chat.proposal!!.getString("_draft_id"))
            assertFalse(chat.dismissDraft(id, plan))
        }
        assertEquals(before, db.assistant().draft())
    }

    @Test fun expiredRouteCannotUseSaveOrModeShortcuts() = runBlocking {
        openTaskViewModel()
        val id = show(route())
        withContext(Dispatchers.Main) {
            chat.proposal!!.put("_expires_at", 1L)
            listOf("保存", "确认加入", "按所选规划", "骑车", "第二个").forEach { command ->
                chat.input = command
                chat.send(taskViewModel)
                assertEquals(id, chat.proposal!!.getString("_draft_id"))
                assertFalse(chat.busy)
            }
        }
        assertTrue(submittedRequests.isEmpty())
        assertEquals(id, db.assistant().draft()!!.draftId)
    }

    @Test fun newRequestAfterRouteExpiryOmitsOldDraftAndPlaceChoiceContext() = runBlocking {
        openTaskViewModel()
        val id = show(route())
        val plan = withContext(Dispatchers.Main) { chat.proposal!!.getString("_plan_id") }
        withContext(Dispatchers.Main) {
            chat.automatic.restoreQuestion(ClarificationState(
                AssistantClarification(draftId = id, planId = plan, kind = "DESTINATION",
                    prompt = "只属于过期建议的地点追问", destination = "测试旧宿舍", mode = "walking", savedAt = 1),
                listOf(Place("old-place", "测试旧宿舍", 120.0, 31.0).toClarificationChoice(id, 0))))
            chat.proposal!!.put("_expires_at", 1L)
            chat.input = "重新规划去宿舍"
            chat.send(taskViewModel)
        }
        withTimeout(5_000) {
            while (submittedRequests.isEmpty() || withContext(Dispatchers.Main) { chat.busy }) delay(20)
        }
        val body = JSONObject(submittedRequests.single())
        assertFalse(body.has("draft_items"))
        val messages = body.getJSONArray("messages")
        assertEquals("重新规划去宿舍", messages.getJSONObject(messages.length() - 1).getString("content"))
        assertFalse(messages.toString().contains("只属于过期建议的地点追问"))
        assertFalse(messages.toString().contains("当前有未确认的路线草稿"))
        withContext(Dispatchers.Main) {
            assertEquals(id, JSONObject(chat.pendingRequest!!.contextKey).getString("draft_id"))
            assertFalse(chat.automatic.busy)
            assertFalse(chat.automatic.committing)
            assertNull(chat.automatic.preview)
        }
        assertEquals(id, db.assistant().draft()!!.draftId)
    }
}
