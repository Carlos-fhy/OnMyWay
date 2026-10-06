package com.onmyway.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** SQLite delivery journal. JSON here is a queued wire request, not the business database. */
internal class SyncJournal(context: Context, name: String = "onmyway-sync.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bindings (scope TEXT PRIMARY KEY, store_id TEXT NOT NULL, revision INTEGER NOT NULL, baseline TEXT NOT NULL)")
        db.execSQL("CREATE TABLE pending (scope TEXT PRIMARY KEY, kind TEXT NOT NULL, request_id TEXT NOT NULL, store_id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, baseline TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported sync journal upgrade")
    data class Binding(val storeId: String, val revision: Long, val baseline: JSONObject)
    data class Pending(val kind: String, val requestId: String, val storeId: String, val revision: Long,
        val payload: JSONObject, val baseline: JSONObject)

    fun binding(scope: String): Binding? = readableDatabase.rawQuery(
        "SELECT store_id, revision, baseline FROM bindings WHERE scope=?", arrayOf(scope)).use {
        if (it.moveToFirst()) Binding(it.getString(0), it.getLong(1), JSONObject(it.getString(2))) else null
    }
    fun pending(scope: String): Pending? = readableDatabase.rawQuery(
        "SELECT kind, request_id, store_id, revision, payload, baseline FROM pending WHERE scope=?", arrayOf(scope)).use {
        if (it.moveToFirst()) Pending(it.getString(0), it.getString(1), it.getString(2), it.getLong(3),
            JSONObject(it.getString(4)), JSONObject(it.getString(5))) else null
    }
    fun hasOtherStore(storeId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM bindings WHERE store_id != ? LIMIT 1", arrayOf(storeId)).use { it.moveToFirst() }

    fun stage(scope: String, kind: String, storeId: String, revision: Long, payload: JSONObject, baseline: JSONObject) {
        val values = ContentValues().apply {
            put("scope", scope); put("kind", kind); put("request_id", UUID.randomUUID().toString())
            put("store_id", storeId); put("revision", revision)
            put("payload", payload.toString()); put("baseline", baseline.toString())
        }
        check(writableDatabase.insertOrThrow("pending", null, values) != -1L)
    }
    fun accept(scope: String, storeId: String, revision: Long, baseline: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("bindings", null, ContentValues().apply {
                put("scope", scope); put("store_id", storeId); put("revision", revision); put("baseline", baseline.toString())
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
