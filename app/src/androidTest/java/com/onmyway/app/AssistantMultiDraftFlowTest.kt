package com.onmyway.app

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AssistantMultiDraftFlowTest {
    private lateinit var app: Application
    private lateinit var isolated: Application
    private lateinit var db: TaskDatabase
    private lateinit var vm: AppViewModel
    private lateinit var delivery: NavigationRequestDelivery
    private val name = "multi-draft-${UUID.randomUUID()}"
    private val preferences = mutableSetOf<String>()
    private val chats = mutableListOf<AssistantViewModel>()
    private val submissions = CopyOnWriteArrayList<String>()
    private var nextReply = JSONObject()
    private val transport = object : AssistantRequestTransport {
        override suspend fun submit(server: String, body: JSONObject): JSONObject {
            submissions.add(body.toString())
            return JSONObject(nextReply.toString())
        }
        override suspend fun status(server: String, body: JSONObject): JSONObject = error("Unexpected recovery")
    }

    @Before fun setup() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        isolated = object : Application() {
            init { attachBaseContext(app) }
            override fun getSharedPreferences(key: String, mode: Int): android.content.SharedPreferences {
                val unique = "$name-$key"
                preferences += unique
                return app.getSharedPreferences(unique, mode)
            }
        }
        db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        delivery = NavigationRequestDelivery(app, "$name-navigation.db")
        vm = withContext(Dispatchers.Main) { AppViewModel(isolated, db, delivery) }
        waitFor { !vm.restoringTrip }
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            chats.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        delivery.close(); db.close()
        app.deleteDatabase("$name-navigation.db"); app.deleteDatabase("$name-chat.db")
        preferences.forEach(app::deleteSharedPreferences)
    }

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(8_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(20) }
    }

    private suspend fun openChat(): AssistantViewModel {
        val chat = withContext(Dispatchers.Main) {
            AssistantViewModel(isolated, isolated.getSharedPreferences("assistant_conversation", 0), db, transport, "$name-chat.db")
        }.also(chats::add)
        waitFor { chat.ready }
        return chat
    }

    private suspend fun send(chat: AssistantViewModel, text: String) {
        withContext(Dispatchers.Main) { chat.input = text; chat.send(vm) }
        waitFor { !chat.busy }
    }

    private fun initial() = JSONObject().put("action", "create_tasks").put("tasks", JSONArray()
        .put(JSONObject().put("title", "取快递").put("place_type", "PARCEL").put("planned_date", "2026-10-15"))
        .put(JSONObject().put("title", "打印论文").put("place_type", "PRINT_SHOP").put("planned_date", "2026-10-16")))

    @Test fun mainSceneCorrectPrintConfirmOnlyPrintReopenAndSaveParcel() = runBlocking {
        var chat = openChat()
        nextReply = initial()
        send(chat, "今天取快递，明天打印论文")
        assertEquals("", chat.error)
        assertTrue(db.tasks().observe().first().isEmpty())
        val before = taskDraftItems(chat.proposal)
        val parcelBefore = before[0].toString()
        val printId = before[1].getString("item_id")
        val oldDraftId = chat.proposal!!.getString("_draft_id")
        nextReply = JSONObject().put("action", "update_task_drafts").put("updates", JSONArray().put(JSONObject()
            .put("item_id", printId).put("expected_version", 1).put("planned_date", "2026-10-17").put("priority", 3)))
        send(chat, "只把打印改到后天，优先级设为重要")
        assertEquals("", chat.error)
        val updated = taskDraftItems(chat.proposal)
        assertEquals(parcelBefore, updated[0].toString())
        assertEquals(printId, updated[1].getString("item_id"))
        assertEquals(2, updated[1].getInt("version"))
        assertEquals("", updated[1].getString("deadline"))
        assertEquals(2, JSONObject(submissions.last()).getJSONArray("draft_items").length())
        withContext(Dispatchers.Main) {
            assertFalse(chat.saveTasks(vm, oldDraftId, mapOf(printId to 1)))
        }
        send(chat, "只保存打印")
        assertEquals("", chat.error)
        assertEquals(2, submissions.size) // Confirmation is a local command, never another model request.
        val savedPrint = db.tasks().observe().first().single()
        assertEquals("打印论文", savedPrint.title)
        assertEquals("2026-10-17", savedPrint.plannedDate)
        assertEquals("", savedPrint.deadline)
        assertEquals(3, savedPrint.priority)
        assertEquals(parcelBefore, taskDraftItems(chat.proposal).single().toString())
        withContext(Dispatchers.Main) { chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        chat = openChat()
        send(chat, "保存")
        assertTrue(chat.error.isNotBlank()) // Parcel cannot be saved at an invented pickup point.
        assertEquals(1, db.tasks().observe().first().size)
        val parcel = taskDraftItems(chat.proposal).single()
        val edit = TaskDraftEdit(taskDraftErrand(parcel), chat.proposal!!.getString("_draft_id"), parcel.getString("item_id"), parcel.getInt("version"))
        withContext(Dispatchers.Main) {
            assertTrue(chat.editTaskDraft(vm, edit, edit.task.copy(placeJson = Place("test-parcel", "测试快递站", 120.0, 31.0).json().toString())))
        }
        waitFor { !chat.busy }
        assertEquals("", chat.error)
        assertEquals(1, db.tasks().observe().first().size) // Edit is not confirmation.
        send(chat, "保存")
        assertEquals("", chat.error)
        assertNull(chat.proposal)
        assertEquals(2, db.tasks().observe().first().size)
        assertEquals(2, db.agentEvents().recent().count { it.type == "ACTION_CREATED" })
    }

    @Test fun cancellationAfterPartialConfirmationKeepsAcceptedTask() = runBlocking {
        val chat = openChat()
        nextReply = initial()
        send(chat, "取快递和打印论文")
        send(chat, "保存第二项")
        send(chat, "取消")
        withTimeout(5_000) { while (db.assistant().draft() != null) delay(20) }
        assertEquals("打印论文", db.tasks().observe().first().single().title)
        assertTrue(db.assistant().draftItems().isEmpty())
        assertEquals(1, submissions.size)
    }

    private suspend fun assertHistoryRepeatPreservesDraft(chat: AssistantViewModel) {
        val proposalBefore = canonicalJson(withContext(Dispatchers.Main) { chat.proposal })
        val draftBefore = db.assistant().draft()
        val itemsBefore = db.assistant().draftItems()
        val tasksBefore = db.tasks().observe().first()
        val messagesBefore = db.assistant().recentMessages()
        val history = RouteHistory(sessionId = "previous-trip", state = "ARRIVED", timestamp = 1L,
            requestJson = JSONObject().put("destination", Place("dorm", "宿舍", 120.0, 31.0).json())
                .put("mode", "walking").toString(), resultJson = "{}")
        withContext(Dispatchers.Main) {
            assertFalse(chat.repeatRoute(history, vm))
            assertEquals("请先保存或取消当前待办草稿，再从历史记录重新规划。", chat.error)
            assertEquals(chat.error, vm.message) // Settings history also needs the global snackbar feedback.
            assertEquals(proposalBefore, canonicalJson(chat.proposal))
            assertNull(chat.historyDestination)
        }
        assertEquals(draftBefore, db.assistant().draft())
        assertEquals(itemsBefore, db.assistant().draftItems()) // Includes stable IDs, versions and all editable fields.
        assertEquals(tasksBefore, db.tasks().observe().first())
        assertEquals(messagesBefore, db.assistant().recentMessages())
        withContext(Dispatchers.Main) { chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        val reopened = openChat()
        assertEquals(proposalBefore, canonicalJson(withContext(Dispatchers.Main) { reopened.proposal }))
        assertEquals(itemsBefore, db.assistant().draftItems())
        assertEquals(tasksBefore, db.tasks().observe().first())
    }

    @Test fun historyRepeatPreservesBothUnconfirmedDrafts() = runBlocking {
        val chat = openChat()
        nextReply = initial()
        send(chat, "今天取快递，明天打印论文")
        assertEquals("", chat.error)
        assertEquals(2, db.assistant().draftItems().size)
        assertTrue(db.tasks().observe().first().isEmpty())
        assertHistoryRepeatPreservesDraft(chat)
    }

    @Test fun historyRepeatAfterPartialConfirmationPreservesRemainingDraftAndSavedTask() = runBlocking {
        val chat = openChat()
        nextReply = initial()
        send(chat, "今天取快递，明天打印论文")
        send(chat, "保存第二项")
        val parcel = taskDraftItems(chat.proposal).single()
        val edit = TaskDraftEdit(taskDraftErrand(parcel), chat.proposal!!.getString("_draft_id"),
            parcel.getString("item_id"), parcel.getInt("version"))
        withContext(Dispatchers.Main) {
            assertTrue(chat.editTaskDraft(vm, edit, edit.task.copy(plannedDate = "2026-10-17", priority = 3)))
        }
        waitFor { !chat.busy }
        assertEquals("", chat.error)
        assertEquals(2, db.assistant().draftItems().single().version)
        assertEquals("打印论文", db.tasks().observe().first().single().title)
        assertHistoryRepeatPreservesDraft(chat)
    }

    @Test fun historyRepeatPreservesLegacySingleTaskDraft() = runBlocking {
        val chat = openChat()
        nextReply = JSONObject().put("action", "create_task").put("title", "打印论文")
            .put("place_type", "PRINT_SHOP").put("deadline", "")
        send(chat, "打印论文")
        assertEquals("", chat.error)
        assertEquals("create_task", db.assistant().draft()?.action)
        assertTrue(db.tasks().observe().first().isEmpty())
        assertHistoryRepeatPreservesDraft(chat)
    }

    @Test fun patchPreservesOtherFieldsAndRejectsStaleOrUnknownItems() {
        val current = taskDraftReplacement(initial(), null, UUID.randomUUID().toString())
        val item = taskDraftItems(current)[1]
        val update = JSONObject().put("item_id", item.getString("item_id")).put("expected_version", 1).put("deadline", "")
        val reply = JSONObject().put("action", "update_task_drafts").put("updates", JSONArray().put(update))
        val result = taskDraftReplacement(reply, current, UUID.randomUUID().toString())
        assertEquals("2026-10-16", taskDraftItems(result)[1].getString("planned_date"))
        assertEquals(taskDraftItems(current)[0].toString(), taskDraftItems(result)[0].toString())
        assertTrue(runCatching { taskDraftReplacement(reply, result, UUID.randomUUID().toString()) }.isFailure)
        update.put("item_id", "unknown")
        assertTrue(runCatching { taskDraftReplacement(reply, current, UUID.randomUUID().toString()) }.isFailure)
    }

    @Test fun ambiguousConfirmationCannotSaveAllAndDuplicateTitlesNeedAnOrdinal() {
        val items = taskDraftItems(taskDraftReplacement(initial(), null, UUID.randomUUID().toString()))
        assertNull(taskDraftConfirmation("确认", items))
        assertEquals(setOf(items[1].getString("item_id")), taskDraftConfirmation("只保存第 2 项", items)!!.keys)
        assertNull(taskDraftConfirmation("不要保存第二项", items))
        assertNull(taskDraftConfirmation("保存第三项", items))
        items[0].put("title", "打印论文")
        assertNull(taskDraftConfirmation("保存打印", items))
        assertEquals(2, taskDraftConfirmation("全部保存", items)!!.size)
    }
}
