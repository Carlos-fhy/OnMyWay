package com.onmyway.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/** Device-local delivery state. Conversation records and accepted drafts remain in Room. */
internal data class PendingAssistantRequest(
    val requestId: String,
    val server: String,
    val bodyJson: String,
    val userText: String,
    val userMessageId: String,
    val createdAt: Long,
    val contextKey: String,
    val resultJson: String? = null,
)

/**
 * A single outstanding request, persisted before transport starts. Keep the exact wire body so
 * retries preserve request_id, now and context across process death. This is not a JSON file or
 * a second conversation store, and is intentionally excluded from cross-device synchronization.
 */
internal class AssistantRequestJournal(
    context: Context,
    name: String = "onmyway-assistant-requests.db",
) : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE pending (
                slot INTEGER PRIMARY KEY CHECK(slot = 1),
                request_id TEXT NOT NULL,
                server TEXT NOT NULL,
                body_json TEXT NOT NULL,
                user_text TEXT NOT NULL,
                user_message_id TEXT NOT NULL,
                created_at INTEGER NOT NULL CHECK(created_at > 0),
                context_key TEXT NOT NULL,
                result_json TEXT
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported assistant request journal upgrade")

    @Synchronized
    fun pending(): PendingAssistantRequest? = readPending(readableDatabase)

    /** Never replace an unconsumed request, even when a new request has the same text. */
    @Synchronized
    fun create(request: PendingAssistantRequest) {
        validate(request)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readPending(db)
            check(current == null || current == request) { "已有待处理的助手请求，请先恢复或取消。" }
            if (current == null) {
                db.insertOrThrow("pending", null, ContentValues().apply {
                    put("slot", 1)
                    put("request_id", request.requestId)
                    put("server", request.server)
                    put("body_json", request.bodyJson)
                    put("user_text", request.userText)
                    put("user_message_id", request.userMessageId)
                    put("created_at", request.createdAt)
                    put("context_key", request.contextKey)
                    if (request.resultJson == null) putNull("result_json") else put("result_json", request.resultJson)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A response from a cancelled/older request must never be attached to a newer request. */
    @Synchronized
    fun saveResult(requestId: String, resultJson: String): Boolean {
        validateId(requestId)
        jsonObject(resultJson, "助手响应")
        return writableDatabase.update("pending", ContentValues().apply { put("result_json", resultJson) },
            "slot = 1 AND request_id = ?", arrayOf(requestId)) == 1
    }

    /** Call only after Room has durably consumed the response, or after explicit cancellation. */
    @Synchronized
    fun finish(requestId: String): Boolean {
        validateId(requestId)
        return writableDatabase.delete("pending", "slot = 1 AND request_id = ?", arrayOf(requestId)) == 1
    }

    private fun readPending(db: SQLiteDatabase): PendingAssistantRequest? = db.rawQuery(
        "SELECT request_id, server, body_json, user_text, user_message_id, created_at, context_key, result_json FROM pending WHERE slot = 1",
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else PendingAssistantRequest(
            requestId = cursor.getString(0),
            server = cursor.getString(1),
            bodyJson = cursor.getString(2),
            userText = cursor.getString(3),
            userMessageId = cursor.getString(4),
            createdAt = cursor.getLong(5),
            contextKey = cursor.getString(6),
            resultJson = if (cursor.isNull(7)) null else cursor.getString(7),
        )
    }

    private fun validate(request: PendingAssistantRequest) {
        validateId(request.requestId)
        require(request.server.length <= 2_048 && runCatching { Api.validateBase(request.server) }.isSuccess) {
            "助手请求服务地址无效。"
        }
        val body = jsonObject(request.bodyJson, "助手请求")
        require(body.opt("request_id") is String && body.optString("request_id") == request.requestId) {
            "助手请求标识与正文不一致。"
        }
        require(request.createdAt > 0) { "助手请求创建时间无效。" }
        boundedText(request.userText, 16 * 1_024, "用户输入")
        boundedText(request.userMessageId, 256, "消息标识")
        boundedText(request.contextKey, 8 * 1_024, "上下文标识")
        request.resultJson?.let { jsonObject(it, "助手响应") }
    }

    private fun validateId(value: String) {
        require(value.length == 36 && runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)) {
            "助手请求标识无效。"
        }
    }

    private fun boundedText(value: String, maxBytes: Int, field: String) {
        require(value.isNotBlank() && value.length <= maxBytes && value.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            "$field 为空或超出长度限制。"
        }
    }

    private fun jsonObject(value: String, field: String): JSONObject {
        // Both body and result live in one row; stay comfortably below CursorWindow limits.
        boundedText(value, 512 * 1_024, field)
        // Reject non-object roots and trailing content. Never put request contents in errors.
        return try {
            val parser = JSONTokener(value)
            val parsed = parser.nextValue()
            require(parsed is JSONObject && parser.nextClean() == '\u0000')
            parsed
        } catch (_: Exception) {
            throw IllegalArgumentException("$field 格式无效。")
        }
    }
}
