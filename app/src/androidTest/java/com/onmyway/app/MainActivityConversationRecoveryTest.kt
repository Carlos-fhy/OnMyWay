package com.onmyway.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.feature.assistant.AssistantStore
import com.onmyway.app.feature.assistant.AssistantViewModel
import com.onmyway.app.feature.assistant.ChatLine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production Activity and storage; run only on an isolated emulator. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityConversationRecoveryTest : ActivityTestSupport() {
    private fun store() = AssistantStore(db, app.getSharedPreferences("assistant_conversation", 0))

    private fun activeConversation(): String = store().activeConversationId

    private fun awaitConversationSwitch(scenario: ActivityScenario<MainActivity>, phase: String,
        predicate: () -> Boolean) {
        // Switching is a UI callback with asynchronous persistence; allow Compose to process
        // that callback before checking the stored identity, rather than blocking its clock.
        compose.waitForIdle()
        scenario.onActivity { activity ->
            val chat = ViewModelProvider(activity)[AssistantViewModel::class.java]
            val vm = ViewModelProvider(activity)[AppViewModel::class.java]
            val state = "$phase: ready=${chat.ready}, busy=${chat.busy}, pending=${chat.pendingRequest != null}, " +
                "draft=${chat.proposal != null}, inputLength=${chat.input.length}, " +
                "restoringTrip=${vm.restoringTrip}, updatingTrip=${vm.updatingTrip}, planning=${vm.busy}"
            android.util.Log.i("ConversationRecoveryTest", state + ", error=" + chat.error)
            assertEquals(state, "", chat.error)
        }
        try { compose.waitUntil(timeoutMillis = 10_000, condition = predicate) }
        catch (failure: AssertionError) {
            throw AssertionError("$phase did not persist its conversation identity.\n" +
                compose.onRoot().printToString(), failure)
        }
    }

    @Test fun cancellingPrintDraftAfterRecreateKeepsParcelAndDormTrip() {
        val parcel = seedTask("取快递", "PARCEL", place = Place("parcel-stop", "快递站", 120.0, 31.0))
        val tripBefore = seedTrip(parcel)
        val draftId = "print-draft-recovery"
        runBlocking {
            store().setDraft(JSONObject().put("action", "create_task").put("title", "打印论文")
                .put("place_type", "PRINT_SHOP").put("place", printPlace.json())
                .put("_draft_id", draftId).put("_plan_id", "print-plan-recovery").put("_card_version", 1))
            assertNull(db.confirmations().latest())
        }
        launchApp()
        compose.waitUntilAtLeastOneExists(hasContentDescription("消息输入框") and isEnabled(), 10_000)
        compose.onNodeWithTag("assistant-messages").performScrollToNode(hasText("打印论文"))
        compose.onNodeWithText("打印论文").assertIsDisplayed()
        val conversationBefore = activeConversation()

        recreateActivity()

        compose.waitUntilAtLeastOneExists(hasContentDescription("消息输入框") and isEnabled(), 10_000)
        compose.onNodeWithTag("assistant-messages").performScrollToNode(hasText("取消"))
        compose.onNodeWithText("取消").performScrollTo().performClick()
        awaitDb { db.assistant().draft() == null }
        compose.waitForIdle()

        assertEquals(listOf(parcel), allTasks())
        assertEquals(tripBefore, runBlocking { db.trips().load() })
        assertEquals("宿舍", JSONObject(tripBefore.requestJson).getJSONObject("destination").getString("name"))
        assertNull(findDraft(draftId))
        assertNull(runBlocking { db.confirmations().latest() })
        assertEquals(conversationBefore, activeConversation())
        compose.onNodeWithText("待确认任务").assertDoesNotExist()

        recreateActivity()

        compose.onNodeWithText("待确认任务").assertDoesNotExist()
        assertEquals(listOf(parcel), allTasks())
        assertEquals(tripBefore, runBlocking { db.trips().load() })
    }

    @Test fun newConversationHistoryAndContinueKeepTheirIdentityAfterRecreate() {
        val parcel = seedTask("取快递", "PARCEL", place = Place("parcel-stop", "快递站", 120.0, 31.0))
        val tripBefore = seedTrip(parcel)
        val historyTitle = "取快递后回宿舍"
        val firstConversation = runBlocking {
            val history = store()
            history.load()
            history.add(ChatLine("user", historyTitle))
            history.add(ChatLine("assistant", "打印安排留在原对话里核对。"))
            history.activeConversationId
        }
        val messagesBefore = runBlocking { db.assistant().recentMessages() }
        val scenario = launchApp()
        compose.waitUntilAtLeastOneExists(hasText("路线已算好"), 10_000)
        compose.waitUntilAtLeastOneExists(hasContentDescription("消息输入框") and isEnabled(), 10_000)
        compose.onNodeWithContentDescription("新对话").performClick()
        awaitConversationSwitch(scenario, "new conversation") {
            activeConversation().isNotBlank() && activeConversation() != firstConversation
        }
        compose.waitForIdle()
        val newConversation = activeConversation()
        assertNotEquals(firstConversation, newConversation)
        assertTrue(runBlocking { store().conversation(newConversation) }.isEmpty())

        recreateActivity()

        assertEquals(newConversation, activeConversation())
        compose.onNodeWithContentDescription("消息输入框").assertIsDisplayed()
        compose.onNodeWithText(historyTitle).assertDoesNotExist()
        compose.onNodeWithContentDescription("打开历史对话").performClick()
        compose.waitUntilAtLeastOneExists(hasText(historyTitle), 10_000)
        // Drawer semantics exist while it is still sliding in. Wait for that transition,
        // then scroll the actual history item into view before touching its center.
        compose.waitForIdle()
        val historyItem = compose.onNodeWithText(historyTitle).performScrollTo().assertIsDisplayed()
        android.util.Log.i("ConversationRecoveryTest", "history item bounds=" + historyItem.fetchSemanticsNode().boundsInRoot)
        historyItem.performClick()
        compose.waitForIdle()
        try { compose.waitUntilAtLeastOneExists(hasText("历史对话 · 回看"), 10_000) }
        catch (failure: AssertionError) {
            throw AssertionError("History selection did not show its preview.\n" +
                compose.onRoot().printToString(), failure)
        }
        compose.onNodeWithText("继续这段对话").assertIsDisplayed()
        assertEquals(newConversation, activeConversation())

        recreateActivity()

        compose.onNodeWithText("历史对话 · 回看").assertIsDisplayed()
        assertEquals(newConversation, activeConversation())
        compose.onNodeWithTag("assistant-messages").performScrollToNode(hasText(historyTitle))
        compose.onNodeWithText(historyTitle).assertIsDisplayed()
        compose.onNodeWithText("继续这段对话").performClick()
        awaitConversationSwitch(scenario, "continue history") { activeConversation() == firstConversation }
        compose.waitForIdle()

        recreateActivity()

        assertEquals(firstConversation, activeConversation())
        compose.onNodeWithText("历史对话 · 回看").assertDoesNotExist()
        compose.onNodeWithText("继续这段对话").assertDoesNotExist()
        compose.onNodeWithContentDescription("消息输入框").assertIsDisplayed()
        compose.onNodeWithTag("assistant-messages").performScrollToNode(hasText(historyTitle))
        compose.onNodeWithText(historyTitle).assertIsDisplayed()
        assertEquals(messagesBefore, runBlocking { db.assistant().recentMessages() })
        assertEquals(listOf(parcel), allTasks())
        assertEquals(tripBefore, runBlocking { db.trips().load() })
        assertNull(runBlocking { db.confirmations().latest() })
    }
}
