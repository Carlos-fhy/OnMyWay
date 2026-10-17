package com.onmyway.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PcSyncProgressTest {
    private fun withProgress(test: (PcSyncProgress, android.content.SharedPreferences) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sync-progress-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        try { test(PcSyncProgress(prefs), prefs) } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun editDuringSyncRemainsPendingUntilSuccessorPass() = withProgress { progress, prefs ->
        progress.changed()
        val started = progress.begin()
        val successor = progress.changed()
        progress.succeeded(started, 1, false)
        assertEquals("pending", prefs.getString("sync_state", null))
        assertFalse(progress.covered(successor))
        progress.succeeded(progress.begin(), 1, false)
        assertEquals("synced", prefs.getString("sync_state", null))
        assertTrue(progress.covered(successor))
    }

    @Test fun editAfterCompletionSchedulesUncoveredGeneration() = withProgress { progress, prefs ->
        val first = progress.changed()
        progress.succeeded(progress.begin(), 1, false)
        assertTrue(progress.covered(first))
        val next = progress.changed()
        assertFalse(progress.covered(next))
        assertEquals("pending", prefs.getString("sync_state", null))
    }

    @Test fun periodicAndFailedAttemptsCannotBeSkippedAsCovered() = withProgress { progress, prefs ->
        val generation = progress.changed()
        progress.succeeded(progress.begin(), 0, true)
        assertFalse(progress.covered(-1))
        progress.failed(SyncConflict("conflict"))
        assertFalse(progress.covered(generation))
        assertEquals("conflict", prefs.getString("sync_state", null))
        assertEquals(0, prefs.getInt("task_protocol", -1))
        assertTrue(prefs.getBoolean("needs_reload", false))
    }
}
