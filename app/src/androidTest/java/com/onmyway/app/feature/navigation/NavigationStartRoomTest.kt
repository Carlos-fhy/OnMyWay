package com.onmyway.app.feature.navigation

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.SavedTrip
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationStartRoomTest {
    private val identity = NavigationExecutionIdentity("parcel-print-dorm-session", "parcel-print-dorm-plan", 2)
    private fun trip() = SavedTrip(
        requestJson = """{"mode":"walking","tasks":[]}""", resultJson = "{}",
        sessionId = identity.sessionId, planId = identity.planId, planVersion = identity.planVersion,
    )

    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().context, TaskDatabase::class.java,
    ).build()

    @Test fun outboxInsertFailureRollsBackTripAndHistoryThenRetryCommitsTogether() = runBlocking {
        val database = database()
        try {
            val original = trip()
            database.trips().save(original)
            val recorder = NavigationStartRecorder(RoomNavigationStartStore(database), "test-correlation")
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_start_event BEFORE INSERT ON business_event_outbox " +
                    "BEGIN SELECT RAISE(ABORT, 'injected start event failure'); END",
            )

            try {
                recorder.record(identity, 100L, attemptId = "sdk-run:1")
                fail("The injected outbox failure must abort the transaction")
            } catch (error: SQLiteException) {
                assertTrue(error.message.orEmpty().contains("injected start event failure"))
            }

            assertEquals(original, database.trips().load())
            assertTrue(database.trips().history().isEmpty())
            assertEquals(0, database.businessEvents().pendingCount())

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_start_event")
            val started = recorder.record(identity, 200L, attemptId = "sdk-run:1")

            assertEquals(original.copy(started = true, startedAt = 200L), started)
            assertEquals(started, database.trips().load())
            val history = database.trips().history().single()
            assertEquals(identity.planVersion, history.planVersion)
            assertEquals(NavigationState.NAVIGATION_OPENED.name, history.state)
            val event = database.businessEvents().pending().single()
            assertEquals("NAVIGATION_STARTED", event.kind)
            assertEquals(200L, event.occurredAt)
            val payload = JSONObject(event.payloadJson)
            assertEquals(identity.planId, payload.getString("plan_id"))
            assertEquals(identity.planVersion, payload.getInt("plan_version"))
            assertEquals(identity.sessionId, payload.getString("route_session_id"))
        } finally { database.close() }
    }

    @Test fun duplicateSdkCallbackRetainsOneHistoryAndOriginalOutboxRow() = runBlocking {
        val database = database()
        try {
            database.trips().save(trip())
            val recorder = NavigationStartRecorder(RoomNavigationStartStore(database), "test-correlation")
            val started = recorder.record(identity, 100L, attemptId = "sdk-run:1")
            val event = database.businessEvents().pending().single()
            val history = database.trips().history().single()

            assertEquals(started, recorder.record(identity, 200L, attemptId = "sdk-run:1"))

            assertEquals(started, database.trips().load())
            assertEquals(listOf(history), database.trips().history())
            assertEquals(listOf(event), database.businessEvents().pending())
        } finally { database.close() }
    }
}
