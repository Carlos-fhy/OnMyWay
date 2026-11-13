package com.onmyway.app.feature.assistant

import org.json.JSONArray
import org.json.JSONObject

/** The persisted request freezes the only memory targets a later response may change. */
internal fun memoryRequestFields(snapshot: MemorySnapshot, query: String, now: Long = System.currentTimeMillis()): JSONObject =
    JSONObject().put("memory_contract", 1)
        .put("memory_snapshot_revision", snapshot.state.revision)
        .put("memory_auto_learn", snapshot.state.useMemory && snapshot.state.autoRemember)
        .put("memory_items", JSONArray().apply {
            if (snapshot.state.useMemory) selectMemories(snapshot.items, query, now).forEach { item ->
                put(JSONObject().put("id", item.id).put("text", item.text).put("revision", item.revision)
                    .put("kind", item.kind).put("scope", item.scope))
            }
        })

internal data class MemoryResponseBatch(
    val revision: Long,
    val updates: List<MemoryUpdate>,
    val references: List<AssistantMemoryReference>,
)

private fun JSONObject.wholeNumber(key: String): Long? = when (val value = opt(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

/** Untrusted auxiliary fields must never make the conversation reply fail. */
internal fun memoryResponseBatch(body: JSONObject, response: JSONObject): MemoryResponseBatch? {
    if (body.wholeNumber("memory_contract") != 1L || body.opt("memory_auto_learn") != true) return null
    val revision = body.wholeNumber("memory_snapshot_revision")?.takeIf { it >= 0 } ?: return null
    val entries = body.optJSONArray("memory_items") ?: return null
    val references = (0 until minOf(entries.length(), 12)).mapNotNull { index ->
        val entry = entries.optJSONObject(index) ?: return@mapNotNull null
        val id = (entry.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val text = (entry.opt("text") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val version = entry.wholeNumber("revision")?.takeIf { it > 0 } ?: return@mapNotNull null
        val kind = entry.opt("kind") as? String ?: return@mapNotNull null
        val scope = entry.opt("scope") as? String ?: return@mapNotNull null
        if (kind != "preference" || scope !in MEMORY_SCOPES) return@mapNotNull null
        AssistantMemoryReference(id, text, version, kind, scope)
    }
    val updates = response.optJSONArray("memory_updates")?.let { array ->
        (0 until minOf(array.length(), 20)).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            if (listOf("op", "id", "text", "quote", "kind", "scope").any { entry.has(it) && entry.opt(it) !is String })
                return@mapNotNull null
            val op = entry.optString("op")
            if (op !in setOf("add", "update", "forget") || entry.optString("quote").isBlank()) return@mapNotNull null
            MemoryUpdate(op, entry.optString("id"), entry.optString("text"), entry.optString("quote"),
                entry.optString("kind", "preference"), entry.optString("scope", "general"))
        }.take(3)
    }.orEmpty()
    return MemoryResponseBatch(revision, updates, references)
}
