package com.onmyway.app.feature.assistant

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.remote.AssistantRequestTransport
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
class AssistantDraftContextTest {
    private lateinit var app: Application
    private lateinit var isolatedApp: Application
    private lateinit var db: TaskDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var store: AssistantStore
    private lateinit var vm: AppViewModel
    private lateinit var navigationDelivery: NavigationRequestDelivery
    private lateinit var testName: String
    private val preferenceNames = mutableSetOf<String>()
    private val chats = mutableListOf<AssistantViewModel>()
    private val submissions = CopyOnWriteArrayList<String>()
    private val transport = object : AssistantRequestTransport {
        override suspend fun submit(server: String, body: JSONObject): JSONObject {
            submissions.add(body.toString())
            return JSONObject().put("action", "reply").put("reply", "请核对草稿。")
        }
        override suspend fun status(server: String, body: JSONObject): JSONObject =
            error("A new request must not query recovery status")
    }

    @Before fun setUp() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        testName = "assistant_draft_context_${UUID.randomUUID()}"
        isolatedApp = object : Application() {
            init { attachBaseContext(app) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$testName-$name"
                preferenceNames += isolatedName
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
        prefs = isolatedApp.getSharedPreferences("assistant_conversation", 0)
        db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        store = AssistantStore(db, prefs)
        navigationDelivery = NavigationRequestDelivery(app, "$testName-navigation.db")
        vm = withContext(Dispatchers.Main) { AppViewModel(isolatedApp, db, navigationDelivery) }
        waitUntil { !vm.restoringTrip }
    }

    @After fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) {
            chats.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        navigationDelivery.close()
        db.close()
        app.deleteDatabase("$testName-navigation.db")
        app.deleteDatabase("$testName-chat.db")
        preferenceNames.forEach(app::deleteSharedPreferences)
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(5_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(20) }
    }

    private fun task(title: String, type: String, deadline: String = "") = JSONObject()
        .put("action", "create_task").put("title", title).put("place_type", type)
        .put("deadline", deadline).put("_draft_id", UUID.randomUUID().toString())

    private suspend fun openChat(proposal: JSONObject): AssistantViewModel {
        // Restoring also restores the clarification message that accompanies real follow-ups.
        store.setDraft(proposal)
        val chat = withContext(Dispatchers.Main) {
            AssistantViewModel(isolatedApp, prefs, db, transport, "$testName-chat.db")
        }.also(chats::add)
        waitUntil { chat.ready }
        return chat
    }

    private suspend fun send(chat: AssistantViewModel, text: String): JSONObject {
        val expectedCount = submissions.size + 1
        withContext(Dispatchers.Main) { chat.input = text; chat.send(vm) }
        waitUntil { submissions.size == expectedCount && !chat.busy }
        withContext(Dispatchers.Main) {
            assertEquals("", chat.error)
            assertNull(chat.pendingRequest)
        }
        val body = JSONObject(submissions.last())
        // Context stays inside messages; the durable request/recovery protocol is unchanged.
        assertEquals(setOf("request_id", "messages", "route_active", "now"), body.keys().asSequence().toSet())
        val messages = body.getJSONArray("messages")
        assertEquals("user", messages.getJSONObject(messages.length() - 1).getString("role"))
        assertEquals(text, messages.getJSONObject(messages.length() - 1).getString("content"))
        assertTrue(db.tasks().observe().first().isEmpty())
        return body
    }

    private fun taskFields(body: JSONObject): JSONObject {
        val messages = body.getJSONArray("messages")
        val context = (0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.optString("content").startsWith("当前有未确认的待办草稿") }
        assertEquals("assistant", context.getString("role"))
        val content = context.getString("content")
        assertTrue(content.contains("任务尚未保存"))
        assertTrue(content.contains("只修改明确指定的字段"))
        val fields = JSONObject(content.substringAfter('\n'))
        assertEquals(setOf("title", "place_type", "deadline", "reminder_at"), fields.keys().asSequence().toSet())
        return fields
    }

    @Test fun followUpRequestsCarryEachCurrentTaskTypeAndDeadline() = runBlocking {
        val proposals = listOf(
            task("打印论文", "PRINT_SHOP", "2026-10-15T14:30:00+08:00"),
            task("取快递", "PARCEL", "2026-10-16T18:00:00+08:00"),
        )
        for (proposal in proposals) {
            val chat = openChat(proposal)
            val before = db.assistant().draft()
            val fields = taskFields(send(chat, "只把名称改短一点"))
            listOf("title", "place_type", "deadline").forEach { field ->
                assertEquals(proposal.getString(field), fields.getString(field))
            }
            // Supplying context is read-only; even a model reply must not confirm the task.
            assertEquals(before, db.assistant().draft())
            withContext(Dispatchers.Main) { chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    @Test fun unsetDeadlineIsExplicitEvenWhenOlderConversationMentionedADate() = runBlocking {
        store.add(ChatLine("user", "之前说过明天下午三点截止"))
        val proposal = task("买水", "SUPERMARKET").apply { remove("deadline") }
        val chat = openChat(proposal)
        val fields = taskFields(send(chat, "名称改成买两瓶水"))
        assertEquals("买水", fields.getString("title"))
        assertEquals("SUPERMARKET", fields.getString("place_type"))
        assertEquals("", fields.getString("deadline"))
        assertEquals("", db.assistant().draft()?.deadline)
    }

    @Test fun routeFollowUpKeepsDestinationModeAndUnconfirmedMeaning() = runBlocking {
        val chat = openChat(JSONObject().put("action", "plan_route")
            .put("destination", "图书馆").put("mode", "cycling"))
        val before = db.assistant().draft()
        val messages = send(chat, "终点改成体育馆").getJSONArray("messages")
        val contexts = (0 until messages.length()).map { messages.getJSONObject(it).getString("content") }
        assertTrue(contexts.contains("当前有未确认的路线草稿：终点图书馆，出行方式cycling。这条路线尚未执行。"))
        assertFalse(contexts.any { it.startsWith("当前有未确认的待办草稿") })
        assertEquals(before, db.assistant().draft())
    }

    @Test fun cancellingDraftDoesNotSendContextOrCreateATask() = runBlocking {
        val chat = openChat(task("取快递", "PARCEL", "2026-10-15T18:00:00+08:00"))
        withContext(Dispatchers.Main) {
            chat.input = "取消"
            chat.send(vm)
            assertEquals("", chat.input)
        }
        waitUntil { !chat.busy }
        withTimeout(5_000) { while (db.assistant().draft() != null) delay(20) }
        withContext(Dispatchers.Main) { assertNull(chat.proposal) }
        assertTrue(submissions.isEmpty())
        assertTrue(db.tasks().observe().first().isEmpty())
        assertNull(db.assistant().clarification())
    }

    @Test fun contextEscapesTitleAndExcludesInternalAndUnrecognizedFields() {
        val title = "打印\"终稿\"\n两份"
        val draft = task(title, "PRINT_SHOP")
            .put("_plan_id", "local-plan")
            .put("reply", "provider-reply")
            .put("priority", 3)
            .put("known_destination", JSONObject().put("name", "irrelevant"))
        val before = draft.toString()
        val context = assistantDraftContext(draft)!!
        val fields = JSONObject(context.substringAfter('\n'))
        assertEquals(setOf("title", "place_type", "deadline", "reminder_at"), fields.keys().asSequence().toSet())
        assertEquals(title, fields.getString("title"))
        assertEquals(before, draft.toString())
        assertNull(assistantDraftContext(JSONObject().put("action", "reply")))
    }
}
