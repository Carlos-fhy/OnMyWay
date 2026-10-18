package com.onmyway.app.feature.assistant

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.OnMyWayTheme
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.remote.AssistantRequestTransport
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import com.onmyway.app.feature.navigation.NavigationRequestTransport
import java.util.UUID
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Whole chat screen, disposable storage, and synthetic messages; never contacts a service. */
@RunWith(AndroidJUnit4::class)
class AssistantChatUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private lateinit var isolated: Application
    private lateinit var db: TaskDatabase
    private lateinit var vm: AppViewModel
    private lateinit var chat: AssistantViewModel
    private val name = "assistant-chat-ui-${UUID.randomUUID()}"
    private val preferences = mutableSetOf<String>()
    private val submitted = AtomicInteger()
    private val transport = object : AssistantRequestTransport {
        override suspend fun submit(server: String, body: JSONObject): JSONObject {
            submitted.incrementAndGet()
            return JSONObject().put("action", "reply").put("reply", "已收到测试消息。")
        }
        override suspend fun status(server: String, body: JSONObject): JSONObject =
            error("This UI test must not recover a network request")
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
        val delivery = NavigationRequestDelivery(app, "$name-navigation.db", object : NavigationRequestTransport {
            override suspend fun submit(server: String, body: JSONObject): JSONObject =
                error("This chat UI test must not request navigation")
            override suspend fun status(server: String, body: JSONObject): JSONObject =
                error("This chat UI test must not recover navigation")
        })
        withContext(Dispatchers.Main) {
            vm = AppViewModel(isolated, db, delivery, reminderScheduler = {})
            chat = AssistantViewModel(isolated, isolated.getSharedPreferences("assistant_conversation", 0),
                db, transport, "$name-requests.db")
        }
        awaitState { chat.ready && !vm.restoringTrip }
        assertEquals("", chat.error)
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            if (::chat.isInitialized) chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            // AppViewModel closes its isolated navigation journal when its scope completes.
            if (::vm.isInitialized) vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::db.isInitialized) db.close()
        if (::app.isInitialized) {
            app.deleteDatabase("$name-navigation.db")
            app.deleteDatabase("$name-requests.db")
            preferences.forEach(app::deleteSharedPreferences)
        }
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        withTimeout(10_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(25) }
    }

    private fun record(vararg messages: String) = runBlocking {
        withContext(Dispatchers.Main) { messages.forEach { chat.record(it) } }
        withTimeout(10_000) {
            while (!db.assistant().recentMessages().map { it.text }.containsAll(messages.toList())) delay(25)
        }
    }

    private fun showScreen() {
        compose.setContent {
            OnMyWayTheme {
                Surface(Modifier.fillMaxSize()) {
                    AssistantScreen(vm, emptyList(), settings = {}, openPlan = {}, openNavigation = {}, chat = chat)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitMessage(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun assertNoTaskOrRequest() {
        compose.waitForIdle()
        assertEquals("Typing and navigation between input modes must not submit", 0, submitted.get())
        assertTrue(runBlocking { db.tasks().observe().first().isEmpty() })
        assertTrue(runBlocking { db.assistant().draftItems().isEmpty() })
    }

    @Test fun mainScenarioSuggestionOnlyFillsEditableDraft() {
        showScreen()
        val scenario = "取快递、打印论文后回宿舍"
        compose.onNodeWithText(scenario).performScrollTo().performClick()
        compose.onNodeWithTag("assistant-input").assertTextEquals(scenario)
        compose.runOnIdle { assertEquals(scenario, chat.input) }
        assertNoTaskOrRequest()
        assertTrue("A suggestion is not a sent message", runBlocking { db.assistant().recentMessages().isEmpty() })
    }

    @Test fun copyReplyWritesExactMessageToClipboard() {
        val reply = "先去取快递，再打印论文，最后回宿舍。\n确认实际领取地点后再安排路线。"
        record(reply)
        showScreen()
        awaitMessage(reply)
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("test", "旧内容")) }
        compose.onNodeWithContentDescription("复制回复").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(reply, clipboard.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString())
        }
        assertNoTaskOrRequest()
    }

    @Test fun incomingReplyKeepsFocusedDraftWhileFollowingLatest() {
        val history = (1..20).map { "第 $it 条对话：核对快递领取点和论文打印要求。" }
        record(*history.toTypedArray())
        showScreen()
        awaitMessage(history.last())
        val input = compose.onNodeWithTag("assistant-input")
        input.performClick().performTextInput("还要带上打印材料")
        input.assertIsFocused()
        val incoming = "新回复：打印材料已记在这段对话中。\n可以继续补充你的要求。"
        record(incoming)
        awaitMessage(incoming)
        input.assertIsFocused().assertTextEquals("还要带上打印材料")
        assertNoTaskOrRequest()
    }

    @Test fun readingEarlierMessagesDoesNotJumpAndLatestButtonReturnsToBottom() {
        val history = (1..40).map { "历史消息 $it：取快递，打印论文，回宿舍。" }
        record(*history.toTypedArray())
        showScreen()
        awaitMessage(history.last())
        val list = compose.onNodeWithTag("assistant-messages")
        repeat(3) {
            list.performTouchInput { swipeDown(durationMillis = 350) }
            compose.waitForIdle()
        }
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val anchor = compose.onAllNodes(hasText("历史消息", substring = true)).fetchSemanticsNodes()
            .first { node ->
                node.boundsInRoot.height > 0 && node.boundsInRoot.top >= listBounds.top &&
                    node.boundsInRoot.bottom <= listBounds.bottom
            }.config[SemanticsProperties.Text].first().text
        assertNotEquals("The swipe must leave the latest reply", history.last(), anchor)
        compose.onNodeWithText(anchor).assertIsDisplayed()
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val incoming = "最新消息：请确认返回宿舍的时间。"
        record(incoming)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("回到最新消息").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText(anchor).assertIsDisplayed()
        val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("A new reply must preserve the reader's scroll position", before, after, 0.01f)
        compose.onNodeWithContentDescription("回到最新消息").performClick()
        awaitMessage(incoming)
        compose.onNodeWithText(incoming).assertIsDisplayed()
        assertNoTaskOrRequest()
    }

    @Test fun voiceToggleKeepsDraftAndWhitespaceCannotBeSent() {
        showScreen()
        val input = compose.onNodeWithTag("assistant-input")
        input.performTextInput("取快递后去打印论文")
        compose.onNodeWithContentDescription("切换语音输入").performClick()
        compose.onNodeWithContentDescription("切换文字输入").performClick()
        input.assertTextEquals("取快递后去打印论文")
        input.performTextReplacement("   ")
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        input.performImeAction()
        assertNoTaskOrRequest()
        assertTrue(runBlocking { db.assistant().recentMessages().isEmpty() })
    }

    @Test fun mainScenarioSendsOnceAndRendersBothSidesWithoutCreatingTasks() {
        showScreen()
        val scenario = "取快递、打印论文后回宿舍"
        val reply = "已收到测试消息。"
        compose.onNodeWithText(scenario).performScrollTo().performClick()
        compose.onNodeWithContentDescription("发送").performClick()
        runBlocking { awaitState { submitted.get() == 1 && !chat.busy } }
        awaitMessage(reply)
        compose.onNodeWithText(scenario).assertIsDisplayed()
        compose.onNodeWithText(reply).assertIsDisplayed()
        compose.onNodeWithTag("assistant-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.runOnIdle {
            assertEquals("", chat.error)
            assertNull(chat.pendingRequest)
            assertNull(chat.proposal)
        }
        val messages = runBlocking { db.assistant().recentMessages() }
        assertEquals("Only the submitted exchange should be stored", 2, messages.size)
        assertEquals(scenario, messages.single { it.role == "user" }.text)
        assertEquals(reply, messages.single { it.role == "assistant" }.text)
        assertEquals(1, submitted.get())
        assertTrue(runBlocking { db.tasks().observe().first().isEmpty() })
        assertTrue(runBlocking { db.assistant().draftItems().isEmpty() })
        assertNull(runBlocking { db.trips().load() })
        assertTrue(runBlocking { db.trips().history().isEmpty() })

        // A raw full-device capture of this synthetic exchange, with no real credentials or data.
        compose.waitForIdle()
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(app.getExternalFilesDir(null))
            val output = File(directory, "assistant-chat-message.png")
            output.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }
}
