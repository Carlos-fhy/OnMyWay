package com.onmyway.app.feature.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.ui.OnMyWayTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production navigation with synthetic page editors; no database or network access. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun navigation() {
        SettingsNavigation("偏好、情境与经历", "顺路预算、常用地点", "个人服务与业务同步") { page ->
            var input by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(input, { input = it }, label = { androidx.compose.material3.Text("${page.title}内容") },
                modifier = Modifier.fillMaxWidth().testTag("editor-${page.name}"))
        }
    }

    @Test fun eachDestinationIsIsolatedAndSystemBackReturnsToSettings() {
        compose.setContent { OnMyWayTheme { navigation() } }
        for (page in SettingsPage.entries.filter { it != SettingsPage.HOME }) {
            compose.onNodeWithText(page.title).performScrollTo().performClick()
            compose.onNodeWithTag("editor-${page.name}").assertIsDisplayed()
            SettingsPage.entries.filter { it != page && it != SettingsPage.HOME }.forEach {
                compose.onNodeWithTag("editor-${it.name}").assertDoesNotExist()
            }
            Espresso.pressBack()
            compose.onNodeWithContentDescription("返回设置").assertDoesNotExist()
        }
    }

    @Test fun unsubmittedPageInputSurvivesBackAndReopening() {
        compose.setContent { OnMyWayTheme { navigation() } }
        compose.onNodeWithText("出行偏好").performClick()
        compose.onNodeWithTag("editor-TRAVEL").performTextInput("取快递后回宿舍")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("个人记忆").performClick()
        compose.onNodeWithTag("editor-MEMORY").assert(hasText("取快递后回宿舍").not())
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("出行偏好").performClick()
        compose.onNodeWithTag("editor-TRAVEL").assertTextContains("取快递后回宿舍")
    }

    @Test fun restoredStateKeepsCurrentPageAndUnsubmittedInput() {
        val restore = StateRestorationTester(compose)
        restore.setContent { OnMyWayTheme { navigation() } }
        compose.onNodeWithText("数据与连接").performClick()
        compose.onNodeWithTag("editor-CONNECTION").performTextInput("尚未保存的地址")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("返回设置").assertIsDisplayed()
        compose.onNodeWithTag("editor-CONNECTION").assertTextContains("尚未保存的地址")
    }

    private fun layout(dark: Boolean, scale: Float, name: String) {
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, scale)) {
                OnMyWayTheme {
                    Surface(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("settings-root"),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground) { navigation() }
                }
            }
        }
        capture(name)
        for (label in listOf("个人记忆", "出行偏好", "数据与连接", "帮助与诊断")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertFalse("Text is clipped: $label; " + layouts.joinToString { "text=${it.layoutInput.text.text}, size=${it.size}, constraints=${it.layoutInput.constraints}, paragraphWidth=${it.multiParagraph.width}, lineRight=${it.getLineRight(0)}, lines=${it.lineCount}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}" },
                layouts.any { it.hasVisualOverflow })
        }
        compose.onNodeWithText("个人记忆").performScrollTo()
        capture(name)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)).absolutePath, "settings-review")
        assertTrue(directory.isDirectory || directory.mkdirs())
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag("settings-root").captureToImage().asAndroidBitmap()
        try { File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @Test fun lightNarrowLayout() = layout(false, 1f, "settings-light")
    @Test fun darkNarrowLayout() = layout(true, 1f, "settings-dark")
    @Test fun largeTextRemainsReadableAndReachable() = layout(false, 2f, "settings-large")
}
