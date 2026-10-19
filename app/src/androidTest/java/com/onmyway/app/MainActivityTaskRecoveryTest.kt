package com.onmyway.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.data.local.entity.Errand
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 MainActivity：编辑弹层在 Activity 重建后应保持，并只写入目标任务。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityTaskRecoveryTest : ActivityTestSupport() {
    private val pickupPlace = com.onmyway.app.core.model.Place("fixture-pickup-place", "验收领取点", 120.0, 31.0)

    private fun seedTwoTasksWithTrip(): Pair<Errand, Errand> {
        val parcel = seedTask("取快递", "PARCEL", place = pickupPlace)
        val print = seedTask("打印论文", "PRINT_SHOP", plannedDate = LocalDate.now().toString())
        seedTrip(parcel)
        return parcel to print
    }

    private fun openPrintEditor() {
        openTasksTab()
        compose.onNodeWithText("打印论文").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("编辑任务").assertIsDisplayed()
    }

    private fun editTitleDateAndPlace(targetDate: LocalDate) {
        compose.onNodeWithText("要办什么").performTextReplacement("打印论文（修改）")
        compose.onNodeWithContentDescription("计划日期").performClick()
        compose.onNodeWithText(targetDate.dayOfMonth.toString()).performClick()
        compose.onNodeWithText("确定").performClick()
        // 编辑器内不再内联常用地点芯片；打开地点选择弹层后再点常用地点。
        compose.onNodeWithText("搜索并选择地点").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("打印店 · 论文打印点").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("已选 · 论文打印点").performScrollTo().assertIsDisplayed()
    }

    private fun targetDate(): LocalDate {
        val today = LocalDate.now()
        return if (today.dayOfMonth < today.lengthOfMonth()) today.plusDays(1) else today.minusDays(1)
    }

    @Test fun manualNewTaskPrefillsDefaultPlaceAndSavesIt() {
        setDefaultPlace("打印店")
        launchApp()
        openTasksTab()
        // FAB 的“记一件事”仅在未合并语义树中，点击稳定的图标描述。
        compose.onNodeWithContentDescription("新增待办").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("记下一件事").assertIsDisplayed()
        compose.onNodeWithText("已选 · 论文打印点").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("默认地点「打印店」，可直接保存或更换。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("要办什么").performTextReplacement("打印开题报告")
        dismissSheetByBack()
        awaitDb { allTasks().any { it.title == "打印开题报告" } }
        val saved = allTasks().single { it.title == "打印开题报告" }
        assertEquals(printPlace, saved.place())
    }

    @Test fun taskEditorSurvivesRecreateAndBlankTitleDiscardsChanges() {
        val (parcel, print) = seedTwoTasksWithTrip()
        val tripBefore = runOnMainDb { db.trips().load() }
        val target = targetDate()
        launchApp()
        openPrintEditor()
        editTitleDateAndPlace(target)

        recreateActivity()

        compose.onNodeWithText("编辑任务").assertIsDisplayed()
        // 恢复的滚动位置停在底部，标题在滚动区顶部，先滚回再断言。
        compose.onNodeWithText("打印论文（修改）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(target.toString()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已选 · 论文打印点").performScrollTo().assertIsDisplayed()
        // 标题为空时关闭弹层视为放弃本次修改。
        compose.onNodeWithText("要办什么").performScrollTo().performTextReplacement("")
        dismissSheetByBack()

        assertEquals(print, task(print.id))
        assertEquals(parcel, task(parcel.id))
        assertEquals(tripBefore, runOnMainDb { db.trips().load() })
    }

    @Test fun taskEditorSaveAfterRecreateChangesOnlyTargetTask() {
        val (parcel, print) = seedTwoTasksWithTrip()
        val tripBefore = runOnMainDb { db.trips().load() }
        val target = targetDate()
        launchApp()
        openPrintEditor()
        editTitleDateAndPlace(target)

        recreateActivity()

        compose.onNodeWithText("保存任务").assertDoesNotExist()
        dismissSheetByBack()
        awaitDb {
            task(print.id)?.title == "打印论文（修改）"
        }
        val saved = task(print.id)
        assertNotNull(saved)
        assertEquals(print.id, saved!!.id)
        assertEquals(print.createdAt, saved.createdAt)
        assertEquals("打印论文（修改）", saved.title)
        assertEquals(target.toString(), saved.plannedDate)
        assertEquals(printPlace, saved.place())
        assertEquals(parcel, task(parcel.id))
        assertEquals(tripBefore, runOnMainDb { db.trips().load() })
    }

    @Test fun restoredTaskEditorClosesWithFeedbackWhenTaskWasDeleted() {
        val (_, print) = seedTwoTasksWithTrip()
        launchApp()
        openPrintEditor()
        compose.onNodeWithText("要办什么").performTextReplacement("打印论文（修改）")
        runOnMainDb { db.tasks().delete(print) }

        recreateActivity()

        compose.waitUntilAtLeastOneExists(
            androidx.compose.ui.test.hasText("这条待办已不存在，编辑已关闭。"), 10_000,
        )
        compose.onNodeWithText("编辑任务").assertDoesNotExist()
        assertNull(task(print.id))
    }

    @Test fun addEditorSurvivesRecreateAndStaysDistinctFromEditMode() {
        launchApp()
        openTasksTab()
        compose.onNodeWithContentDescription("新增待办").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("记下一件事").assertIsDisplayed()
        compose.onNodeWithText("要办什么").performTextReplacement("买水")

        recreateActivity()

        compose.onNodeWithText("记下一件事").assertIsDisplayed()
        compose.onNodeWithText("编辑任务").assertDoesNotExist()
        compose.onNodeWithText("买水").assertIsDisplayed()
        dismissSheetByBack()
        awaitDb { allTasks().any { it.title == "买水" } }
        assertNotNull(allTasks().single { it.title == "买水" }.id)
        assertEquals(1, allTasks().size)
    }

    private fun <T> runOnMainDb(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
