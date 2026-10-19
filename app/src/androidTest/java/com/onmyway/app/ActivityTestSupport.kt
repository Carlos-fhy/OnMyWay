package com.onmyway.app

import android.app.Application
import android.view.KeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.sync.BackupCredentials
import com.onmyway.app.feature.assistant.AssistantDraftItem
import com.onmyway.app.feature.assistant.AssistantStore
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * 真实 MainActivity 的仪器测试基类：使用生产 Room 单例与生产偏好，
 * 因此只允许在隔离模拟器上运行（前后都清空数据）。
 */
@OptIn(ExperimentalTestApi::class)
abstract class ActivityTestSupport {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var app: Application
    protected lateinit var db: TaskDatabase
    private var scenario: ActivityScenario<MainActivity>? = null

    protected val printPlace = Place("fixture-print-place", "论文打印点", 120.0123, 31.0123)

    @Before fun prepareActivityStorage() { runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = TaskDatabase.get(app)
        clearStorage()
        app.getSharedPreferences("settings", 0).edit()
            .putString("familiar_places", JSONObject().put("打印店", printPlace.json()).toString())
            .putString(
                "familiar_place_purposes",
                JSONObject().put("打印店", JSONArray().put("PRINT_SHOP")).toString(),
            )
            .commit()
    } }

    @After fun closeActivityAndStorage() = runBlocking {
        scenario?.close()
        scenario = null
        clearStorage()
    }

    private suspend fun clearStorage() {
        db.clearAllTables()
        BackupCredentials.clear(app)
        for (name in listOf("assistant_conversation", "settings", "navigation_context")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        app.deleteDatabase("onmyway-navigation-requests.db")
        app.deleteDatabase("onmyway-assistant-requests.db")
    }

    /** Must be called before launching the Activity; AppViewModel reads the default at init. */
    protected fun setDefaultPlace(alias: String) {
        app.getSharedPreferences("settings", 0).edit()
            .putString("default_familiar_place", alias).commit()
    }

    protected fun launchApp(): ActivityScenario<MainActivity> {
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        compose.waitForIdle()
        waitForText("助手")
        return launched
    }

    protected fun recreateActivity() {
        checkNotNull(scenario) { "Activity 尚未启动" }.recreate()
        compose.waitForIdle()
    }

    /** 手动记事弹层没有按钮：返回即保存，测试用同一路径关闭。 */
    protected fun dismissSheetByBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(2) {
            if (compose.onAllNodes(hasText("要办什么")).fetchSemanticsNodes().isEmpty()) return@repeat
            // ModalBottomSheet 运行在独立窗口，Activity 的 BackPressedDispatcher 收不到返回事件，
            // 需要发送真实返回键；第一次返回也可能只收起输入法，因此再确认一次。
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            runCatching { compose.waitUntilDoesNotExist(hasText("要办什么"), 1_500) }
        }
        compose.waitUntilDoesNotExist(hasText("要办什么"), 5_000)
    }

    protected fun openTasksTab() {
        compose.onNode(hasText("待办") and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    protected fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        compose.waitUntilAtLeastOneExists(hasText(text), timeoutMillis)
    }

    protected fun seedTask(
        title: String,
        placeType: String,
        plannedDate: String = "",
        place: Place? = null,
        serviceMinutes: Int = 5,
        priority: Int = 2,
    ): Errand {
        val task = Errand(
            title = title,
            placeType = placeType,
            plannedDate = plannedDate,
            placeJson = place?.json()?.toString().orEmpty(),
            serviceMinutes = serviceMinutes,
            priority = priority,
        )
        runBlocking { db.tasks().save(task) }
        return task
    }

    /** 一个含任务 A 的有效已保存行程，用于验证编辑/删除宿主不会误改路线。 */
    protected fun seedTrip(task: Errand): SavedTrip {
        val destination = Place("fixture-dorm", "宿舍", 120.02, 31.02)
        val trip = SavedTrip(
            requestJson = JSONObject()
                .put("origin", Place("fixture-origin", "学校", 120.0, 31.0).json())
                .put("destination", destination.json())
                .put("mode", "walking")
                .put("preference", "most_tasks")
                .put("allow_partial", false)
                .put("max_extra_minutes", JSONObject.NULL)
                .put("arrive_by", JSONObject.NULL)
                .put(
                    "tasks",
                    JSONArray().put(
                        JSONObject().put("id", task.id).put("title", task.title)
                            .put("place_type", task.placeType)
                            .put("place", task.place()?.json() ?: JSONObject.NULL),
                    ),
                )
                .toString(),
            resultJson = JSONObject()
                .put("destination", destination.json())
                .put("total_seconds", 600)
                .put("extra_seconds", 0)
                .put("arrival", java.time.OffsetDateTime.now().plusMinutes(10).toString())
                .put("stops", JSONArray())
                .put("omitted", JSONArray())
                .put("warnings", JSONArray())
                .toString(),
        )
        runBlocking { db.trips().save(trip) }
        return trip
    }

    protected fun seedDraft(
        draftId: String = "draft-recovery",
        planId: String = "plan-recovery",
    ): List<AssistantDraftItem> {
        fun item(id: String, title: String, placeType: String): JSONObject = JSONObject()
            .put("item_id", id).put("version", 1).put("title", title)
            .put("place_type", placeType).put("deadline", "")
            .put("planned_date", LocalDate.now().toString())
            .put("priority", 2).put("service_minutes", 5).put("place", JSONObject.NULL)
        val proposal = JSONObject()
            .put("action", "create_tasks").put("_draft_id", draftId).put("_plan_id", planId)
            .put("_card_version", 1)
            .put(
                "tasks",
                JSONArray().put(item("item-parcel", "取快递", "PARCEL"))
                    .put(item("item-print", "打印论文", "PRINT_SHOP")),
            )
        val store = AssistantStore(db, app.getSharedPreferences("assistant_conversation", 0))
        runBlocking { store.setDraft(proposal) }
        return draftItems()
    }

    protected fun task(id: String): Errand? = runBlocking { db.tasks().get(id) }

    protected fun allTasks(): List<Errand> = runBlocking { db.tasks().observe().first() }

    protected fun draftItems(): List<AssistantDraftItem> = runBlocking { db.assistant().draftItems() }

    protected fun findDraft(draftId: String): JSONObject? = runBlocking {
        AssistantStore(db, app.getSharedPreferences("assistant_conversation", 0)).load().second
            ?.takeIf { it.optString("_draft_id") == draftId }
    }

    protected fun awaitDb(timeoutMillis: Long = 10_000, condition: suspend () -> Boolean) = runBlocking {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(25)
        }
    }
}
