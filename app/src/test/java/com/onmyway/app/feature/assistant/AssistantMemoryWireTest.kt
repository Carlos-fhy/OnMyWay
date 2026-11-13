package com.onmyway.app.feature.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantMemoryWireTest {
    @Test fun disabledMemoryDoesNotSendPrivateEntriesOrAllowLearning() {
        val snapshot = MemorySnapshot(AssistantMemoryState(revision = 8, useMemory = false),
            listOf(AssistantMemory(id = "a", text = "以后打印优先校内店")))
        val fields = memoryRequestFields(snapshot, "打印论文", 10)
        assertFalse(fields.getBoolean("memory_auto_learn"))
        assertEquals(0, fields.getJSONArray("memory_items").length())
        assertFalse(fields.has("memory"))
        assertEquals(8L, fields.getLong("memory_snapshot_revision"))
    }

    @Test fun requestFreezesSelectedIdsAndRevisionWithoutSourceQuotes() {
        val item = AssistantMemory(id = "a", text = "以后打印优先校内店", revision = 3,
            scope = "printing", sourceQuote = "private provenance")
        val fields = memoryRequestFields(MemorySnapshot(AssistantMemoryState(revision = 12), listOf(item)), "打印论文", 10)
        val reference = fields.getJSONArray("memory_items").getJSONObject(0)
        assertEquals("a", reference.getString("id"))
        assertEquals(3L, reference.getLong("revision"))
        assertFalse(reference.has("sourceQuote"))
        assertEquals(12L, fields.getLong("memory_snapshot_revision"))
        val batch = checkNotNull(memoryResponseBatch(fields, JSONObject()))
        assertEquals("printing", batch.references.single().scope)
        assertEquals("preference", batch.references.single().kind)
    }

    @Test fun legacyResponsesAndMissingSnapshotNeverBecomeNewMemoryWrites() {
        val response = JSONObject().put("memory", JSONArray().put("用户喜欢步行"))
        assertNull(memoryResponseBatch(JSONObject(), response))
        assertNull(memoryResponseBatch(JSONObject().put("memory_contract", 1), response))
    }

    @Test fun malformedMemoryEntriesDoNotDiscardValidReplyOrValidNeighbor() {
        val body = JSONObject().put("memory_contract", 1).put("memory_snapshot_revision", 4)
            .put("memory_auto_learn", true).put("memory_items", JSONArray())
        val valid = JSONObject().put("op", "add").put("text", "以后优先步行")
            .put("quote", "以后优先步行").put("scope", "travel").put("kind", "preference")
        val response = JSONObject().put("reply", "可以").put("memory_updates", JSONArray()
            .put("bad").put(JSONObject().put("op", "add").put("text", 3)).put(valid))
        val batch = checkNotNull(memoryResponseBatch(body, response))
        assertEquals(1, batch.updates.size)
        assertEquals("以后优先步行", batch.updates.single().text)
        assertEquals("可以", response.getString("reply"))
        assertEquals(4L, batch.revision)
    }

    @Test fun aStringBooleanOrFractionalRevisionCannotEnableLearning() {
        val body = JSONObject().put("memory_contract", 1).put("memory_snapshot_revision", 4)
            .put("memory_auto_learn", "true")
        assertNull(memoryResponseBatch(body, JSONObject()))
        body.put("memory_auto_learn", true).put("memory_snapshot_revision", 4.5)
        assertNull(memoryResponseBatch(body, JSONObject()))
    }
}
