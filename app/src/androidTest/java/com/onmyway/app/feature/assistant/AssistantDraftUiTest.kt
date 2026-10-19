package com.onmyway.app.feature.assistant

import java.util.UUID
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.R
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.remote.AssistantRequestTransport
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import com.onmyway.app.feature.task.TaskEditor
import com.onmyway.app.feature.task.TaskEditorMode
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production components + ViewModels, isolated storage and synthetic model/place inputs. */
@RunWith(AndroidJUnit4::class)
class AssistantDraftUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private lateinit var isolated: Application
    private lateinit var db: TaskDatabase
    private lateinit var vm: AppViewModel
    private lateinit var delivery: NavigationRequestDelivery
    private var chat by mutableStateOf<AssistantViewModel?>(null)
    private var showCard by mutableStateOf(true)
    private val name = "draft-ui-${UUID.randomUUID()}"
    private val preferences = mutableSetOf<String>()
    private val chats = mutableListOf<AssistantViewModel>()
    private val pickup = Place("fixture-pickup", "验收领取点", 120.0, 31.0)
    private val transport = object : AssistantRequestTransport {
        override suspend fun submit(server: String, body: JSONObject) = JSONObject()
            .put("action", "create_tasks").put("tasks", JSONArray()
                .put(JSONObject().put("title", "取快递").put("place_type", "PARCEL").put("planned_date", "2026-10-17"))
                .put(JSONObject().put("title", "打印论文").put("place_type", "PRINT_SHOP").put("planned_date", "2026-10-17")))
        override suspend fun status(server: String, body: JSONObject): JSONObject = error("Unexpected network recovery")
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
        awaitState { !vm.restoringTrip }
        withContext(Dispatchers.Main) { vm.saveFamiliarPlace("测试快递站", pickup) }
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            chats.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        delivery.close()
        db.close()
        app.deleteDatabase("$name-navigation.db")
        app.deleteDatabase("$name-chat.db")
        preferences.forEach(app::deleteSharedPreferences)
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        withTimeout(10_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(25) }
    }

    private fun openChat(seed: Boolean) = runBlocking {
        val opened = withContext(Dispatchers.Main) {
            AssistantViewModel(isolated, isolated.getSharedPreferences("assistant_conversation", 0), db, transport, "$name-chat.db")
        }.also(chats::add)
        awaitState { opened.ready }
        withContext(Dispatchers.Main) {
            chat = opened
            if (seed) { opened.input = "今天取快递、打印论文"; opened.send(vm) }
        }
        awaitState { !opened.busy }
        assertEquals("", opened.error)
    }

    private fun settle() {
        runBlocking { awaitState { chat?.busy != true } }
        compose.waitForIdle()
        assertEquals("", chat?.error.orEmpty())
    }

    private fun tasks() = runBlocking { db.tasks().observe().first() }
    private fun draft() = runBlocking { db.assistant().draftItems() }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(app.getExternalFilesDir(null), "m1-ui/$label.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // AGP removes the target app after the run; preserve synthetic screenshots outside its data.
        // UiAutomation executes commands directly; shell operators would be treated as arguments.
        for (command in listOf("mkdir -p /data/local/tmp/onmyway-m1-ui",
            "cp ${file.absolutePath} /data/local/tmp/onmyway-m1-ui/$label-capture.png")) {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }

    private fun showDrafts(fontScale: Float = 1f) {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    var editing by remember { mutableStateOf<TaskDraftEdit?>(null) }
                    Surface {
                        Column(Modifier.widthIn(max = 320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                            val current = chat
                            val proposal = current?.proposal
                            if (showCard && current != null && proposal != null) AssistantTaskDraftCard(
                                proposal, !current.busy,
                                edit = { editing = it },
                                confirm = { current.saveTasks(vm, proposal.getString("_draft_id"), it) },
                                cancel = { current.input = "取消"; current.send(vm) },
                            ) else Text("其他页面")
                        }
                    }
                    editing?.let { edit -> key(edit.draftId, edit.itemId, edit.version) {
                        TaskEditor(vm, edit.task, { editing = null }, allowIncompleteDraft = true,
                            saveLabel = "保留草稿修改", mode = TaskEditorMode.Assistant) { updated ->
                            if (chat!!.editTaskDraft(vm, edit, updated)) editing = null
                        }
                    } }
                }
            }
        }
    }

    private fun clickText(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()
    private fun clickButton(text: String) = compose.onNodeWithText(text).performClick()
    private fun clickDescription(text: String) = compose.onNodeWithContentDescription(text).performScrollTo().performClick()

    /** 地点芯片在横向 ChoiceRow 中，performScrollTo 不会滚动外层纵向弹层。 */
    private fun scrollEditorToPlaceSection() {
        compose.onNodeWithText("这是助手草稿：可以先保留修改，地点也可以稍后再补。").performScrollTo()
    }

    @Test fun selectPickupSaveParcelThenEditPrintDateAndConfirm() {
        openChat(seed = true)
        val printBefore = draft().single { it.title == "打印论文" }
        showDrafts()
        compose.onNodeWithText("保存所选 0 项").assertIsNotEnabled()
        clickDescription("修改第 1 项：取快递")
        scrollEditorToPlaceSection()
        compose.onNodeWithText("要出门").assertDoesNotExist()
        compose.onNodeWithText("最近用过").assertDoesNotExist()
        clickText("选择地点")
        clickText("测试快递站 · 验收领取点")
        clickButton("保留草稿修改")
        settle()
        assertTrue(tasks().isEmpty())
        assertEquals(printBefore, draft().single { it.title == "打印论文" })
        clickDescription("选择第 1 项：取快递")
        clickText("保存所选 1 项")
        settle()
        val parcel = tasks().single()
        assertEquals("取快递", parcel.title)
        assertEquals(pickup, parcel.place())
        // Removing the first item renumbers display positions, but never changes identity or fields.
        assertEquals(printBefore.copy(position = 0), draft().single())
        screenshot("parcel-saved-print-remains")
        // Remove and re-enter the card, then reopen the persisted conversation.
        compose.runOnIdle { showCard = false }
        compose.onNodeWithText("其他页面").assertIsDisplayed()
        runBlocking { withContext(Dispatchers.Main) { chat!!.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        openChat(seed = false)
        compose.runOnIdle { showCard = true }
        clickDescription("修改第 1 项：打印论文")
        clickDescription("计划日期")
        // The dialog opens on the draft's existing October 2026 plan date; pick the 19th.
        compose.onNodeWithText("19", substring = true).performClick()
        compose.onNodeWithText("确定").performClick()
        clickButton("保留草稿修改")
        settle()
        assertEquals(listOf(parcel), tasks())
        assertEquals("2026-10-19", draft().single().plannedDate)
        // 编辑器不再提供优先级入口，保存时保留原值。
        assertEquals(printBefore.priority, draft().single().priority)
        assertEquals("", draft().single().deadline)
        screenshot("print-edited-after-reopen")
        clickDescription("选择第 1 项：打印论文")
        clickText("保存所选 1 项")
        settle()
        assertEquals(2, tasks().size)
        assertEquals(parcel, tasks().single { it.id == parcel.id })
        assertEquals("2026-10-19", tasks().single { it.title == "打印论文" }.plannedDate)
        assertTrue(draft().isEmpty())
    }

    @Test fun cancelEditAndLargeFontSelectionDoNotChangeUnselectedTask() {
        openChat(seed = true)
        val original = draft()
        showDrafts(fontScale = 2f)
        clickDescription("修改第 2 项：打印论文")
        compose.onNodeWithText("要办什么").performTextReplacement("取消的修改")
        clickButton("取消")
        settle()
        assertEquals(original, draft())
        assertTrue(tasks().isEmpty())
        clickDescription("选择第 2 项：打印论文")
        compose.onNodeWithText("保存所选 1 项").performScrollTo().assertIsDisplayed()
        screenshot("large-font-selected-print")
        compose.onNodeWithText("保存所选 1 项").performClick()
        settle()
        assertEquals("打印论文", tasks().single().title)
        assertEquals(original.first(), draft().single())
        clickText("取消剩余草稿")
        settle()
        assertTrue(draft().isEmpty())
        assertEquals("打印论文", tasks().single().title)
    }

    @Test fun editorSavedStateRetainsNewPlaceAlongsideTitleAndDate() {
        val restoration = StateRestorationTester(compose)
        val original = Errand(title = "取快递", placeType = "PARCEL", plannedDate = "2026-10-17")
        var saved: Errand? = null
        restoration.setContent {
            MaterialTheme { TaskEditor(vm, original, {}, allowIncompleteDraft = true) { saved = it } }
        }
        compose.onNodeWithText("要办什么").performTextReplacement("取两件快递")
        scrollEditorToPlaceSection()
        compose.onNodeWithText("常用地点").assertDoesNotExist()
        compose.onNodeWithText("最近用过").assertDoesNotExist()
        clickText("搜索并选择地点")
        clickText("测试快递站 · 验收领取点")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("已选 · 验收领取点").performScrollTo().assertIsDisplayed()
        screenshot("editor-restored-pickup")
        clickButton("保存任务")
        assertEquals("取两件快递", saved!!.title)
        assertEquals(pickup, saved!!.place())
        assertEquals("2026-10-17", saved!!.plannedDate)
        assertEquals("", saved!!.deadline)
        assertEquals(original.priority, saved!!.priority)
        assertTrue(tasks().isEmpty()) // This editor callback alone never creates a task.
    }
}
