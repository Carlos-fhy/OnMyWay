package com.onmyway.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TripPersistenceTest {
    @Test fun migrationKeepsTasksAndTripSurvivesDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "trip-test-${UUID.randomUUID()}.db"
        var database: TaskDatabase? = null
        try {
            val file = context.getDatabasePath(name)
            file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, priority INTEGER NOT NULL, serviceMinutes INTEGER NOT NULL, deadline TEXT NOT NULL, placeJson TEXT NOT NULL, placeType TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                old.execSQL("INSERT INTO tasks VALUES ('test-task','保留的任务',2,5,'','','PHARMACY','pending',1)")
                old.version = 1
            }
            fun open() = Room.databaseBuilder(context, TaskDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
            val first = open().also { database = it }
            val task = first.tasks().observe().first().single()
            assertEquals("保留的任务", task.title)
            assertEquals(task.createdAt, task.updatedAt)
            assertEquals(0L, task.lastRemindedAt)
            val trip = SavedTrip(requestJson = "{\"mode\":\"walking\"}", resultJson = "{\"stops\":[1,2]}", started = true)
            first.trips().save(trip)
            val planEvent = RouteHistory(sessionId = trip.sessionId, state = "READY", timestamp = 1000,
                requestJson = trip.requestJson, resultJson = trip.resultJson)
            first.trips().record(planEvent)
            first.tasks().save(task.copy(status = "done"))
            val feedback = SuggestionFeedback(mode = "walking", offeredTaskCount = 1, offeredExtraSeconds = 240)
            first.suggestionFeedback().show(feedback)
            assertEquals(1, first.suggestionFeedback().decide(feedback.id, "ACCEPTED", 1, 240, 1000))
            assertEquals(0, first.suggestionFeedback().decide(feedback.id, "REJECTED", 0, 0, 1001))
            first.close()
            val reopened = open().also { database = it }
            assertEquals(trip, reopened.trips().load())
            assertEquals(planEvent, reopened.trips().historyById(planEvent.id))
            assertEquals("done", reopened.tasks().observe().first().single().status)
            assertEquals("ACCEPTED", reopened.suggestionFeedback().recentDecisions().single().decision)
            reopened.trips().clear()
            assertNull(reopened.trips().load())
            assertEquals(1, reopened.tasks().observe().first().size)
        } finally { database?.close(); context.deleteDatabase(name) }
    }
}
