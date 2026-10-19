package com.onmyway.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.feature.assistant.AssistantStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 MainActivity：助手草稿编辑弹层在 Activity 重建后应保持，并能整页反馈失效操作。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityAssistantRecoveryTest : ActivityTestSupport() {
    private val draftId = "draft-recovery"

    private fun waitForCardItem(title: String) {
        compose.waitUntilAtLeastOneExists(hasContentDescription("修改第 2 项：$title"), 10_000)
    }

    private fun openPrintDraftItemEditor() {
        launchApp()
        waitForCardItem("打印论文")
        compose.onNodeWithContentDescription("修改第 2 项：打印论文").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("核对任务").assertIsDisplayed()
    }

    private fun assistantStore() =
        AssistantStore(db, app.getSharedPreferences("assistant_conversation", 0))

    @Test fun draftItemEditorSurvivesRecreateAndCancelWritesNothing() {
        seedDraft(draftId)
        val before = draftItems()
        openPrintDraftItemEditor()
        compose.onNodeWithText("要办什么").performTextReplacement("打印论文（草稿修改）")

        recreateActivity()

        compose.onNodeWithText("核对任务").assertIsDisplayed()
        compose.onNodeWithText("打印论文（草稿修改）").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()

        assertEquals(before, draftItems())
        assertTrue(allTasks().isEmpty())
        assertNotNull(findDraft(draftId))
    }

    @Test fun draftItemEditorSaveAfterRecreateAdvancesOnlyTargetItem() {
        seedDraft(draftId)
        val before = draftItems()
        openPrintDraftItemEditor()
        compose.onNodeWithText("要办什么").performTextReplacement("打印论文（草稿修改）")

        recreateActivity()

        compose.onNodeWithText("保留草稿修改").performClick()
        awaitDb { draftItems().single { it.id == "item-print" }.version == 2 }
        val updated = draftItems().single { it.id == "item-print" }
        assertEquals("打印论文（草稿修改）", updated.title)
        assertEquals(2, updated.version)
        assertEquals(before.single { it.id == "item-parcel" }, draftItems().single { it.id == "item-parcel" })
        assertTrue(allTasks().isEmpty())
    }

    @Test fun staleDraftEditShowsFeedbackAtWholePageLevel() {
        seedDraft(draftId)
        openPrintDraftItemEditor()
        compose.onNodeWithText("要办什么").performTextReplacement("过期的修改")
        // 编辑器打开期间，草稿被另一条路径修改：条目版本 + 1，草稿 ID 也随之更换。
        runBlocking {
            val stored = draftItems().single { it.id == "item-print" }
            assistantStore().updateDraftItem(
                draftId, "item-print", stored.version, stored.task().copy(title = "外部修改"),
            )
        }

        compose.onNodeWithText("保留草稿修改").performClick()

        compose.waitUntilAtLeastOneExists(
            hasText("待确认任务已变化，请关闭旧编辑页并重新编辑。"), 10_000,
        )
        compose.onNodeWithText("核对任务").assertDoesNotExist()
        assertTrue(allTasks().isEmpty())
        assertEquals("外部修改", draftItems().single { it.id == "item-print" }.title)
    }
}
