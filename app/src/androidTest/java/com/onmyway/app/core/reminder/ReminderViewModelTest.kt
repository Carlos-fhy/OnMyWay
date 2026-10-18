package com.onmyway.app.core.reminder

import java.util.UUID
import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderViewModelTest {
    private lateinit var app: Application
    private lateinit var db: TaskDatabase
    private lateinit var vm: AppViewModel
    private lateinit var delivery: NavigationRequestDelivery
    private val name = "reminder-vm-${UUID.randomUUID()}"
    private val preferences = mutableSetOf<String>()
    private val scheduled = mutableListOf<Errand>()

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
        vm = withContext(Dispatchers.Main) { AppViewModel(isolated, db, delivery, reminderScheduler = { scheduled += it }) }
        await { !vm.restoringTrip }
    }

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        delivery.close()
        db.close()
        app.deleteDatabase("$name-navigation.db")
        preferences.forEach(app::deleteSharedPreferences)
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(20)
    }

    @Test fun completingOrDeferringClearsAppointmentAndUnreadReminder() = runBlocking {
        for (status in listOf("done", "deferred")) {
            val task = Errand(id = status, title = "打印论文", reminderPending = true,
                reminderAt = System.currentTimeMillis() + 3_600_000L)
            db.tasks().save(task)
            withContext(Dispatchers.Main) { vm.save(task.copy(status = status)) }
            await { scheduled.any { it.id == task.id } }
            val stored = db.tasks().get(task.id)!!
            assertEquals(status, stored.status)
            assertEquals(0L, stored.reminderAt)
            assertFalse(stored.reminderPending)
            assertTrue(scheduled.last().reminderAt == 0L)
        }
    }

    @Test fun savingAStaleEditorPreservesNewerReminderState() = runBlocking {
        val task = Errand(id = "print", title = "打印论文")
        db.tasks().save(task)
        val updated = task.copy(reminderAt = System.currentTimeMillis() + 3_600_000L,
            reminderCount = 3, lastRemindedAt = 123L, reminderPending = true)
        db.tasks().save(updated)
        withContext(Dispatchers.Main) { vm.save(task.copy(title = "打印两份论文")) }
        await { scheduled.any { it.id == task.id } }
        val stored = db.tasks().get(task.id)!!
        assertEquals("打印两份论文", stored.title)
        assertEquals(updated.reminderAt, stored.reminderAt)
        assertEquals(updated.reminderCount, stored.reminderCount)
        assertEquals(updated.lastRemindedAt, stored.lastRemindedAt)
        assertTrue(stored.reminderPending)
    }

    @Test fun snoozingAndIgnoringDoNotInvalidateDormitoryRoute() = runBlocking {
        val print = Errand(id = "print", title = "打印论文", reminderPending = true)
        val parcel = Errand(id = "parcel", title = "取快递", status = "done")
        db.tasks().save(print)
        db.tasks().save(parcel)
        val route = SavedTrip(requestJson = """{"destination":{"name":"宿舍"},"tasks":[{"id":"print"}]}""", resultJson = "{}")
        db.trips().save(route)
        withContext(Dispatchers.Main) { vm.snoozeReminder(print, System.currentTimeMillis() + 3_600_000L) }
        await { scheduled.isNotEmpty() }
        assertEquals(route, db.trips().load())
        val at = db.tasks().get(print.id)!!.reminderAt
        val current = processReminders(db, at).single()
        withContext(Dispatchers.Main) { vm.dismissReminder(current) }
        await { scheduled.size == 2 }
        assertFalse(db.tasks().get(print.id)!!.reminderPending)
        assertEquals(route, db.trips().load())
        assertEquals(parcel, db.tasks().get(parcel.id))
    }
}
