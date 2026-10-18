package com.onmyway.app.feature.assistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardSafetyUiTest {
    @get:Rule val compose = createComposeRule()
    private fun card() = JSONObject().put("action", "create_tasks")
        .put("_draft_id", "current-draft").put("_plan_id", "current-plan")
        .put("tasks", JSONArray().put(JSONObject().put("item_id", "print").put("version", 1)
            .put("title", "打印论文").put("place_type", "PRINT_SHOP")
            .put("planned_date", "2026-10-19").put("priority", 3)))

    private fun show(value: JSONObject) {
        compose.setContent {
            MaterialTheme {
                AssistantTaskDraftCard(value, true,
                    edit = { error("Unavailable card must not edit") },
                    confirm = { error("Unavailable card must not confirm") },
                    cancel = { error("Unavailable card must not cancel a current draft") })
            }
        }
    }

    private fun assertReadOnly() {
        compose.onNodeWithText("这张建议暂时无法操作").assertIsDisplayed()
        compose.onNodeWithText("全选").assertDoesNotExist()
        compose.onNodeWithText("取消剩余草稿").assertDoesNotExist()
        compose.onAllNodes(hasText("保存所选", substring = true)).assertCountEquals(0)
    }

    @Test fun unknownCardVersionHasNoExecutableControls() {
        show(card().put("_card_version", 99))
        assertReadOnly()
    }

    @Test fun unknownCardTypeHasNoExecutableControls() {
        show(card().put("action", "future_task_card"))
        assertReadOnly()
    }

    @Test fun missingDraftIdentityHasNoExecutableControls() {
        val value = card().also { it.remove("_draft_id") }
        show(value)
        assertReadOnly()
    }

    @Test fun invalidItemVersionHasNoExecutableControls() {
        val value = card()
        value.getJSONArray("tasks").getJSONObject(0).put("version", 0)
        show(value)
        assertReadOnly()
    }

    @Test fun supportedCardStillConfirmsOriginalItemVersion() {
        var selection = emptyMap<String, Int>()
        compose.setContent { MaterialTheme {
            AssistantTaskDraftCard(card().put("_card_version", 1), true, {}, { selection = it }, {})
        } }
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("保存所选 1 项").performClick()
        assertEquals(mapOf("print" to 1), selection)
    }
}
