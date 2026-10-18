package com.onmyway.app.core.reminder

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderTest {
    @Test fun remindersRepeatWithoutDuplicatesAndNeverExceedPriorityCap() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, TaskDatabase::class.java).build()
        try {
            val start = 1000L
            db.tasks().save(Errand(id = "pending", title = "提醒测试", priority = 1, createdAt = start))
            db.tasks().save(Errand(id = "done", title = "已完成", status = "done", createdAt = start))
            db.tasks().save(Errand(id = "paused", title = "暂缓", status = "deferred", createdAt = start))
            assertTrue(processReminders(db, start + REMINDER_INTERVAL_MS - 1).isEmpty())
            val firstTime = start + REMINDER_INTERVAL_MS
            val concurrent = awaitAll(async { processReminders(db, firstTime) }, async { processReminders(db, firstTime) }).flatten()
            assertEquals(1, concurrent.size)
            assertEquals(2, db.tasks().get("pending")!!.priority)
            assertTrue(db.tasks().get("pending")!!.reminderPending)
            db.tasks().acknowledgeReminders()
            assertTrue(processReminders(db, firstTime + 1).isEmpty())
            processReminders(db, firstTime + REMINDER_INTERVAL_MS)
            assertEquals(3, db.tasks().get("pending")!!.priority)
            processReminders(db, firstTime + REMINDER_INTERVAL_MS * 5) // No burst catch-up on long absence.
            val task = db.tasks().get("pending")!!
            assertEquals(3, task.priority)
            assertEquals(3, task.reminderCount)
            assertEquals(start, task.createdAt)
            assertEquals(0, db.tasks().get("done")!!.reminderCount)
            assertEquals(0, db.tasks().get("paused")!!.reminderCount)
            db.tasks().save(task.copy(status = "done"))
            assertTrue(processReminders(db, firstTime + REMINDER_INTERVAL_MS * 6).isEmpty())
        } finally { db.close() }
    }
}
