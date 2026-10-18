package com.onmyway.app.data.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import com.onmyway.app.data.local.TaskDatabase
import org.json.JSONArray
import org.json.JSONObject

/** A consistent, explicitly requested snapshot of user-owned data. */
internal class BackupRepository(private val context: Context, private val database: TaskDatabase) {
    private val legacyTables = listOf("tasks", "saved_trip", "route_history", "suggestion_feedback", "agent_events")
    private val v8Tables = legacyTables + listOf("assistant_messages", "assistant_draft")
    private val v10Tables = v8Tables + listOf("planning_draft", "planning_draft_tasks")
    private val v11Tables = v10Tables + listOf("assistant_clarification", "assistant_place_choices")
    private val tables = v11Tables + "assistant_task_drafts"
    private val preferenceKeys = mapOf(
        "settings" to listOf("city", "familiar_places", "preferred_detour_minutes",
            "walking_detour_minutes", "cycling_detour_minutes", "learning_enabled"),
        "assistant_conversation" to listOf("lines", "proposal", "proposal_saved_at"),
        "navigation_context" to listOf("snapshot"),
    )

    suspend fun snapshot(): JSONObject = database.withTransaction {
        val sql = database.openHelper.writableDatabase
        val data = JSONObject()
        for (table in tables) {
            val rows = JSONArray()
            sql.query("SELECT * FROM `$table` ORDER BY id").use { cursor ->
                while (cursor.moveToNext()) rows.put(cursorRow(cursor))
            }
            data.put(table, rows)
        }
        val preferences = JSONObject()
        for ((name, keys) in preferenceKeys) {
            val source = context.getSharedPreferences(name, 0)
            val values = JSONObject()
            for (key in keys) if (source.contains(key)) {
                val value = source.all[key]
                when (value) {
                    is String, is Number, is Boolean -> values.put(key, value)
                }
            }
            preferences.put(name, values)
        }
        JSONObject().put("format", 1).put("room_version", 13)
            .put("tables", data).put("preferences", preferences)
    }

    fun validate(snapshot: JSONObject) {
        require(snapshot.optInt("format") == 1 && snapshot.optInt("room_version") in 7..13) {
            "电脑备份格式与当前版本不兼容"
        }
        val data = snapshot.getJSONObject("tables")
        val snapshotTables = when (snapshot.getInt("room_version")) { 7 -> legacyTables; 8 -> v8Tables; 9, 10 -> v10Tables; 11 -> v11Tables; else -> tables }
        require(data.keys().asSequence().toSet() == snapshotTables.toSet()) { "备份数据表不完整" }
        for (table in snapshotTables) {
            val array = data.getJSONArray(table)
            require(array.length() <= 100_000) { "备份数据量过大" }
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i)
                require(row != null) { "备份数据无效" }
                if (table == "tasks" && snapshot.getInt("room_version") >= 13) {
                    val reminderAt = row.opt("reminderAt")
                    require((reminderAt is Int || reminderAt is Long) && (reminderAt as Number).toLong() >= 0) {
                        "备份提醒时间无效"
                    }
                }
            }
        }
        val preferences = snapshot.getJSONObject("preferences")
        require(preferences.keys().asSequence().toSet() == preferenceKeys.keys) { "同步设置分类不完整" }
        for ((name, allowed) in preferenceKeys) {
            val values = preferences.getJSONObject(name)
            require(values.keys().asSequence().all { it in allowed }) { "同步设置字段无效" }
            values.keys().forEach { key ->
                val value = values.get(key)
                require(value is String || value is Number || value is Boolean) { "同步设置类型无效" }
            }
        }
    }

    /** Restore only onto an empty installation; never overwrite unsynchronized records. */
    suspend fun restore(snapshot: JSONObject) {
        val current = snapshot()
        check(!hasUserRecords(current)) { "本机已有记录；请先处理数据合并" }
        applySyncedSnapshot(snapshot, current)
    }

    /** Schema-only upgrades must not masquerade as user edits or stop an active local trip. */
    fun normalizedSchema(snapshot: JSONObject): JSONObject {
        validate(snapshot)
        val result = JSONObject(snapshot.toString()).put("room_version", 13)
        val data = result.getJSONObject("tables")
        for (table in tables) {
            val rows = data.optJSONArray(table) ?: JSONArray()
            val sorted = (0 until rows.length()).map { JSONObject(rows.getJSONObject(it).toString()) }
                .sortedBy { it.optString("id") }
            if (snapshot.getInt("room_version") < 10 && table in listOf("assistant_draft", "planning_draft"))
                sorted.forEach { it.put("draftId", "legacy-draft") }
            if (snapshot.getInt("room_version") < 11 && table in listOf("assistant_draft", "planning_draft", "saved_trip", "route_history"))
                sorted.forEach { it.put("planId", when (table) {
                    "saved_trip" -> it.optString("sessionId")
                    "route_history" -> ""
                    else -> it.optString("draftId")
                }) }
            if (snapshot.getInt("room_version") < 12 && table == "tasks")
                sorted.forEach { it.put("plannedDate", "") }
            if (snapshot.getInt("room_version") < 13 && table == "tasks")
                sorted.forEach { it.put("reminderAt", 0L) }
            data.put(table, JSONArray(sorted))
        }
        return result
    }

    fun normalizedRestore(snapshot: JSONObject): JSONObject {
        val result = normalizedSchema(snapshot)
        val trips = result.getJSONObject("tables").getJSONArray("saved_trip")
        for (index in 0 until trips.length()) trips.getJSONObject(index).put("started", 0).put("needsReplan", 1)
        // Navigation runtime state cannot resume after reinstall or on another device.
        result.getJSONObject("preferences").getJSONObject("navigation_context").remove("snapshot")
        return result
    }

    /** The expected state is persisted in SyncJournal, so interrupted restores can resume safely. */
    suspend fun applySyncedSnapshot(snapshot: JSONObject, expected: JSONObject) {
        val desired = normalizedRestore(snapshot)
        val normalizedExpected = normalizedSchema(expected)
        val desiredPreferences = desired.getJSONObject("preferences")
        val expectedPreferences = expected.getJSONObject("preferences")
        fun checkPreferences() {
            for ((name, keys) in preferenceKeys) {
                val current = context.getSharedPreferences(name, 0).all
                val before = expectedPreferences.optJSONObject(name) ?: JSONObject()
                val after = desiredPreferences.getJSONObject(name)
                for (key in keys) {
                    val value = canonicalJson(current[key])
                    check(value == canonicalJson(before.opt(key)) || value == canonicalJson(after.opt(key))) {
                        "恢复期间设置发生修改；已保留本机修改，请先处理冲突"
                    }
                }
            }
        }
        database.withTransaction {
            checkPreferences()
            val sql = database.openHelper.writableDatabase
            val data = desired.getJSONObject("tables")
            // Validate every column before touching existing rows.
            val prepared = tables.associateWith { table ->
                val allowed = mutableSetOf<String>()
                sql.query("PRAGMA table_info(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) allowed.add(cursor.getString(1))
                }
                val rows = data.getJSONArray(table)
                (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    require(row.keys().asSequence().toSet() == allowed) { "同步列与数据库结构不一致" }
                    ContentValues().apply {
                        for (column in allowed) when (val value = row.get(column)) {
                            JSONObject.NULL -> putNull(column)
                            is Int -> put(column, value)
                            is Long -> put(column, value)
                            is Double -> put(column, value)
                            is Boolean -> put(column, if (value) 1 else 0)
                            is String -> put(column, value)
                            else -> error("同步数据类型无效")
                        }
                    }
                }
            }
            val current = snapshot().getJSONObject("tables")
            if (canonicalJson(current) != canonicalJson(data)) {
                check(canonicalJson(current) == canonicalJson(normalizedExpected.getJSONObject("tables"))) {
                    "同步期间本机记录已变化；已保留本机修改，请先处理冲突"
                }
                for (table in tables) {
                    sql.execSQL("DELETE FROM `$table`")
                    for (values in prepared.getValue(table))
                        check(sql.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values) != -1L)
                }
            }
        }
        // A crash here leaves the persisted restore journal for a safe retry.
        checkPreferences()
        for ((name, keys) in preferenceKeys) {
            val values = desiredPreferences.getJSONObject(name)
            val editor = context.getSharedPreferences(name, 0).edit()
            for (key in keys) {
                when (val value = values.opt(key)) {
                    null, JSONObject.NULL -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                    is Boolean -> editor.putBoolean(key, value)
                    else -> error("同步设置类型无效")
                }
            }
            check(editor.commit()) { "设置恢复失败，将在下次同步继续恢复" }
        }
    }

    private fun cursorRow(cursor: Cursor): JSONObject = JSONObject().also { row ->
        for (i in 0 until cursor.columnCount) {
            val value: Any = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> error("Unsupported binary data in backup")
                else -> cursor.getString(i)
            }
            row.put(cursor.getColumnName(i), value)
        }
    }
}
