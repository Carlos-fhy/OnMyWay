package com.onmyway.app.core.reminder

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderOperationsTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, TaskDatabase::class.java
    ).build()

    @Test fun appointmentSuppressesOverdueAgingUntilItsOwnTime() = runBlocking {
        val db = database()
        try {
            val now = REMINDER_INTERVAL_MS + 1_000L
            val print = Errand(id = "print", title = "打印论文", priority = 1,
                createdAt = 1_000L, plannedDate = "2026-10-18",
                deadline = "2026-10-20T18:00:00+08:00", reminderAt = now + 3_600_000L)
            db.tasks().save(print)
            assertTrue(processReminders(db, now).isEmpty())
            assertEquals(print, db.tasks().get(print.id))
        } finally { db.close() }
    }

    @Test fun appointmentFiresOnceWithoutEscalatingAndThenResumesAging() = runBlocking {
        val db = database()
        try {
            val due = REMINDER_INTERVAL_MS * 4
            val print = Errand(id = "print", title = "打印论文", priority = 1,
                createdAt = 1_000L, plannedDate = "2026-10-18",
                deadline = "2026-10-20T18:00:00+08:00", reminderAt = due)
            val parcel = Errand(id = "parcel", title = "取快递", status = "done", createdAt = 1_000L)
            db.tasks().save(print)
            db.tasks().save(parcel)
            val delivered = awaitAll(async { processReminders(db, due) }, async { processReminders(db, due) }).flatten()
            assertEquals(1, delivered.size)
            val after = db.tasks().get(print.id)!!
            assertEquals(1, after.priority)
            assertEquals(0L, after.reminderAt)
            assertEquals(1, after.reminderCount)
            assertEquals(due, after.lastRemindedAt)
            assertTrue(after.reminderPending)
            assertEquals(print.plannedDate, after.plannedDate)
            assertEquals(print.deadline, after.deadline)
            assertEquals(parcel, db.tasks().get(parcel.id))
            assertTrue(processReminders(db, due + 1).isEmpty())
            assertEquals(1, processReminders(db, due + REMINDER_INTERVAL_MS).size)
            assertEquals(2, db.tasks().get(print.id)!!.priority)
        } finally { db.close() }
    }

    @Test fun snoozeAndIgnoreOnlyChangeTargetReminderAndKeepLatestBusinessFields() = runBlocking {
        val db = database()
        try {
            val now = 1_000_000L
            val print = Errand(id = "print", title = "打印论文", priority = 2, createdAt = 100L,
                reminderPending = true, reminderCount = 1, lastRemindedAt = 1_000L,
                plannedDate = "2026-10-18", deadline = "2026-10-20T18:00:00+08:00")
            val parcel = Errand(id = "parcel", title = "取快递", status = "done", createdAt = 200L)
            db.tasks().save(print)
            db.tasks().save(parcel)
            // A reminder action must not overwrite another editor's newer title.
            db.tasks().save(print.copy(title = "打印两份论文"))
            val snoozed = snoozeTaskReminder(db, print, now + 3_600_000L, now)
            assertEquals("打印两份论文", snoozed.title)
            assertEquals(print.plannedDate, snoozed.plannedDate)
            assertEquals(print.deadline, snoozed.deadline)
            assertEquals(print.priority, snoozed.priority)
            assertEquals(print.status, snoozed.status)
            assertEquals(print.reminderCount, snoozed.reminderCount)
            assertEquals(print.lastRemindedAt, snoozed.lastRemindedAt)
            assertEquals(now + 3_600_000L, snoozed.reminderAt)
            assertFalse(snoozed.reminderPending)
            val current = processReminders(db, snoozed.reminderAt).single()
            val ignored = dismissTaskReminder(db, current, snoozed.reminderAt + 1)
            assertFalse(ignored.reminderPending)
            assertEquals(current.lastRemindedAt, ignored.lastRemindedAt)
            assertEquals(current.reminderCount, ignored.reminderCount)
            assertEquals(current.priority, ignored.priority)
            assertEquals(parcel, db.tasks().get(parcel.id))
        } finally { db.close() }
    }

    @Test fun oldReminderActionsCannotConsumeANewerReminderOrReplaceANewerAppointment() = runBlocking {
        val db = database()
        try {
            val print = Errand(id = "print", title = "打印论文", createdAt = 100L,
                reminderPending = true, reminderCount = 1, lastRemindedAt = 100L)
            db.tasks().save(print)
            val at = 1_000_000L
            snoozeTaskReminder(db, print, at, 1_000L)
            assertTrue(runCatching { snoozeTaskReminder(db, print, at + 1_000L, 1_000L) }.isFailure)
            val delivered = processReminders(db, at).single()
            assertTrue(runCatching { dismissTaskReminder(db, print, at + 1) }.isFailure)
            assertEquals(delivered, db.tasks().get(print.id))
            dismissTaskReminder(db, delivered, at + 1)
            assertTrue(runCatching { dismissTaskReminder(db, delivered, at + 2) }.isFailure)
        } finally { db.close() }
    }

    @Test fun invalidTimeClosedOrDeletedTasksDoNotMutateAnyRecords() = runBlocking {
        val db = database()
        try {
            val print = Errand(id = "print", title = "打印论文", reminderPending = true)
            db.tasks().save(print)
            assertTrue(runCatching { snoozeTaskReminder(db, print, 99, 100) }.isFailure)
            assertTrue(runCatching { snoozeTaskReminder(db, print, 100, 100) }.isFailure)
            assertEquals(print, db.tasks().get(print.id))
            val closed = print.copy(status = "deferred", reminderPending = false)
            db.tasks().save(closed)
            assertTrue(runCatching { snoozeTaskReminder(db, print, 200, 100) }.isFailure)
            assertTrue(runCatching { dismissTaskReminder(db, print, 100) }.isFailure)
            assertEquals(closed, db.tasks().get(print.id))
            db.tasks().delete(closed)
            assertTrue(runCatching { snoozeTaskReminder(db, print, 200, 100) }.isFailure)
            assertNull(db.tasks().get(print.id))
        } finally { db.close() }
    }

    @Test fun concurrentSnoozeOnlyAcceptsOneActionAndDoesNotDoubleCount() = runBlocking {
        val db = database()
        try {
            val print = Errand(id = "print", title = "打印论文", reminderPending = true)
            db.tasks().save(print)
            val results = awaitAll(
                async { runCatching { snoozeTaskReminder(db, print, 200_000, 100) } },
                async { runCatching { snoozeTaskReminder(db, print, 300_000, 100) } },
            )
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(0, db.tasks().get(print.id)!!.reminderCount)
            assertFalse(db.tasks().get(print.id)!!.reminderPending)
        } finally { db.close() }
    }

    @Test fun notificationCandidatesUseCurrentUnreadStateAndRejectAnOlderGeneration() = runBlocking {
        val db = database()
        try {
            val old = Errand(id = "print", title = "打印论文", reminderPending = true,
                lastRemindedAt = 100, reminderCount = 1)
            db.tasks().save(old.copy(status = "done", reminderPending = false))
            publishCurrentReminders(db, listOf(old)) { pending, alert ->
                assertTrue(pending.isEmpty())
                assertFalse(alert)
            }
            db.tasks().save(old.copy(lastRemindedAt = 200, reminderCount = 2))
            publishCurrentReminders(db, listOf(old)) { pending, alert ->
                assertEquals(1, pending.size)
                assertFalse(alert)
            }
            val current = db.tasks().get(old.id)!!
            publishCurrentReminders(db, listOf(current)) { pending, alert ->
                assertEquals(listOf(current), pending)
                assertTrue(alert)
            }
            dismissTaskReminder(db, current, 201)
            publishCurrentReminders(db, listOf(current)) { pending, alert ->
                assertTrue(pending.isEmpty())
                assertFalse(alert)
            }
        } finally { db.close() }
    }
}
