package com.onmyway.app.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.feature.assistant.AssistantDraft
import com.onmyway.app.feature.assistant.AssistantMessage
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "backup-test-" + UUID.randomUUID() + "-"
        private val names = mutableSetOf<String>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }

        fun clean() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }

    @Test fun backupTokenIsStoredEncryptedAndCanBeDisabled() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().context)
        val sample = "test-only-token-" + "x".repeat(32)
        try {
            BackupCredentials.save(context, "http://127.0.0.1:8000", sample)
            assertEquals(sample, BackupCredentials.load(context)?.token)
            val stored = context.getSharedPreferences("pc_backup", 0).getString("token", "")
            assertFalse(stored!!.contains(sample))
            assertEquals("http://127.0.0.1:8000", BackupCredentials.load(context)?.server)
            BackupCredentials.clear(context)
            assertNull(BackupCredentials.load(context))
        } finally { context.clean() }
    }

    @Test fun snapshotRestoresRecordsAndDoesNotResumeOldNavigation() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceContext = IsolatedContext(base)
        val targetContext = IsolatedContext(base)
        val first = Room.inMemoryDatabaseBuilder(sourceContext, TaskDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(targetContext, TaskDatabase::class.java).build()
        try {
            val familiarPlaces = """[{"name":"Test parcel station"}]"""
            sourceContext.getSharedPreferences("settings", 0).edit()
                .putString("familiar_places", familiarPlaces).commit()
            sourceContext.getSharedPreferences("navigation_context", 0).edit()
                .putString("snapshot", """{"state":"NAVIGATING"}""").commit()
            first.tasks().save(Errand(id = "backup-task", title = "打印论文"))
            first.trips().save(SavedTrip(requestJson = "{}", resultJson = "{}", started = true))
            first.trips().record(RouteHistory(id = "route-1", sessionId = "session-1",
                state = "CANCELLED", timestamp = 1, requestJson = "{}", resultJson = "{}"))
            first.assistant().addMessage(AssistantMessage(id = "message-1", role = "user",
                text = "去图书馆", createdAt = 123L, routeHistoryId = "route-1"))
            first.assistant().saveDraft(AssistantDraft(action = "plan_route", destination = "图书馆",
                mode = "cycling", savedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 30 * 60_000L))
            val snapshot = BackupRepository(sourceContext, first).snapshot()
            assertEquals(14, snapshot.getInt("room_version"))
            val target = BackupRepository(targetContext, second)
            assertFalse(hasUserRecords(target.snapshot()))
            target.restore(snapshot)
            assertEquals("打印论文", second.tasks().observe().first().single().title)
            assertEquals("route-1", second.trips().history().single().id)
            assertEquals("去图书馆", second.assistant().recentMessages().single().text)
            assertEquals("route-1", second.assistant().recentMessages().single().routeHistoryId)
            assertEquals("cycling", second.assistant().draft()?.mode)
            assertFalse(second.trips().load()!!.started)
            assertTrue(second.trips().load()!!.needsReplan)
            assertEquals(familiarPlaces, targetContext.getSharedPreferences("settings", 0)
                .getString("familiar_places", null))
            assertFalse(targetContext.getSharedPreferences("navigation_context", 0).contains("snapshot"))
            try {
                target.restore(snapshot)
                fail("Restore should reject nonempty device")
            } catch (_: IllegalStateException) { }
            assertEquals(1, second.tasks().observe().first().size)
        } finally {
            first.close()
            second.close()
            sourceContext.clean()
            targetContext.clean()
        }
    }

    @Test fun restoreRejectsExistingPreferencesEvenWhenDatabaseIsEmpty() = runBlocking {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        try {
            val repository = BackupRepository(context, database)
            val emptySnapshot = repository.snapshot()
            val localPlaces = """[{"name":"My parcel station"}]"""
            context.getSharedPreferences("settings", 0).edit()
                .putString("familiar_places", localPlaces).commit()
            try {
                repository.restore(emptySnapshot)
                fail("Restore should preserve local preferences on an empty database")
            } catch (_: IllegalStateException) { }
            assertEquals(localPlaces, context.getSharedPreferences("settings", 0)
                .getString("familiar_places", null))
            assertTrue(database.tasks().observe().first().isEmpty())
        } finally {
            database.close()
            context.clean()
        }
    }
}
