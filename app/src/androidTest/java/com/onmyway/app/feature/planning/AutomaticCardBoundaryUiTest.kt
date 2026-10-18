package com.onmyway.app.feature.planning

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The route card checks its current identity before delegating any action. */
@RunWith(AndroidJUnit4::class)
class AutomaticCardBoundaryUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: Application
    private lateinit var db: TaskDatabase
    private lateinit var delivery: NavigationRequestDelivery
    private lateinit var vm: AppViewModel
    private lateinit var scope: CoroutineScope
    private lateinit var auto: AutomaticRoutePlanner
    private val name = "automatic-card-boundary-${UUID.randomUUID()}"
    private val preferences = mutableSetOf<String>()
    private var retries = 0
    private var manualChanges = 0
    private var cancellations = 0

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

    private fun showCard(allowed: Boolean) {
        compose.setContent {
            MaterialTheme {
                AutomaticRouteCard(auto, vm,
                    retry = { retries++ }, manual = { manualChanges++ }, cancel = { cancellations++ },
                    accepted = {}, canAct = { allowed })
            }
        }
    }

    private fun clickDelegatedActions() {
        compose.onNodeWithText("重新计算").performClick()
        compose.onNodeWithText("手动调整").performClick()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun expiredCardCannotRetryChangeManuallyOrCancel() {
        showCard(allowed = false)
        clickDelegatedActions()
        compose.runOnIdle {
            assertEquals(0, retries)
            assertEquals(0, manualChanges)
            assertEquals(0, cancellations)
        }
    }

    @Test fun currentCardDelegatesEachActionOnce() {
        showCard(allowed = true)
        clickDelegatedActions()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, manualChanges)
            assertEquals(1, cancellations)
        }
    }
}
