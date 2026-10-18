package com.onmyway.app.data.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** SQLite delivery journal. JSON here is a queued wire request, not the business database. */
internal class SyncJournal(context: Context, name: String = "onmyway-sync.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bindings (scope TEXT PRIMARY KEY, store_id TEXT NOT NULL, revision INTEGER NOT NULL, baseline TEXT NOT NULL, task_protocol INTEGER NOT NULL DEFAULT 0, task_versions TEXT NOT NULL DEFAULT '{}')")
        db.execSQL("CREATE TABLE pending (scope TEXT PRIMARY KEY, kind TEXT NOT NULL, request_id TEXT NOT NULL, store_id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, baseline TEXT NOT NULL, task_protocol INTEGER NOT NULL DEFAULT 0, task_versions TEXT NOT NULL DEFAULT '{}', task_commands TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported sync journal upgrade" }
        for (table in listOf("bindings", "pending")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN task_protocol INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $table ADD COLUMN task_versions TEXT NOT NULL DEFAULT '{}'")
        }
        db.execSQL("ALTER TABLE pending ADD COLUMN task_commands TEXT")
    }
    data class Binding(val storeId: String, val revision: Long, val baseline: JSONObject,
        val tasks: TaskAuthority = TaskAuthority())
    data class Pending(val kind: String, val requestId: String, val storeId: String, val revision: Long,
        val payload: JSONObject, val baseline: JSONObject, val tasks: TaskAuthority = TaskAuthority(),
        val taskCommands: JSONArray? = null) {
        fun request(): JSONObject = JSONObject().put("store_id", storeId).put("base_revision", revision)
            .put("request_id", requestId).put("snapshot", payload).also {
                taskCommands?.let { commands -> it.put("task_commands", commands) }
            }
    }

    fun binding(scope: String): Binding? = readableDatabase.rawQuery(
        "SELECT store_id, revision, baseline, task_protocol, task_versions FROM bindings WHERE scope=?", arrayOf(scope)).use {
        if (it.moveToFirst()) Binding(it.getString(0), it.getLong(1), JSONObject(it.getString(2)),
            TaskAuthority(it.getInt(3), JSONObject(it.getString(4)))) else null
    }
    fun pending(scope: String): Pending? = readableDatabase.rawQuery(
        "SELECT kind, request_id, store_id, revision, payload, baseline, task_protocol, task_versions, task_commands FROM pending WHERE scope=?", arrayOf(scope)).use {
        if (it.moveToFirst()) Pending(it.getString(0), it.getString(1), it.getString(2), it.getLong(3),
            JSONObject(it.getString(4)), JSONObject(it.getString(5)), TaskAuthority(it.getInt(6), JSONObject(it.getString(7))),
            if (it.isNull(8)) null else JSONArray(it.getString(8))) else null
    }
    fun hasOtherStore(storeId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM bindings WHERE store_id != ? LIMIT 1", arrayOf(storeId)).use { it.moveToFirst() }

    fun stage(scope: String, kind: String, storeId: String, revision: Long, payload: JSONObject, baseline: JSONObject,
        tasks: TaskAuthority = TaskAuthority(), taskCommands: JSONArray? = null) {
        val values = ContentValues().apply {
            put("scope", scope); put("kind", kind); put("request_id", UUID.randomUUID().toString())
            put("store_id", storeId); put("revision", revision)
            put("payload", payload.toString()); put("baseline", baseline.toString())
            put("task_protocol", tasks.protocol); put("task_versions", tasks.versions.toString())
            taskCommands?.let { put("task_commands", it.toString()) }
        }
        check(writableDatabase.insertOrThrow("pending", null, values) != -1L)
    }
    /** Only used after proving that an old pending request's original base is still current. */
    fun upgradePending(scope: String, requestId: String, tasks: TaskAuthority, commands: JSONArray) {
        check(writableDatabase.update("pending", ContentValues().apply {
            put("task_protocol", tasks.protocol); put("task_versions", tasks.versions.toString())
            put("task_commands", commands.toString())
        }, "scope=? AND request_id=? AND task_commands IS NULL", arrayOf(scope, requestId)) == 1)
    }

    fun accept(scope: String, storeId: String, revision: Long, baseline: JSONObject,
        tasks: TaskAuthority = TaskAuthority()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("bindings", null, ContentValues().apply {
                put("scope", scope); put("store_id", storeId); put("revision", revision); put("baseline", baseline.toString())
                put("task_protocol", tasks.protocol); put("task_versions", tasks.versions.toString())
            }.also { db.delete("bindings", "scope=?", arrayOf(scope)) })
            db.delete("pending", "scope=?", arrayOf(scope))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

internal fun syncScope(server: String, token: String): String =
    MessageDigest.getInstance("SHA-256").digest((server + "\n" + token).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun canonicalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    // JSON/SQLite round trips may turn 120.0 into 120; that is not a user edit.
    // Keep exact decimal values, including large Long IDs, without converting to Double.
    is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    else -> value.toString()
}

internal fun hasUserRecords(snapshot: JSONObject): Boolean {
    val tables = snapshot.getJSONObject("tables")
    if (tables.keys().asSequence().any { tables.getJSONArray(it).length() > 0 }) return true
    val preferences = snapshot.getJSONObject("preferences")
    for ((namespace, key) in listOf("settings" to "familiar_places",
        "assistant_conversation" to "lines", "assistant_conversation" to "proposal",
        "navigation_context" to "snapshot")) {
        if (preferences.optJSONObject(namespace)?.optString(key).orEmpty() !in listOf("", "[]", "{}", "null")) return true
    }
    return false
}
