package com.onmyway.app.core.reminder

import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.onmyway.app.data.local.entity.Errand
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated-emulator scheduling checks. All UUID timers stay at least one day in the future. */
@RunWith(AndroidJUnit4::class)
class ReminderSchedulingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tag(task: Errand) = "task-reminder-${task.id}"
    private fun name(task: Errand) = "${tag(task)}-${task.reminderAt}"

    private fun active(manager: WorkManager, task: Errand): List<WorkInfo> =
        manager.getWorkInfosByTag(tag(task)).get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }

    private fun appointment(manager: WorkManager, task: Errand): WorkInfo {
        val active = manager.getWorkInfosForUniqueWork(name(task)).get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
        assertEquals("One future timer must remain for this appointment", 1, active.size)
        return active.single().also { assertEquals(WorkInfo.State.ENQUEUED, it.state) }
    }

    private fun withFutureTimers(block: (WorkManager, Errand, Errand) -> Unit) {
        val manager = WorkManager.getInstance(context)
        val at = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)
        val print = Errand(id = "s2-scheduling-${UUID.randomUUID()}", title = "打印论文", reminderAt = at)
        val parcel = Errand(id = "s2-scheduling-${UUID.randomUUID()}", title = "取快递", reminderAt = at)
        try {
            block(manager, print, parcel)
        } finally {
            // Cancel only this test's tags. Future wake-ups never execute against the app database.
            val cancellations = listOf(print, parcel).map { manager.cancelAllWorkByTag(tag(it)) }
            cancellations.forEach { it.result.get(10, TimeUnit.SECONDS) }
        }
    }

    @Test fun processorOldSnapshotCannotReplaceNewerUserAppointment() = withFutureTimers { manager, print, parcel ->
        scheduleTaskReminder(context, print)
        val original = appointment(manager, print)
        scheduleTaskReminder(context, parcel)
        val other = appointment(manager, parcel)

        val newer = print.copy(reminderAt = print.reminderAt + TimeUnit.DAYS.toMillis(1))
        scheduleTaskReminder(context, newer)
        val userTimer = appointment(manager, newer)
        assertNotEquals(original.id, userTimer.id)

        // A processor may finish reading the previous DB snapshot after the user saves a newer time.
        scheduleTaskReminder(context, print, replace = false)
        assertEquals(userTimer.id, appointment(manager, newer).id)
        assertTrue(active(manager, print).any { it.id == userTimer.id })
        assertEquals(setOf(other.id), active(manager, parcel).map { it.id }.toSet())

        // Re-reading the current appointment keeps its existing request too.
        scheduleTaskReminder(context, newer, replace = false)
        assertEquals(userTimer.id, appointment(manager, newer).id)
    }

    @Test fun completingTaskCancelsOnlyItsOwnTimers() = withFutureTimers { manager, print, parcel ->
        scheduleTaskReminder(context, print)
        appointment(manager, print)
        scheduleTaskReminder(context, parcel)
        val other = appointment(manager, parcel)

        scheduleTaskReminder(context, print.copy(status = "done", reminderAt = 0, reminderPending = false))

        assertTrue(active(manager, print).isEmpty())
        assertEquals(setOf(other.id), active(manager, parcel).map { it.id }.toSet())
    }

    @Test fun ignoringWithoutAppointmentCancelsOnlyItsOwnTimers() = withFutureTimers { manager, print, parcel ->
        scheduleTaskReminder(context, print)
        appointment(manager, print)
        scheduleTaskReminder(context, parcel)
        val other = appointment(manager, parcel)

        // The DB state after handling an already delivered reminder has no explicit appointment.
        scheduleTaskReminder(context, print.copy(reminderAt = 0, reminderPending = false))

        assertTrue(active(manager, print).isEmpty())
        assertEquals(setOf(other.id), active(manager, parcel).map { it.id }.toSet())
    }
}
