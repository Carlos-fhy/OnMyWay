package com.onmyway.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.core.ui.OnMyWayTheme
import com.onmyway.app.feature.assistant.PersonalMemoryItem
import com.onmyway.app.feature.assistant.PersonalMemorySnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

/** Production content with synthetic state; no database, credential or transport is used. */
@RunWith(AndroidJUnit4::class)
class MemorySettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private fun render(connected: Boolean = true) {
        val item = PersonalMemoryItem("walking", "preference", "以后出门我优先步行", "travel", "personal", "",
            null, null, "manual", "fixture", "以后出门我优先步行", emptyList(), 1, "active", "")
        val state = MemorySettingsUiState(true, false, connected, false, true, true,
            PersonalMemorySnapshot("fixture-store", 1, true, true, listOf(item), ""))
        compose.setContent {
            OnMyWayTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonalMemorySettingsContent(state, "fixture-service", { "fixture-connection" },
                        refresh = {}, retry = {}, controls = { _, _ -> },
                        save = { _, _, _, _, _, _ -> error("This interaction must not save") },
                        forget = { error("This interaction must not forget") }, importLegacy = {}, reflect = {})
                }
            }
        }
    }

    @Test fun cancelEditedMemoryRequiresDecisionAndContinueKeepsInput() {
        render()
        compose.onNodeWithText("添加").performScrollTo().performClick()
        compose.onNodeWithText("需要记住的内容").performTextInput("这周希望少走路")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("放弃未保存的编辑？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("这周希望少走路").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("放弃编辑").performClick()
        compose.onNodeWithText("需要记住的内容").assertDoesNotExist()
        compose.onNodeWithText("以后出门我优先步行").performScrollTo().assertIsDisplayed()
    }

    @Test fun selectingEmptyCategoryDoesNotHideOtherCategoriesPermanently() {
        render()
        compose.onNodeWithText("经历").performScrollTo().performClick()
        compose.onNodeWithText("这个分类下还没有记录。").assertIsDisplayed()
        compose.onNodeWithText("全部").performClick()
        compose.onNodeWithText("以后出门我优先步行").performScrollTo().assertIsDisplayed()
    }

    @Test fun offlineMemoryCanBeReadButCannotBeEditedOrForgotten() {
        render(connected = false)
        compose.onNodeWithText("添加").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("以后出门我优先步行").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("纠正").assertIsNotEnabled()
        compose.onNodeWithText("忘记").assertIsNotEnabled()
    }

    @Test fun recoveringUncertainSaveClosesItsEditorWithoutSavingAgain() {
        var state by mutableStateOf(MemorySettingsUiState(true, false, true, false, true, true,
            PersonalMemorySnapshot("fixture-store", 1, true, true, emptyList(), "")))
        var saves = 0
        var recoveries = 0
        compose.setContent {
            OnMyWayTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonalMemorySettingsContent(state, "fixture-service", { "fixture-connection" },
                        refresh = {}, controls = { _, _ -> },
                        retry = { recovered ->
                            recoveries++
                            state = state.copy(pending = false, message = "已确认上次记忆更新结果。")
                            recovered()
                        },
                        save = { _, _, _, _, _, _ ->
                            saves++
                            state = state.copy(pending = true, message = "更新结果尚未确认。")
                        }, forget = {}, importLegacy = {}, reflect = {})
                }
            }
        }
        compose.onNodeWithText("添加").performScrollTo().performClick()
        compose.onNodeWithText("需要记住的内容").performTextInput("这周希望少走路")
        compose.onNodeWithText("保存记忆").performScrollTo().performClick()
        compose.onNodeWithText("保存记忆").assertIsNotEnabled()
        compose.onNode(hasText("获取上次更新结果") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithText("需要记住的内容").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(1, recoveries) }
    }
}
