package com.onmyway.app.feature.assistant

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class PersonalMemoryItem(
    val id: String,
    val kind: String,
    val text: String,
    val topic: String,
    val scope: String,
    val scopeId: String,
    val validFrom: String?,
    val validUntil: String?,
    val sourceKind: String,
    val sourceId: String,
    val quote: String,
    val derivedFrom: List<String>,
    val revision: Long,
    val status: String,
    val updatedAt: String,
) {
    val editable: Boolean get() = kind in setOf("preference", "context") && sourceKind !in setOf("event", "derived")
    fun activeAt(now: java.time.Instant = java.time.Instant.now()): Boolean = status == "active" &&
        (validFrom == null || !OffsetDateTime.parse(validFrom).toInstant().isAfter(now)) &&
        (validUntil == null || OffsetDateTime.parse(validUntil).toInstant().isAfter(now))

    companion object {
        fun parse(value: JSONObject): PersonalMemoryItem {
            fun optionalTime(key: String): String? = if (!value.has(key) || value.isNull(key)) null else
                value.strictString(key).also { OffsetDateTime.parse(it) }
            val kind = value.strictString("kind").also { require(it in setOf("preference", "context", "episode", "insight")) }
            val scope = value.strictString("scope").also { require(it in setOf("personal", "session", "trip")) }
            val source = value.strictString("source_kind").also {
                require(it in setOf("manual", "conversation", "legacy", "event", "derived"))
            }
            val status = value.strictString("status").also { require(it in setOf("active", "deleted", "invalidated", "superseded")) }
            val text = value.strictString("text", allowBlank = status != "active").also { require(it.length <= 1000) }
            val derived = value.optJSONArray("derived_from") ?: JSONArray()
            return PersonalMemoryItem(
                value.strictString("id"), kind, text, value.strictString("topic"), scope,
                value.optString("scope_id", ""), optionalTime("valid_from"), optionalTime("valid_until"),
                source, value.optString("source_id", ""), value.optString("quote", ""),
                (0 until derived.length()).map { derived.getString(it) }, value.strictLong("revision", 1), status,
                value.optString("updated_at", ""),
            )
        }
    }
}

internal data class PersonalMemorySnapshot(
    val storeId: String,
    val revision: Long,
    val referenceEnabled: Boolean,
    val learningEnabled: Boolean,
    val items: List<PersonalMemoryItem>,
    val encoded: String,
) {
    companion object {
        fun parse(value: JSONObject): PersonalMemorySnapshot {
            val items = value.getJSONArray("items")
            require(items.length() <= 5000) { "记忆返回数量异常。" }
            val parsed = (0 until items.length()).map { PersonalMemoryItem.parse(items.getJSONObject(it)) }
            require(parsed.map { it.id }.distinct().size == parsed.size) { "记忆身份重复。" }
            return PersonalMemorySnapshot(value.strictString("store_id"), value.strictLong("revision"),
                value.strictBoolean("reference_enabled"), value.strictBoolean("learning_enabled"), parsed, value.toString())
        }
    }
}

private fun JSONObject.strictString(key: String, allowBlank: Boolean = false): String =
    (get(key) as? String)?.also { require(allowBlank || it.isNotBlank()) } ?: error("记忆字段无效：$key")

private fun JSONObject.strictLong(key: String, minimum: Long = 0): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("记忆版本无效。")
}.also { require(it >= minimum) }

private fun JSONObject.strictBoolean(key: String): Boolean = get(key) as? Boolean ?: error("记忆开关无效。")

internal fun personalMemoryCacheKey(server: String, credential: String): String = MessageDigest.getInstance("SHA-256")
    .digest((server.trimEnd('/') + "\n" + credential).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal interface PersonalMemoryCache {
    fun read(): String?
    fun write(value: String)
    fun readPending(): String?
    fun writePending(value: String?)
}

internal interface PersonalMemoryTransport {
    suspend fun read(): JSONObject
    suspend fun command(body: JSONObject): JSONObject
}

internal class PersonalMemoryCommandRejected(message: String) : java.io.IOException(message)

/** Cached entries are for display. Every new chat obtains a fresh server revision before use. */
internal class PersonalMemoryClient(private val cache: PersonalMemoryCache, private val transport: PersonalMemoryTransport) {
    private val mutex = Mutex()
    fun cached(): PersonalMemorySnapshot? = cache.read()?.let { runCatching { PersonalMemorySnapshot.parse(JSONObject(it)) }.getOrNull() }
    fun hasPendingCommand(): Boolean = cache.readPending() != null

    private fun accept(value: JSONObject): PersonalMemorySnapshot {
        val incoming = PersonalMemorySnapshot.parse(value)
        val previous = cached()
        check(previous == null || previous.storeId != incoming.storeId || incoming.revision >= previous.revision) {
            "服务返回了较旧记忆，已保留当前记录。"
        }
        cache.write(incoming.encoded)
        return incoming
    }

    suspend fun refresh(): PersonalMemorySnapshot = mutex.withLock { accept(transport.read()) }

    suspend fun mutate(operation: String, fields: JSONObject = JSONObject()): PersonalMemorySnapshot = mutex.withLock {
        check(!hasPendingCommand()) { "上次记忆更新尚未确认，请先重新获取更新结果。" }
        val current = accept(transport.read())
        val body = JSONObject(fields.toString()).put("operation_id", UUID.randomUUID().toString())
            .put("store_id", current.storeId).put("expected_revision", current.revision).put("operation", operation)
        cache.writePending(body.toString())
        deliver(body)
    }

    suspend fun retryPending(): PersonalMemorySnapshot = mutex.withLock {
        val body = cache.readPending()?.let(::JSONObject) ?: return@withLock accept(transport.read())
        deliver(body)
    }

    private suspend fun deliver(body: JSONObject): PersonalMemorySnapshot = try {
        val response = transport.command(body)
        check(response.optString("status") in setOf("applied", "duplicate")) { "服务尚未确认记忆更新。" }
        val incoming = response.getJSONObject("snapshot")
        check(incoming.optString("store_id") == body.getString("store_id")) { "记忆服务身份发生变化。" }
        val snapshot = accept(incoming)
        cache.writePending(null)
        snapshot
    } catch (error: PersonalMemoryCommandRejected) {
        cache.writePending(null)
        throw error
    }
}

internal fun personalMemoryRequestFields(
    snapshot: PersonalMemorySnapshot?, sessionId: String, tripId: String,
    referenceAllowed: Boolean = true, learningAllowed: Boolean = true,
): JSONObject {
    val enabled = snapshot != null && snapshot.referenceEnabled && referenceAllowed
    return JSONObject().put("memory_contract", 2).put("memory_enabled", enabled)
        .put("memory_auto_learn", enabled && snapshot?.learningEnabled == true && learningAllowed)
        .put("memory_store_id", snapshot?.storeId.orEmpty()).put("memory_snapshot_revision", snapshot?.revision ?: 0)
        .put("memory_session_id", sessionId).put("memory_trip_id", tripId)
}

internal fun personalMemoryEvidence(request: JSONObject, response: JSONObject): List<PersonalMemoryItem> = runCatching {
    if (request.optInt("memory_contract") != 2 || request.opt("memory_enabled") != true) return emptyList()
    val context = response.getJSONObject("memory_context")
    if (context.optString("status") != "ready" || context.strictString("store_id") != request.strictString("memory_store_id") ||
        context.strictLong("revision") < request.strictLong("memory_snapshot_revision")) return emptyList()
    val ids = context.getJSONArray("evidence_ids").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
    val items = context.getJSONArray("items")
    (0 until items.length()).map { PersonalMemoryItem.parse(items.getJSONObject(it)) }.filter { it.id in ids }
}.getOrDefault(emptyList())

internal fun personalMemoryWriteMessage(context: JSONObject?): String {
    context?.optString("feedback")?.takeIf(String::isNotBlank)?.let { return it }
    val count = context?.optJSONArray("applied_ids")?.length() ?: 0
    val rejected = context?.optInt("rejected_count", 0) ?: 0
    return when (context?.optString("write_status")) {
        "applied", "duplicate", "partial" -> when {
            count > 0 -> "已确认 $count 项记忆更新。" + if (rejected > 0) "另有 $rejected 项提议未通过校验，未保存。" else ""
            rejected > 0 -> "本轮记忆提议未通过来源或范围校验，未保存。"
            else -> "本轮没有新增记忆。"
        }
        "unavailable" -> "本轮记忆更新未成功保存。"
        "stale", "conflict" -> "记忆已变化，本轮提议未写入。"
        "rejected" -> "本轮记忆提议未通过来源或范围校验，未保存。"
        "disabled" -> "自动学习已关闭。"
        else -> ""
    }
}
