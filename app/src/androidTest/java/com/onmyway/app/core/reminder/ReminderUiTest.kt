package com.onmyway.app.core.reminder

import java.util.UUID
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.R
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import com.onmyway.app.feature.task.TaskScreen
import com.onmyway.app.feature.task.displayReminderTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production task screen and ViewModel with synthetic tasks and isolated local storage. */
@RunWith(AndroidJUnit4::class)
class ReminderUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private lateinit var db: TaskDatabase
    private lateinit var vm: AppViewModel
    private lateinit var delivery: NavigationRequestDelivery
    private val name = "reminder-ui-${UUID.randomUUID()}"
    private val parcelId = "$name-parcel"
    private val printId = "$name-print"
    private val preferences = mutableSetOf<String>()

    @Before fun setup() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val isolated = object : Application() {
            init { attachBaseContext(app) }
            override fun getSharedPreferences(key: String, mode: Int): android.content.SharedPreferences {
                val unique = "$name-$key"
                preferences += unique
                return app.getSharedPreferences(unique, mode)
            }
        }
        db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        delivery = NavigationRequestDelivery(app, "$name-navigation.db")
        vm = withContext(Dispatchers.Main) { AppViewModel(isolated, db, delivery, reminderScheduler = {}) }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(25)
        }
        db.tasks().save(Errand(id = parcelId, title = "取快递", placeType = "PARCEL",
            placeJson = Place("fixture-pickup", "验收领取点", 120.0, 31.0).json().toString(),
            plannedDate = LocalDate.now().toString(), priority = 2, reminderPending = true))
        db.tasks().save(Errand(id = printId, title = "打印论文", placeType = "PRINT_SHOP",
            plannedDate = LocalDate.now().toString(), deadline = "2026-10-19T17:00:00+08:00",
            priority = 3, reminderPending = true))
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        delivery.close()
        db.close()
        app.deleteDatabase("$name-navigation.db")
        preferences.forEach(app::deleteSharedPreferences)
    }

    private fun showTasks() {
        compose.setContent {
            MaterialTheme {
                val tasks by db.tasks().observe().collectAsState(emptyList())
                TaskScreen(tasks, {}, {}, {}, vm::save, vm::snoozeReminder, vm::dismissReminder)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("打印论文").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("打印论文").assertIsDisplayed()
    }

    private fun snapshot() = runBlocking { db.tasks().observe().first().associateBy { it.id } }

    private fun awaitTask(id: String, predicate: (Errand) -> Boolean): Errand = runBlocking {
        withTimeout(10_000) {
            var task = db.tasks().get(id)!!
            while (!predicate(task)) { delay(25); task = db.tasks().get(id)!! }
            task
        }
    }

    private fun openPrintReminder() {
        compose.onNodeWithContentDescription("打印论文的更多操作").performClick()
        compose.onNodeWithText("稍后提醒").performClick()
    }

    private fun assertTaskDetailsUnchanged(before: Errand, after: Errand) {
        assertEquals(before.id, after.id)
        assertEquals(before.title, after.title)
        assertEquals(before.status, after.status)
        assertEquals(before.plannedDate, after.plannedDate)
        assertEquals(before.deadline, after.deadline)
        assertEquals(before.priority, after.priority)
        assertEquals(before.placeJson, after.placeJson)
        assertEquals(before.placeType, after.placeType)
        assertEquals(before.serviceMinutes, after.serviceMinutes)
    }

    private fun selectCustomTime(dayLabel: String, dayIndex: Int, selectedHour: Int, selectedMinute: Int) {
        compose.onNodeWithText("自选日期时间").performClick()
        compose.onNodeWithText("选择日期和时间").performScrollTo().performClick()
        compose.onNodeWithTag("date-row").performScrollToIndex(dayIndex)
        compose.onNodeWithText(dayLabel).performClick()
        compose.onNodeWithTag("hour-row").performScrollToIndex(selectedHour)
        compose.onNodeWithText("${selectedHour}时").performClick()
        compose.onNodeWithTag("minute-row").performScrollToIndex(selectedMinute / 5)
        compose.onNodeWithText("%02d分".format(selectedMinute)).performClick()
        compose.onNodeWithText("确定").performClick()
    }

    @Test fun pendingTaskMenuOffersSnoozeAndIndividualDismiss() {
        showTasks()
        compose.onNodeWithContentDescription("打印论文的更多操作").performClick()
        compose.onNodeWithText("稍后提醒").assertIsDisplayed()
        compose.onNodeWithText("忽略本次提醒").assertIsDisplayed()
    }

    @Test fun oneHourSnoozeChangesOnlyPrintReminder() {
        val before = snapshot()
        showTasks()
        openPrintReminder()
        compose.onNodeWithText("仅调整本任务的提醒，不改变计划日期、截止时间和优先级。").assertIsDisplayed()
        val earliest = System.currentTimeMillis() + 3_600_000
        compose.onNodeWithText("确认稍后提醒").performClick()
        val changed = awaitTask(printId) { it.reminderAt > 0 && !it.reminderPending }
        val latest = System.currentTimeMillis() + 3_600_000
        assertTrue(changed.reminderAt in earliest..latest)
        assertTaskDetailsUnchanged(before.getValue(printId), changed)
        assertEquals(before.getValue(parcelId), snapshot().getValue(parcelId))
        compose.onNodeWithContentDescription("打印论文的更多操作").performClick()
        compose.onNodeWithText("忽略本次提醒").assertDoesNotExist()
    }

    @Test fun dismissPrintKeepsParcelReminderAndBothTasks() {
        val before = snapshot()
        showTasks()
        compose.onNodeWithContentDescription("打印论文的更多操作").performClick()
        compose.onNodeWithText("忽略本次提醒").performClick()
        val changed = awaitTask(printId) { !it.reminderPending }
        assertTaskDetailsUnchanged(before.getValue(printId), changed)
        assertEquals(0L, changed.reminderAt)
        assertEquals(2, snapshot().size)
        assertEquals(before.getValue(parcelId), snapshot().getValue(parcelId))
        compose.onNodeWithText("打印论文").assertIsDisplayed()
    }

    @Test fun cancelSnoozeNeverWritesEitherTask() {
        val before = snapshot()
        showTasks()
        openPrintReminder()
        compose.onNodeWithText("明天 09:00").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("稍后提醒：打印论文").assertDoesNotExist()
        assertEquals(before, snapshot())
    }

    @Test fun tomorrowMorningReminderKeepsPrintPlanAndDeadline() {
        val before = snapshot()
        showTasks()
        openPrintReminder()
        compose.onNodeWithText("明天 09:00").performClick()
        val expected = LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        compose.onNodeWithText("确认稍后提醒").performClick()
        val changed = awaitTask(printId) { it.reminderAt > 0 }
        assertEquals(expected, changed.reminderAt)
        assertTaskDetailsUnchanged(before.getValue(printId), changed)
        assertEquals(before.getValue(parcelId), snapshot().getValue(parcelId))
    }

    @Test fun pastCustomTimeIsRejectedAndCancelKeepsTasks() {
        val before = snapshot()
        showTasks()
        openPrintReminder()
        selectCustomTime("今天", 0, 0, 0)
        compose.onNodeWithText("确认稍后提醒").performClick()
        compose.onNodeWithText("请选择未来的提醒时间").performScrollTo().assertIsDisplayed()
        assertEquals(before, snapshot())
        compose.onNodeWithText("取消").performClick()
        assertEquals(before, snapshot())
    }

    @Test fun futureCustomTimeSavesOnlyPrintReminder() {
        val before = snapshot()
        showTasks()
        openPrintReminder()
        val date = LocalDate.now().plusDays(2)
        selectCustomTime("后天", 2, 13, 15)
        val expected = date.atTime(13, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        compose.onNodeWithText("确认稍后提醒").performClick()
        val changed = awaitTask(printId) { it.reminderAt > 0 && !it.reminderPending }
        assertEquals(expected, changed.reminderAt)
        assertTaskDetailsUnchanged(before.getValue(printId), changed)
        assertEquals(before.getValue(parcelId), snapshot().getValue(parcelId))
    }

    @Test fun completedParcelHasNoReminderActions() {
        showTasks()
        compose.onNodeWithContentDescription("完成取快递").performClick()
        val completed = awaitTask(parcelId) { it.status == "done" }
        assertFalse(completed.reminderPending)
        assertEquals(0L, completed.reminderAt)
        compose.onNodeWithText("已完成 (1)").performClick()
        compose.onNodeWithContentDescription("取快递的更多操作").performClick()
        compose.onNodeWithText("稍后提醒").assertDoesNotExist()
        compose.onNodeWithText("忽略本次提醒").assertDoesNotExist()
    }

    @Test fun existingAppointmentIsVisibleWithoutDiscardingIt() {
        val at = Instant.now().plusSeconds(7_200).toEpochMilli()
        runBlocking { db.tasks().save(db.tasks().get(printId)!!.copy(reminderAt = at, reminderPending = false)) }
        val before = snapshot()
        showTasks()
        openPrintReminder()
        compose.onNodeWithText("当前预约：${displayReminderTime(at)}").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(before, snapshot())
    }

    @Test fun changedTaskClosesOldReminderDialog() {
        showTasks()
        openPrintReminder()
        val changed = snapshot().getValue(printId).copy(title = "打印两份论文", updatedAt = System.currentTimeMillis())
        runBlocking { db.tasks().save(changed) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("稍后提醒：打印论文").fetchSemanticsNodes().isEmpty() }
        assertEquals(changed, snapshot().getValue(printId))
    }
}
