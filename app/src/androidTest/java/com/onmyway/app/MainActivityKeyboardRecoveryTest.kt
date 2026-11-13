package com.onmyway.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on an isolated emulator, including a real 320dp / 2x-font configuration. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityKeyboardRecoveryTest : ActivityTestSupport() {
    @Test fun assistantHeaderRemainsOneCompleteLineOnTheRealActivity() {
        launchApp()
        val header = compose.onAllNodesWithText("助手", useUnmergedTree = true)
            .fetchSemanticsNodes().minBy { it.boundsInRoot.top }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(SemanticsMatcher("assistant header") { it.id == header.id }, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Header must expose its actual text layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals("Assistant title must remain on one line", 1, layout.lineCount)
            assertFalse("Assistant title must not be ellipsized", layout.isLineEllipsized(0))
            assertEquals("Assistant title must retain both characters", 2, layout.getLineEnd(0, visibleEnd = true))
            "助手".indices.forEach { index ->
                val character = layout.getBoundingBox(index)
                assertTrue("Header character clipped: $character in ${layout.size}",
                    character.left >= -1f && character.top >= -1f &&
                        character.right <= layout.size.width + 1f && character.bottom <= layout.size.height + 1f)
            }
        }
    }

    @Test fun realKeyboardLeavesMessagesAndComposerUsableAndDraftSurvivesRecreate() {
        val scenario = launchApp()
        val draft = "取快递，取消打印后回宿舍"
        compose.waitUntilAtLeastOneExists(hasTestTag("assistant-input") and isEnabled(), 10_000)
        compose.onNodeWithTag("assistant-input").performClick().performTextInput(draft)
        waitForIme(scenario, visible = true)

        val window = windowState(scenario)
        val messages = compose.onNodeWithTag("assistant-messages").fetchSemanticsNode().boundsInWindow
        assertTrue("IME must leave at least 72dp of message space; actual=${messages.height / window.density}dp",
            messages.height >= 72f * window.density - 1f)
        assertAboveKeyboard("assistant-input", window)
        val send = compose.onNodeWithContentDescription("发送")
            .assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInWindow
        assertTrue("Send control must remain above the real IME: $send / $window",
            send.top >= 0f && send.bottom <= window.visibleBottom + 1f &&
                send.left >= 0f && send.right <= window.width + 1f)

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitForIme(scenario, visible = false)
        openTasksTab()
        compose.onNodeWithContentDescription("新增待办").assertIsDisplayed()
        compose.onNode(hasText("助手") and hasClickAction()).performClick()
        compose.onNodeWithTag("assistant-input").assertTextEquals(draft)

        recreateActivity()
        compose.onNodeWithTag("assistant-input").assertIsDisplayed().assertTextEquals(draft)
        compose.onNodeWithContentDescription("发送").assertIsEnabled()
        assertTrue("Typing and recreating must not create tasks", allTasks().isEmpty())
    }

    private data class WindowState(val imeVisible: Boolean, val visibleBottom: Float,
        val width: Float, val density: Float)

    private fun windowState(scenario: ActivityScenario<MainActivity>): WindowState {
        var state: WindowState? = null
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor)) { "Window insets unavailable" }
            state = WindowState(insets.isVisible(WindowInsetsCompat.Type.ime()),
                (decor.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom).toFloat(),
                decor.width.toFloat(), activity.resources.displayMetrics.density)
        }
        return checkNotNull(state)
    }

    private fun waitForIme(scenario: ActivityScenario<MainActivity>, visible: Boolean) {
        compose.waitUntil(10_000) { windowState(scenario).imeVisible == visible }
        compose.waitForIdle()
    }

    private fun assertAboveKeyboard(tag: String, window: WindowState) {
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue("Input must remain above the real IME: $bounds / $window",
            bounds.top >= 0f && bounds.bottom <= window.visibleBottom + 1f &&
                bounds.left >= 0f && bounds.right <= window.width + 1f)
    }
}
