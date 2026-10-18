package com.onmyway.app.feature.assistant

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.state.NavigationEndEvent
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.remote.AssistantRequestTransport
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import com.onmyway.app.feature.navigation.NavigationRequestTransport
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated check of the end-of-navigation confirmation card and summary; never contacts a service. */
@RunWith(AndroidJUnit4::class)
class TripCompletionFlowTest {
    private lateinit var app: Application
    private lateinit var isolated: Application
    private lateinit var db: TaskDatabase
    private lateinit var vm: AppViewModel
    private lateinit var chat: AssistantViewModel
    private val name = "trip-completion-${UUID.randomUUID()}"
    private val preferences = mutableSetOf<String>()

    private val offlineTransport = object : AssistantRequestTransport {
        override suspend fun submit(server: String, body: JSONObject): JSONObject =
            error("This trip completion test must not contact the assistant service")
        override suspend fun status(server: String, body: JSONObject): JSONObject =
            error("This trip completion test must not contact the assistant service")
    }

    @Before fun setup() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        isolated = object : Application() {
            init { attachBaseContext(app) }
            override fun getSharedPreferences(key: String, mode: Int): android.content.SharedPreferences {
                val unique = "$name-$key"
                preferences += unique
                return app.getSharedPreferences(unique, mode)
            }
        }
        db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val delivery = NavigationRequestDelivery(app, "$name-navigation.db",
            object : NavigationRequestTransport {
                override suspend fun submit(server: String, body: JSONObject): JSONObject =
                    error("This trip completion test must not request navigation")
                override suspend fun status(server: String, body: JSONObject): JSONObject =
                    error("This trip completion test must not recover navigation")
            })
        withContext(Dispatchers.Main) {
            vm = AppViewModel(isolated, db, delivery, reminderScheduler = {})
            chat = AssistantViewModel(isolated, isolated.getSharedPreferences("assistant_conversation", 0),
                db, offlineTransport, "$name-requests.db")
        }
        awaitState { chat.ready && !vm.restoringTrip }
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            if (::chat.isInitialized) chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            if (::vm.isInitialized) vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::db.isInitialized) db.close()
        if (::app.isInitialized) {
            app.deleteDatabase("$name-navigation.db")
            app.deleteDatabase("$name-requests.db")
            preferences.forEach(app::deleteSharedPreferences)
        }
    }

    @Test fun arrivalWaitsForConfirmationThenSummarizesCompletedAndPending() = runBlocking {
        val parcel = Errand(id = "parcel", title = "取快递", status = "pending")
        val print = Errand(id = "print", title = "打印论文", status = "pending")
        val dorm = Errand(id = "dorm", title = "回宿舍", status = "done")
        listOf(parcel, print, dorm).forEach { db.tasks().save(it) }
        val event = NavigationEndEvent("session-1:ARRIVED", true, "宿舍", listOf("parcel", "print", "dorm"))
        val tasks = listOf(parcel, print, dorm)

        withContext(Dispatchers.Main) { chat.routeEnded(event, tasks) }

        // The card comes first; no ending bubble is written before confirmation.
        assertTrue(chat.lines.none { it.id == "route-end:session-1:ARRIVED" })
        val prompt = chat.tripCompletion!!
        assertEquals("宿舍", prompt.destination)
        assertEquals(listOf("parcel", "print", "dorm"), prompt.tasks.map { it.id })
        assertEquals(listOf(false, false, true), prompt.tasks.map { it.done })

        // Re-entering the terminal state must not stack another card or bubble.
        withContext(Dispatchers.Main) { chat.routeEnded(event, tasks) }
        assertTrue(chat.lines.none { it.id == "route-end:session-1:ARRIVED" })

        // Keep pickup checked, leave printing unchecked, and uncheck the already-done dorm task.
        withContext(Dispatchers.Main) { chat.confirmTripCompletion(vm, setOf("parcel")) }

        awaitState { chat.tripCompletion == null }
        assertEquals("done", db.tasks().get("parcel")?.status)
        assertEquals("pending", db.tasks().get("print")?.status)
        assertEquals("pending", db.tasks().get("dorm")?.status)
        assertEquals(1, chat.lines.count { it.id == "route-end:session-1:ARRIVED" })
        val summary = chat.lines.single { it.id == "route-end:session-1:ARRIVED" }
        assertTrue(summary.text.contains("已完成：「取快递」"))
        assertTrue(summary.text.contains("未完成：「打印论文」、「回宿舍」"))
        assertTrue(summary.text.contains("继续留在待办中"))
    }

    @Test fun dismissingEmitsUnconfirmedSummaryWithoutChangingTasks() = runBlocking {
        val parcel = Errand(id = "parcel", title = "取快递", status = "pending")
        val dorm = Errand(id = "dorm", title = "回宿舍", status = "done")
        listOf(parcel, dorm).forEach { db.tasks().save(it) }
        val event = NavigationEndEvent("session-2:CANCELLED", false, "宿舍", listOf("parcel", "dorm"))

        withContext(Dispatchers.Main) { chat.routeEnded(event, listOf(parcel, dorm)) }
        assertEquals(listOf("parcel", "dorm"), chat.tripCompletion?.tasks?.map { it.id })

        withContext(Dispatchers.Main) { chat.dismissTripCompletion() }

        assertNull(chat.tripCompletion)
        assertEquals("pending", db.tasks().get("parcel")?.status)
        assertEquals("done", db.tasks().get("dorm")?.status)
        val summary = chat.lines.last { it.role == "assistant" }
        assertTrue(summary.text.contains("待办状态未更新"))
        assertTrue(summary.text.contains("已完成：「回宿舍」"))
        assertTrue(summary.text.contains("未完成：「取快递」"))
    }

    @Test fun finishedTripSummarizesImmediately() = runBlocking {
        val parcel = Errand(id = "parcel", title = "取快递", status = "done")
        db.tasks().save(parcel)
        val event = NavigationEndEvent("session-3:ARRIVED", true, "宿舍", listOf("parcel"))

        withContext(Dispatchers.Main) { chat.routeEnded(event, listOf(parcel)) }

        assertNull(chat.tripCompletion)
        val summary = chat.lines.single { it.id == "route-end:session-3:ARRIVED" }
        assertTrue(summary.text.contains("已完成：「取快递」"))
        assertTrue(summary.text.contains("没有未完成事项"))
        assertFalse(summary.text.contains("继续留在待办中"))
    }

    @Test fun tripWithoutTasksStillEndsWithASummary() = runBlocking {
        val event = NavigationEndEvent("session-4:ARRIVED", true, "宿舍", emptyList())

        withContext(Dispatchers.Main) { chat.routeEnded(event, emptyList()) }

        assertNull(chat.tripCompletion)
        val summary = chat.lines.single { it.id == "route-end:session-4:ARRIVED" }
        assertTrue(summary.text.contains("本次没有顺路待办"))
    }

    private suspend fun awaitState(predicate: () -> Boolean) {
        withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { predicate() }) delay(25)
        }
    }
}
