package com.onmyway.app.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.entity.ConfirmationOutbox
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanVersionPersistenceTest {
    @Test fun repeatedExecutionHistoryDoesNotIncreaseTheConfirmedVersion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "plan-version-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        var database = open()
        try {
            val trip = SavedTrip(requestJson = "{}", resultJson = "{}", sessionId = "session",
                planId = "plan", planVersion = 2)
            database.trips().save(trip)
            listOf("READY", "NAVIGATION_OPENED", "NAVIGATION_OPENED").forEachIndexed { index, state ->
                database.trips().record(RouteHistory(sessionId = trip.sessionId, planId = trip.planId,
                    planVersion = trip.planVersion, state = state, timestamp = index.toLong(),
                    requestJson = "{}", resultJson = "{}"))
            }
            assertEquals(2, database.trips().latestPlanVersion("plan"))
            assertEquals(0, database.trips().latestPlanVersion("other-plan"))
            database.trips().save(trip.copy(planVersion = 3))
            assertEquals(3, database.trips().latestPlanVersion("plan"))
            database.close()
            database = open()
            assertEquals(3, database.trips().load()!!.planVersion)
            assertEquals(3, database.trips().latestPlanVersion("plan"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun previouslyDeliveredAndRejectedReceiptsReserveTheirPlanVersions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        try {
            val dao = database.confirmations()
            dao.enqueue(ConfirmationOutbox("v4", "plan", "plan", 4, "correlation", 10,
                deliveredAt = 11))
            dao.enqueue(ConfirmationOutbox("v5", "plan", "plan", 5, "correlation", 12,
                terminalAt = 13))
            dao.enqueue(ConfirmationOutbox("proposal", "proposal", "plan", 20, "correlation", 14))
            assertEquals(5, dao.latestPlanVersion("plan"))
            assertEquals(0, dao.latestPlanVersion("other-plan"))
            assertEquals(1, dao.terminalCount())
            assertEquals("proposal", dao.latest()!!.receiptId)
        } finally { database.close() }
    }

    @Test fun migrationLeavesOldVersionUnknownAndPreservesRecordedReceiptVersion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "v16-plan-version-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, priority INTEGER NOT NULL, serviceMinutes INTEGER NOT NULL, deadline TEXT NOT NULL, placeJson TEXT NOT NULL, placeType TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                        listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                            .forEach { it.migrate(db) }
                        db.execSQL("INSERT INTO saved_trip (id,requestJson,resultJson,started,sessionId,planId) VALUES (1,'{}','{}',1,'legacy-session','legacy-plan')")
                        db.execSQL("INSERT INTO route_history (id,sessionId,planId,state,timestamp,requestJson,resultJson) VALUES ('ready','legacy-session','legacy-plan','READY',10,'{}','{}')")
                        db.execSQL("INSERT INTO route_history (id,sessionId,planId,state,timestamp,requestJson,resultJson) VALUES ('started','legacy-session','legacy-plan','NAVIGATION_OPENED',11,'{}','{}')")
                        db.execSQL("INSERT INTO confirmation_outbox (receiptId,subjectType,subjectId,subjectVersion,correlationId,confirmedAt,snapshotJson,createdAt,attempts,lastAttemptAt,lastError) VALUES ('confirmed-v4','plan','legacy-plan',4,'correlation',10,'{}',10,0,0,'')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        var database: TaskDatabase? = null
        try {
            helper.writableDatabase
            helper.close()
            val migrated = Room.databaseBuilder(context, TaskDatabase::class.java, name)
                .addMigrations(MIGRATION_16_17).build().also { database = it }
            assertEquals(17, migrated.openHelper.readableDatabase.version)
            assertEquals("legacy-plan", migrated.trips().load()!!.planId)
            assertEquals(0, migrated.trips().load()!!.planVersion)
            assertEquals(listOf(0, 0), migrated.trips().history().map { it.planVersion })
            assertEquals(0, migrated.trips().latestPlanVersion("legacy-plan"))
            assertEquals(4, migrated.confirmations().latestPlanVersion("legacy-plan"))
        } finally {
            helper.close()
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
