package com.onmyway.app.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.memory.timeBand
import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.entity.AgentEvent
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.local.entity.SuggestionFeedback
import com.onmyway.app.feature.assistant.AssistantClarification
import com.onmyway.app.feature.assistant.toClarificationChoice
import com.onmyway.app.feature.planning.PlanningDraftTask
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

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
            fun open() = Room.databaseBuilder(context, TaskDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15).build()
            val first = open().also { database = it }
            val task = first.tasks().observe().first().single()
            assertEquals("保留的任务", task.title)
            assertEquals(15, first.openHelper.readableDatabase.version)
            assertEquals(0, first.businessEvents().pendingCount())
            assertEquals("", task.plannedDate)
            assertTrue(first.assistant().draftItems().isEmpty())
            assertEquals(task.createdAt, task.updatedAt)
            assertEquals(0L, task.lastRemindedAt)
            val trip = SavedTrip(requestJson = "{\"mode\":\"walking\"}", resultJson = "{\"stops\":[1,2]}", started = true)
            first.trips().save(trip)
            assertFalse(first.trips().load()!!.needsReplan)
            val planEvent = RouteHistory(sessionId = trip.sessionId, state = "READY", timestamp = 1000,
                requestJson = trip.requestJson, resultJson = trip.resultJson)
            first.trips().record(planEvent)
            first.tasks().save(task.copy(status = "done"))
            val feedback = SuggestionFeedback(mode = "walking", offeredTaskCount = 1, offeredExtraSeconds = 240)
            first.suggestionFeedback().show(feedback)
            assertEquals(1, first.suggestionFeedback().decide(feedback.id, "ACCEPTED", 1, 240, 1000, trip.sessionId))
            assertEquals(0, first.suggestionFeedback().decide(feedback.id, "REJECTED", 0, 0, 1001, ""))
            val agentEvent = AgentEvent(type = "SUGGESTION_ACCEPTED", source = "USER",
                correlationId = feedback.id, routeSessionId = trip.sessionId, mode = "walking")
            first.agentEvents().append(agentEvent)
            assertEquals(1, first.trips().markNeedsReplan())
            first.close()
            val reopened = open().also { database = it }
            assertEquals(trip.copy(needsReplan = true), reopened.trips().load())
            assertEquals(planEvent, reopened.trips().historyById(planEvent.id))
            assertEquals("done", reopened.tasks().observe().first().single().status)
            assertEquals("ACCEPTED", reopened.suggestionFeedback().recentDecisions().single().decision)
            assertEquals(trip.sessionId, reopened.suggestionFeedback().recentDecisions().single().routeSessionId)
            assertEquals(agentEvent, reopened.agentEvents().recent().single())
            reopened.suggestionFeedback().clear()
            reopened.agentEvents().clear()
            assertTrue(reopened.suggestionFeedback().recentDecisions().isEmpty())
            assertTrue(reopened.agentEvents().recent().isEmpty())
            reopened.trips().clear()
            assertNull(reopened.trips().load())
            assertEquals(1, reopened.tasks().observe().first().size)
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    @Test fun versionTenMigrationPreservesDraftIdentityWithoutInventingHistoryGrouping() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "v10-plan-test-" + UUID.randomUUID() + ".db"
        var database: TaskDatabase? = null
        try {
            val file = context.getDatabasePath(name)
            file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                // Frozen statements from the exported v10 schema, before any v11 columns existed.
                listOf(
                    "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `priority` INTEGER NOT NULL, `serviceMinutes` INTEGER NOT NULL, `deadline` TEXT NOT NULL, `placeJson` TEXT NOT NULL, `placeType` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, `completedAt` INTEGER NOT NULL DEFAULT 0, `lastRemindedAt` INTEGER NOT NULL DEFAULT 0, `reminderCount` INTEGER NOT NULL DEFAULT 0, `reminderPending` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `saved_trip` (`id` INTEGER NOT NULL, `requestJson` TEXT NOT NULL, `resultJson` TEXT NOT NULL, `started` INTEGER NOT NULL, `sessionId` TEXT NOT NULL DEFAULT '', `startedAt` INTEGER NOT NULL DEFAULT 0, `plannedAt` INTEGER NOT NULL DEFAULT 0, `needsReplan` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `route_history` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `state` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `requestJson` TEXT NOT NULL, `resultJson` TEXT NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `suggestion_feedback` (`id` TEXT NOT NULL, `shownAt` INTEGER NOT NULL, `mode` TEXT NOT NULL, `offeredTaskCount` INTEGER NOT NULL, `offeredExtraSeconds` INTEGER NOT NULL, `decision` TEXT NOT NULL, `selectedTaskCount` INTEGER NOT NULL, `selectedExtraSeconds` INTEGER NOT NULL, `decidedAt` INTEGER NOT NULL, `timeBand` TEXT NOT NULL DEFAULT '', `routeSessionId` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `agent_events` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `source` TEXT NOT NULL, `correlationId` TEXT NOT NULL, `routeSessionId` TEXT NOT NULL, `actionId` TEXT NOT NULL, `mode` TEXT NOT NULL, `timeBand` TEXT NOT NULL, `itemCount` INTEGER NOT NULL, `extraSeconds` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `assistant_messages` (`id` TEXT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `routeHistoryId` TEXT, `conversationId` TEXT NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE INDEX IF NOT EXISTS `index_assistant_messages_createdAt` ON `assistant_messages` (`createdAt`)",
                    "CREATE TABLE IF NOT EXISTS `assistant_draft` (`id` INTEGER NOT NULL, `action` TEXT NOT NULL, `draftId` TEXT NOT NULL DEFAULT 'legacy-draft', `title` TEXT NOT NULL, `placeType` TEXT NOT NULL, `deadline` TEXT NOT NULL, `destination` TEXT NOT NULL, `mode` TEXT NOT NULL, `knownPlaceId` TEXT NOT NULL, `knownPlaceName` TEXT NOT NULL, `knownLongitude` REAL, `knownLatitude` REAL, `knownAddress` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `planning_draft` (`id` INTEGER NOT NULL, `draftId` TEXT NOT NULL DEFAULT 'legacy-draft', `destination` TEXT NOT NULL, `mode` TEXT NOT NULL, `budget` INTEGER NOT NULL, `placeId` TEXT NOT NULL, `placeName` TEXT NOT NULL, `longitude` REAL NOT NULL, `latitude` REAL NOT NULL, `address` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    "CREATE TABLE IF NOT EXISTS `planning_draft_tasks` (`id` TEXT NOT NULL, `selected` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                ).forEach(old::execSQL)
                old.execSQL("INSERT INTO saved_trip (id,requestJson,resultJson,started,sessionId,startedAt,plannedAt,needsReplan) VALUES (1,'{}','{}',0,'existing-session',0,100,0)")
                old.execSQL("INSERT INTO route_history VALUES ('old-version','existing-session','READY',100,'{}','{}')")
                old.execSQL("INSERT INTO assistant_draft (id,action,title,placeType,deadline,destination,mode,knownPlaceId,knownPlaceName,knownLongitude,knownLatitude,knownAddress,savedAt,expiresAt,conversationId,draftId) VALUES (1,'plan_route','','','','Library','cycling','','',NULL,NULL,'',100,0,'primary','existing-draft')")
                old.execSQL("INSERT INTO planning_draft (id,destination,mode,budget,placeId,placeName,longitude,latitude,address,savedAt,draftId) VALUES (1,'Library','cycling',12,'library','Library',120,31,'',100,'existing-draft')")
                old.execSQL("INSERT INTO planning_draft_tasks VALUES ('selected-task',1)")
                old.version = 10
            }
            fun open() = Room.databaseBuilder(context, TaskDatabase::class.java, name)
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15).build()
            val migrated = open().also { database = it }
            assertEquals("existing-session", migrated.trips().load()!!.planId)
            assertEquals(15, migrated.openHelper.readableDatabase.version)
            assertTrue(migrated.assistant().draftItems().isEmpty())
            assertEquals("", migrated.trips().history().single().planId)
            assertEquals("existing-draft", migrated.assistant().draft()!!.draftId)
            assertEquals("existing-draft", migrated.assistant().draft()!!.planId)
            assertEquals("existing-draft", migrated.assistant().planningDraft()!!.planId)
            assertEquals(12, migrated.assistant().planningDraft()!!.budget)
            assertEquals(listOf(PlanningDraftTask("selected-task", true)), migrated.assistant().planningTasks())
            assertNull(migrated.assistant().clarification())
            assertTrue(migrated.assistant().placeChoices().isEmpty())
            val question = AssistantClarification(draftId = "existing-draft", planId = "existing-draft",
                kind = "DESTINATION", prompt = "Which library", destination = "Library",
                mode = "cycling", savedAt = 200)
            val choices = listOf(Place("library", "Library", 120.0, 31.0).toClarificationChoice("existing-draft", 0))
            migrated.assistant().saveClarification(question)
            migrated.assistant().savePlaceChoices(choices)
            migrated.close()
            val reopened = open().also { database = it }
            assertEquals(question, reopened.assistant().clarification())
            assertEquals(choices, reopened.assistant().placeChoices())
            assertEquals("existing-draft", reopened.assistant().planningDraft()!!.planId)
        } finally { database?.close(); context.deleteDatabase(name) }
    }
}
