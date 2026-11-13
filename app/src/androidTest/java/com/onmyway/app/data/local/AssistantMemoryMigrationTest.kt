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
class AssistantMemoryMigrationTest {
    @Test fun existingMemoryKeepsContentAndDateWithoutInventingEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "memory-migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, priority INTEGER NOT NULL, serviceMinutes INTEGER NOT NULL, deadline TEXT NOT NULL, placeJson TEXT NOT NULL, placeType TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                        listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                            MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19).forEach { it.migrate(db) }
                        db.execSQL("INSERT INTO assistant_memory (id,text,createdAt) VALUES ('legacy','我通常步行',1000)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        var database: TaskDatabase? = null
        try {
            helper.writableDatabase
            helper.close()
            val migrated = Room.databaseBuilder(context, TaskDatabase::class.java, name)
                .addMigrations(MIGRATION_19_20).build().also { database = it }
            assertEquals(20, migrated.openHelper.readableDatabase.version)
            val item = migrated.assistant().memoryById("legacy")!!
            assertEquals("我通常步行", item.text)
            assertEquals(1000L, item.createdAt)
            assertEquals(1000L, item.updatedAt)
            assertEquals("legacy", item.sourceType)
            assertEquals("active", item.status)
            assertEquals(1L, item.revision)
            assertTrue(item.sourceQuote.isEmpty())
            assertTrue(item.sourceMessageId.isEmpty())
            assertEquals(0L, migrated.assistant().memoryState()!!.revision)
        } finally {
            helper.close()
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
