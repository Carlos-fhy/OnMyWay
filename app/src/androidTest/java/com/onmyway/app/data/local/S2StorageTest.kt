package com.onmyway.app.data.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.sync.BackupRepository
import com.onmyway.app.data.sync.TaskAuthority
import com.onmyway.app.data.sync.canonicalJson
import com.onmyway.app.data.sync.taskCommands
import com.onmyway.app.data.sync.taskRows
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated-device tests. These databases and preferences never use the user's store. */
@RunWith(AndroidJUnit4::class)
class S2StorageTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "s2-storage-${UUID.randomUUID()}-"
        private val preferences = mutableSetOf<String>()
        val databases = mutableSetOf<String>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            preferences.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }

        fun databaseName(): String = (prefix + UUID.randomUUID() + ".db").also { databases.add(it) }
        fun clean() {
            preferences.forEach { baseContext.deleteSharedPreferences(it) }
            databases.forEach { baseContext.deleteDatabase(it) }
        }
    }

    private fun context() = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)

    // Discover migrations without referencing the new symbol, so the pre-S2 source compiles
    // and fails on observable schema behavior during the initial red run.
    private fun migrations(): Array<Migration> = Class.forName("com.onmyway.app.data.local.DatabaseMigrationsKt").methods
        .filter { it.name.startsWith("getMIGRATION_") && it.parameterCount == 0 }
        .map { it.invoke(null) as Migration }
        .sortedBy { it.startVersion }
        .toTypedArray()

    private fun open(context: Context, name: String): TaskDatabase =
        Room.databaseBuilder(context, TaskDatabase::class.java, name)
            .addMigrations(*migrations()).build()

    private fun reminder(database: TaskDatabase, id: String): Long =
        database.openHelper.writableDatabase.query(
            "SELECT reminderAt FROM tasks WHERE id = ?", arrayOf(id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    @Test fun version12UpgradePreservesScenarioTasksAndAddsUnscheduledReminder() = runBlocking {
        val context = context()
        val name = context.databaseName()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, priority INTEGER NOT NULL, serviceMinutes INTEGER NOT NULL, deadline TEXT NOT NULL, placeJson TEXT NOT NULL, placeType TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                        migrations().filter { it.endVersion <= 12 }.forEach { it.migrate(db) }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        try {
            helper.writableDatabase.apply {
                execSQL("INSERT INTO tasks (id,title,priority,serviceMinutes,deadline,placeJson,placeType,status,createdAt,updatedAt,completedAt,lastRemindedAt,reminderCount,reminderPending,plannedDate) VALUES ('parcel','取快递',2,5,'2026-10-20T18:00:00+08:00','','PARCEL','pending',100,120,0,110,2,1,'2026-10-18')")
                execSQL("INSERT INTO tasks (id,title,priority,serviceMinutes,deadline,placeJson,placeType,status,createdAt,updatedAt,completedAt,lastRemindedAt,reminderCount,reminderPending,plannedDate) VALUES ('print','打印论文',3,10,'','','PRINT_SHOP','done',101,130,125,115,1,0,'2026-10-19')")
            }
        } finally { helper.close() }
        val database = open(context, name)
        try {
            assertEquals(13, database.openHelper.writableDatabase.version)
            val parcel = database.tasks().get("parcel")!!
            assertEquals("取快递", parcel.title)
            assertEquals("2026-10-18", parcel.plannedDate)
            assertEquals("2026-10-20T18:00:00+08:00", parcel.deadline)
            assertEquals(120L, parcel.updatedAt)
            assertEquals(110L, parcel.lastRemindedAt)
            assertEquals(2, parcel.reminderCount)
            assertTrue(parcel.reminderPending)
            assertEquals(0L, reminder(database, "parcel"))
            assertEquals("done", database.tasks().get("print")!!.status)
            assertEquals(125L, database.tasks().get("print")!!.completedAt)
            assertEquals(0L, reminder(database, "print"))
        } finally { database.close(); context.clean() }
    }

    @Test fun scheduledReminderSurvivesBackupRestoreAndDatabaseReopen() = runBlocking {
        val sourceContext = context()
        val targetContext = context()
        val source = open(sourceContext, sourceContext.databaseName())
        val targetName = targetContext.databaseName()
        var target = open(targetContext, targetName)
        val reminderAt = 1_792_300_800_000L
        try {
            source.tasks().save(Errand(id = "parcel", title = "取快递", plannedDate = "2026-10-18",
                deadline = "2026-10-20T18:00:00+08:00", createdAt = 100, updatedAt = 120,
                lastRemindedAt = 110, reminderCount = 2, reminderPending = true))
            source.tasks().save(Errand(id = "print", title = "打印论文", createdAt = 101, updatedAt = 121))
            source.openHelper.writableDatabase.execSQL(
                "UPDATE tasks SET reminderAt = ? WHERE id = 'parcel'", arrayOf(reminderAt))
            val snapshot = BackupRepository(sourceContext, source).snapshot()
            assertEquals(13, snapshot.getInt("room_version"))
            assertEquals(reminderAt, taskRows(snapshot).getValue("parcel").getLong("reminderAt"))
            assertEquals(0L, taskRows(snapshot).getValue("print").getLong("reminderAt"))
            BackupRepository(targetContext, target).restore(snapshot)
            target.close()
            target = open(targetContext, targetName)
            assertEquals(reminderAt, reminder(target, "parcel"))
            assertEquals(0L, reminder(target, "print"))
            assertEquals(source.tasks().get("parcel"), target.tasks().get("parcel"))
            assertEquals("2026-10-18", target.tasks().get("parcel")!!.plannedDate)
            assertEquals("2026-10-20T18:00:00+08:00", target.tasks().get("parcel")!!.deadline)
        } finally {
            source.close(); target.close()
            sourceContext.clean(); targetContext.clean()
        }
    }

    @Test fun olderBackupsNormalizeAndRestoreWithNoInventedReminderOrTaskChanges() = runBlocking {
        val sourceContext = context()
        val source = open(sourceContext, sourceContext.databaseName())
        try {
            source.tasks().save(Errand(id = "parcel", title = "取快递", createdAt = 100, updatedAt = 120))
            val repository = BackupRepository(sourceContext, source)
            val current = repository.snapshot()
            for (version in listOf(7, 11, 12)) {
                val legacy = JSONObject(current.toString()).put("room_version", version)
                val data = legacy.getJSONObject("tables")
                if (version < 12) data.remove("assistant_task_drafts")
                if (version < 11) {
                    listOf("assistant_messages", "assistant_draft", "planning_draft", "planning_draft_tasks",
                        "assistant_clarification", "assistant_place_choices").forEach { data.remove(it) }
                }
                val row = data.getJSONArray("tasks").getJSONObject(0)
                row.remove("reminderAt")
                if (version < 12) row.remove("plannedDate")
                legacy.getJSONObject("preferences").getJSONObject("navigation_context")
                    .put("snapshot", "local runtime")
                val normalized = repository.normalizedSchema(legacy)
                assertEquals(13, normalized.getInt("room_version"))
                assertEquals(0L, taskRows(normalized).getValue("parcel").getLong("reminderAt"))
                assertEquals("local runtime", normalized.getJSONObject("preferences")
                    .getJSONObject("navigation_context").getString("snapshot"))
                assertEquals(0, taskCommands(legacy, normalized,
                    TaskAuthority(1, JSONObject().put("parcel", 4))).length())
                val targetContext = context()
                val target = open(targetContext, targetContext.databaseName())
                try {
                    BackupRepository(targetContext, target).restore(legacy)
                    assertEquals(0L, reminder(target, "parcel"))
                    assertEquals("取快递", target.tasks().get("parcel")!!.title)
                    assertEquals(120L, target.tasks().get("parcel")!!.updatedAt)
                    assertFalse(targetContext.getSharedPreferences("navigation_context", 0).contains("snapshot"))
                } finally { target.close(); targetContext.clean() }
            }
        } finally { source.close(); sourceContext.clean() }
    }

    @Test fun invalidReminderTimestampsAreRejectedBeforeRestoreChangesLocalRecords() = runBlocking {
        val context = context()
        val database = open(context, context.databaseName())
        try {
            database.tasks().save(Errand(id = "parcel", title = "保留的取快递任务", createdAt = 100, updatedAt = 120))
            context.getSharedPreferences("settings", 0).edit().putString("city", "local city").commit()
            val repository = BackupRepository(context, database)
            val before = repository.snapshot()
            val invalid = listOf<Pair<String, Any?>>(
                "missing" to null,
                "null" to JSONObject.NULL,
                "negative" to -1L,
                "integer-looking double" to 1.0,
                "fractional" to 1.5,
                "boolean" to true,
                "string" to "123",
                "overflow" to JSONObject("""{"value":9223372036854775808}""").get("value"),
            )
            for ((label, value) in invalid) {
                val malformed = JSONObject(before.toString())
                taskRows(malformed).getValue("parcel")
                    .put("title", "不应覆盖本机任务").put("reminderAt", value)
                malformed.getJSONObject("preferences").getJSONObject("settings").put("city", "untrusted city")
                try {
                    repository.validate(malformed)
                    fail("validate accepted $label reminderAt")
                } catch (_: IllegalArgumentException) { }
                try {
                    repository.applySyncedSnapshot(malformed, before)
                    fail("restore accepted $label reminderAt")
                } catch (_: IllegalArgumentException) { }
                assertEquals("restore mutated records for $label", canonicalJson(before),
                    canonicalJson(repository.snapshot()))
            }
            for (value in listOf(0L, 1L, Long.MAX_VALUE)) {
                val valid = JSONObject(before.toString())
                taskRows(valid).getValue("parcel").put("reminderAt", value)
                repository.validate(valid)
            }
        } finally { database.close(); context.clean() }
    }

    @Test fun schedulingOneTaskProducesExactlyOneVersionedCommand() = runBlocking {
        val context = context()
        val database = open(context, context.databaseName())
        try {
            database.tasks().save(Errand(id = "parcel", title = "取快递", createdAt = 100, updatedAt = 120))
            database.tasks().save(Errand(id = "print", title = "打印论文", createdAt = 101, updatedAt = 121))
            val current = BackupRepository(context, database).snapshot()
            val legacy = JSONObject(current.toString()).put("room_version", 12)
            taskRows(legacy).values.forEach { it.remove("reminderAt") }
            val edited = JSONObject(current.toString()).put("room_version", 13)
            taskRows(edited).getValue("parcel").put("reminderAt", 1_792_300_800_000L)
            taskRows(edited).getValue("print").put("reminderAt", 0L)
            val commands = taskCommands(legacy, edited,
                TaskAuthority(1, JSONObject().put("parcel", 4).put("print", 9)))
            assertEquals(1, commands.length())
            val command = commands.getJSONObject(0)
            assertEquals("parcel", command.getString("task_id"))
            assertEquals("upsert", command.getString("operation"))
            assertEquals(4, command.getInt("expected_version"))
            assertEquals(1_792_300_800_000L, command.getJSONObject("task").getLong("reminderAt"))
            assertEquals(120L, command.getJSONObject("task").getLong("updatedAt"))
        } finally { database.close(); context.clean() }
    }
}
