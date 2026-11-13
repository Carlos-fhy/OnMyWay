package com.onmyway.app.feature.planning

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.OnMyWayTheme
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real route card at a narrow width; no location or network requests are made. */
@RunWith(AndroidJUnit4::class)
class RouteBudgetLayoutTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private lateinit var db: TaskDatabase
    private lateinit var delivery: NavigationRequestDelivery
    private lateinit var vm: AppViewModel
    private lateinit var scope: CoroutineScope
    private lateinit var auto: AutomaticRoutePlanner
    private val name = "route-budget-layout-${UUID.randomUUID()}"
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
        withContext(Dispatchers.Main) {
            vm = AppViewModel(isolated, db, delivery, reminderScheduler = {})
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            auto = AutomaticRoutePlanner(scope)
            val direct = JSONObject()
                .put("destination", Place("test-dorm", "宿舍", 116.397, 39.909).json())
                .put("mode", "walking")
                .put("total_seconds", 600)
                .put("total_meters", 750)
            // Keep the production setter private: seed only the preview used by this rendering fixture.
            AutomaticRoutePlanner::class.java.getDeclaredMethod("setPreview", JSONObject::class.java).apply {
                isAccessible = true
            }.invoke(auto, JSONObject().put("direct", direct))
        }
        withTimeout(10_000) {
            while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(25)
        }
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            scope.cancel()
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        delivery.close()
        db.close()
        app.deleteDatabase("$name-navigation.db")
        preferences.forEach(app::deleteSharedPreferences)
    }

    @Test fun budgetLabelsRemainReadableAndSelectableAt320dpWithLargerText() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                OnMyWayTheme {
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.width(320.dp).fillMaxHeight()
                                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        ) {
                            AutomaticRouteCard(auto, vm, retry = {}, manual = {}, cancel = {}, accepted = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("调整").performScrollTo().performClick()
        compose.onNodeWithText("15分钟").performScrollTo()
        compose.waitForIdle()
        saveScreenshot()

        val measured = listOf("5分钟", "10分钟", "15分钟", "20分钟", "30分钟", "自定义").map { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHasClickAction().assertIsEnabled()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing text layout for $label", layouts.isNotEmpty())
            android.util.Log.i("RouteBudgetLayout", "$label: " + layouts.joinToString { result ->
                "size=${result.size}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
                    "widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}, " +
                    "lineEnd=${result.getLineEnd(0)}, ellipsized=${result.isLineEllipsized(0)}"
            })
            label to layouts
        }
        for ((label, layouts) in measured) {
            for (layout in layouts) {
                assertEquals("Budget label must stay on one line: $label", 1, layout.lineCount)
                assertFalse("Budget label is ellipsized: $label", layout.isLineEllipsized(0))
                assertEquals("Budget label lost characters: $label", label.length, layout.getLineEnd(0, visibleEnd = true))
                // Semantics can retain a wider paragraph constraint than the measured Text.
                // Check the actual character bounds, allowing only integer-pixel rounding.
                label.indices.forEach { index ->
                    val bounds = layout.getBoundingBox(index)
                    assertTrue("Budget character is clipped: $label[$index], $bounds / ${layout.size}",
                        bounds.left >= -1f && bounds.right <= layout.size.width + 1f &&
                            bounds.top >= -1f && bounds.bottom <= layout.size.height + 1f)
                }
            }
        }
        for (minutes in listOf(5, 10, 15)) {
            compose.onNodeWithText("${minutes}分钟").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(minutes.toString(), auto.budget) }
        }
    }

    private fun saveScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: checkNotNull(app.getExternalFilesDir(null)).absolutePath, "visual-review")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val phase = if (InstrumentationRegistry.getArguments().getString("visualReviewPhase") == "after") "after" else "before"
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "route-budget-$phase.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
