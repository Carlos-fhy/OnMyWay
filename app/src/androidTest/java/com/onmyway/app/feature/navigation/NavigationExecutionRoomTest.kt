package com.onmyway.app.feature.navigation

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.context.ContextSnapshot
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.SavedTrip
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on an isolated emulator/test device; the JVM suite covers the state machine. */
@RunWith(AndroidJUnit4::class)
class NavigationExecutionRoomTest {
    @Test fun concurrentClaimsAndRestartPreserveOneAttemptWithoutReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "h2-execution-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        var database = open()
        val identity = NavigationExecutionIdentity("session", "plan", 1)
        val now = System.currentTimeMillis()
        try {
            val request = JSONObject().put("mode", "walking").put("tasks", JSONArray())
                .put("context_snapshot", ContextSnapshot.capture(emptyList(), null, now = now).json())
            database.trips().save(SavedTrip(requestJson = request.toString(), resultJson = "{}",
                sessionId = identity.sessionId, planId = identity.planId, planVersion = identity.planVersion))
            val recorder = NavigationExecutionRecorder(RoomNavigationExecutionStore(database), "correlation")
            val claims = List(2) { async { recorder.claim(identity, "a1", now + 1) } }.awaitAll()
            assertEquals(1, claims.count { it == NavigationClaimResult.CLAIMED })
            assertEquals(1, claims.count { it == NavigationClaimResult.ALREADY_RUNNING })
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_navigation_receipt BEFORE INSERT ON business_event_outbox " +
                    "BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END",
            )
            try { recorder.recordStarted(identity, "a1", now + 2); fail("expected rollback") }
            catch (_: SQLiteException) { }
            assertFalse(database.trips().load()!!.started)
            assertEquals("claimed", database.capabilityRuns().load(NavigationExecutionRecorder.runId(identity))!!.step)
            assertTrue(database.trips().history().isEmpty())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_navigation_receipt")
            recorder.recordStarted(identity, "a1", now + 3)
            database.close()
            database = open()
            val recovered = NavigationExecutionRecorder(RoomNavigationExecutionStore(database), "correlation")
            assertEquals("unknown", recovered.recover(identity, now + 4)!!.state)
            assertEquals(NavigationClaimResult.UNKNOWN, recovered.claim(identity, "a2", now + 5))
            assertTrue(recovered.recordArrived(identity, "a1", now + 6, null))
            assertEquals("completed", database.capabilityRuns().load(NavigationExecutionRecorder.runId(identity))!!.state)
            assertEquals(2, database.businessEvents().pendingCount())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
