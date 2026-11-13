package com.onmyway.app.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityRunMigrationTest {
    @Test fun migrationPreservesTasksAndPlanWithoutInventingExecutionEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "h2-migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, priority INTEGER NOT NULL, serviceMinutes INTEGER NOT NULL, deadline TEXT NOT NULL, placeJson TEXT NOT NULL, placeType TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                        listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                            MIGRATION_16_17).forEach { it.migrate(db) }
                        db.execSQL("INSERT INTO tasks (id,title,priority,serviceMinutes,deadline,placeJson,placeType,status,createdAt) VALUES ('parcel','取快递',2,5,'','','INDOOR','pending',1000)")
                        db.execSQL("INSERT INTO saved_trip (id,requestJson,resultJson,started,sessionId,planId,planVersion) VALUES (1,'{}','{}',1,'session','plan',2)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        var database: TaskDatabase? = null
        try {
            helper.writableDatabase
            helper.close()
            val migrated = Room.databaseBuilder(context, TaskDatabase::class.java, name)
                .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20).build().also { database = it }
            assertEquals(20, migrated.openHelper.readableDatabase.version)
            assertEquals("取快递", migrated.tasks().all().single().title)
            assertEquals(2, migrated.trips().load()!!.planVersion)
            assertTrue(migrated.trips().load()!!.started)
            assertTrue(migrated.capabilityRuns().forSession("session").isEmpty())
        } finally {
            helper.close()
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
