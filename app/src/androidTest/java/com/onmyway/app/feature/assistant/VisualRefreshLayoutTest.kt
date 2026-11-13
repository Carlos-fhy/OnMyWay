package com.onmyway.app.feature.assistant

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.ui.OnMyWayTheme
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.feature.task.TaskScreen
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production components at constrained width; screenshots contain synthetic data only. */
@RunWith(AndroidJUnit4::class)
class VisualRefreshLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun render(dark: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density, fontScale)) {
                OnMyWayTheme {
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("review-root")
                        .background(MaterialTheme.colorScheme.background)) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: checkNotNull(context.getExternalFilesDir(null)).absolutePath, "visual-review")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertTextFits(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Expected a text layout for $label", layouts.isNotEmpty())
        assertFalse("Text is clipped: $label", layouts.any { it.hasVisualOverflow })
    }

    private fun assistant(dark: Boolean, scale: Float, name: String) {
        render(dark, scale) {
            var input by remember { mutableStateOf("只取快递，保留回宿舍") }
            var voice by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize()) {
                AssistantChatHeader(null, {}, {}, {})
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { ChatBubble(ChatLine("user", "取快递、打印论文后回宿舍")) }
                    item { ChatBubble(ChatLine("assistant", "请核对快递领取点和打印店。选择步行或骑行后，我会给出沿途安排，确认后再开始导航。")) }
                }
                AssistantComposer(input, { input = it }, true, false, true, voice, { voice = !voice }, "", {}, {}, {})
            }
        }
        compose.onNodeWithTag("assistant-input").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsDisplayed().assertIsEnabled()
        val root = compose.onNodeWithTag("review-root").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot
        assertTrue("Send action must remain inside the viewport", send.right <= root.right && send.bottom <= root.bottom)
        capture(name)
        compose.onNodeWithContentDescription("切换语音输入").performClick()
        capture("$name-voice")
        compose.onNodeWithContentDescription("切换文字输入").performClick()
        compose.onNodeWithTag("assistant-input").assertTextEquals("只取快递，保留回宿舍")
    }

    @Test fun assistantNarrowLight() = assistant(false, 1f, "assistant-light")
    @Test fun assistantNarrowDark() = assistant(true, 1f, "assistant-dark")
    @Test fun assistantNarrowLargeText() = assistant(false, 2f, "assistant-large")

    @Test fun taskFiltersHaveEqualHeightAndUnclippedText() {
        val tasks = listOf(
            Errand(id = "parcel", title = "取快递", placeType = "PARCEL"),
            Errand(id = "print", title = "打印论文", placeType = "PRINT_SHOP"),
            Errand(id = "later", title = "整理材料", placeType = "INDOOR", status = "deferred"),
            Errand(id = "done", title = "带好校园卡", placeType = "INDOOR", status = "done"),
        )
        render(false, 2f) { TaskScreen(tasks, {}, {}, {}, {}, { _, _ -> }, {}) }
        capture("tasks-large-before")
        val labels = listOf("待办 (2)", "暂缓 (1)", "已完成 (1)")
        labels.forEach(::assertTextFits)
        val heights = labels.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.height }
        assertTrue("Segmented filters must have equal touch heights: $heights", heights.max() - heights.min() <= 1f)
        compose.onNodeWithText("已完成 (1)").performClick()
        compose.onNodeWithText("带好校园卡").assertIsDisplayed()
        capture("tasks-large-completed")
    }
}
