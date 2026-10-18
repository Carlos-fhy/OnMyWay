package com.onmyway.app.data.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.onmyway.app.core.reminder.ReminderWakeWorker
import com.onmyway.app.core.reminder.ReminderWorker
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkerCompatibilityTest {
    @Test
    fun persistedReminderWorkerClassRemainsAvailable() {
        assertLegacyWorker("com.onmyway.app.ReminderWorker")
    }

    @Test
    fun persistedReminderWakeWorkerClassRemainsAvailable() {
        assertLegacyWorker("com.onmyway.app.ReminderWakeWorker")
    }

    @Test
    fun persistedPcBackupWorkerClassRemainsAvailable() {
        assertLegacyWorker("com.onmyway.app.PcBackupWorker")
    }

    private fun assertLegacyWorker(className: String) {
        val workerClass = Class.forName(className)
        assertTrue(CoroutineWorker::class.java.isAssignableFrom(workerClass))
        val constructor = workerClass.getConstructor(Context::class.java, WorkerParameters::class.java)
        assertTrue(Modifier.isPublic(constructor.modifiers))
    }
}
