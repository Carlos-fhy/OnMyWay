package com.onmyway.app.feature.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonalMemoryTest {
    private fun snapshot(revision: Long = 2, enabled: Boolean = true, learn: Boolean = true) = JSONObject()
        .put("store_id", "store-a").put("revision", revision)
        .put("reference_enabled", enabled).put("learning_enabled", learn)
        .put("items", JSONArray().put(JSONObject().put("id", "context-a").put("kind", "context")
            .put("text", "这周少走路").put("topic", "travel").put("scope", "personal")
            .put("scope_id", "").put("valid_until", "2026-11-14T00:00:00+08:00")
            .put("source_kind", "conversation").put("source_id", "message-a").put("quote", "这周少走路")
            .put("derived_from", JSONArray()).put("revision", 1).put("status", "active")
            .put("created_at", "2026-11-07T00:00:00Z").put("updated_at", "2026-11-07T00:00:00Z")))

    private class Cache : PersonalMemoryCache {
        var value: String? = null
        var pending: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun readPending() = pending
        override fun writePending(value: String?) { pending = value }
    }

    @Test fun personalContextRetainsScopeSourceAndExpiryAcrossSessions() {
        val value = PersonalMemorySnapshot.parse(snapshot())
        val item = value.items.single()
        assertEquals("context", item.kind)
        assertEquals("personal", item.scope)
        assertEquals("这周少走路", item.quote)
        assertEquals("2026-11-14T00:00:00+08:00", item.validUntil)
        val fields = personalMemoryRequestFields(value, "day-two", "trip-a")
        assertEquals(2, fields.getInt("memory_contract"))
        assertEquals("store-a", fields.getString("memory_store_id"))
        assertEquals("day-two", fields.getString("memory_session_id"))
        assertEquals("trip-a", fields.getString("memory_trip_id"))
        assertFalse(fields.has("memory_items"))
        assertFalse(fields.toString().contains("这周少走路"))
    }

    @Test fun missingOrDisabledSnapshotNeverEnablesRemoteLearning() {
        val missing = personalMemoryRequestFields(null, "day-two", "")
        assertFalse(missing.getBoolean("memory_enabled"))
        assertFalse(missing.getBoolean("memory_auto_learn"))
        val disabled = personalMemoryRequestFields(PersonalMemorySnapshot.parse(snapshot(enabled = false)), "day-two", "")
        assertFalse(disabled.getBoolean("memory_enabled"))
        assertFalse(disabled.getBoolean("memory_auto_learn"))
        val readOnly = personalMemoryRequestFields(PersonalMemorySnapshot.parse(snapshot(learn = false)), "day-two", "")
        assertTrue(readOnly.getBoolean("memory_enabled"))
        assertFalse(readOnly.getBoolean("memory_auto_learn"))
    }

    @Test fun malformedSnapshotCannotReplaceKnownState() {
        for (bad in listOf(snapshot().put("revision", 1.2), snapshot().put("reference_enabled", "true"),
            snapshot().put("store_id", ""))) {
            assertTrue(runCatching { PersonalMemorySnapshot.parse(bad) }.isFailure)
        }
    }

    @Test fun forgettingSourceAcceptsInvalidatedDerivedRowsWithoutDisplayingThem() {
        val body = snapshot()
        body.getJSONArray("items").getJSONObject(0).put("kind", "insight").put("source_kind", "derived")
            .put("status", "invalidated").put("text", "").put("quote", "")
        val value = PersonalMemorySnapshot.parse(body)
        assertEquals("invalidated", value.items.single().status)
        assertFalse(value.items.single().activeAt())
    }

    @Test fun cachePartitionSeparatesServersAndCredentialsWithoutStoringSecret() {
        val a = personalMemoryCacheKey("http://127.0.0.1:8000", "secret-a")
        assertNotEquals(a, personalMemoryCacheKey("http://127.0.0.1:8000", "secret-b"))
        assertNotEquals(a, personalMemoryCacheKey("https://example.com", "secret-a"))
        assertFalse(a.contains("secret"))
    }

    @Test fun staleReadCannotRestoreForgottenMemory() = runBlocking {
        val cache = Cache().apply { value = snapshot(5).put("items", JSONArray()).toString() }
        val transport = object : PersonalMemoryTransport {
            override suspend fun read() = snapshot(2)
            override suspend fun command(body: JSONObject): JSONObject = error("not called")
        }
        val client = PersonalMemoryClient(cache, transport)
        assertTrue(runCatching { client.refresh() }.isFailure)
        assertEquals(5L, client.cached()!!.revision)
        assertTrue(client.cached()!!.items.isEmpty())
    }

    @Test fun unknownWriteRetriesIdenticalOperationRatherThanCreatingAnother() = runBlocking {
        val cache = Cache()
        val sent = mutableListOf<String>()
        val transport = object : PersonalMemoryTransport {
            override suspend fun read() = snapshot()
            override suspend fun command(body: JSONObject): JSONObject {
                sent += body.toString()
                if (sent.size == 1) throw java.io.IOException("response lost")
                return JSONObject().put("status", "duplicate").put("snapshot", snapshot(3).put("items", JSONArray()))
            }
        }
        val client = PersonalMemoryClient(cache, transport)
        assertTrue(runCatching { client.mutate("forget", JSONObject().put("id", "context-a")) }.isFailure)
        assertNotNull(cache.pending)
        assertTrue(runCatching { client.mutate("settings", JSONObject().put("reference_enabled", false)) }.isFailure)
        val restored = PersonalMemoryClient(cache, transport).retryPending()
        assertEquals(sent[0], sent[1])
        assertEquals(3L, restored.revision)
        assertTrue(restored.items.isEmpty())
        assertNull(cache.pending)
    }

    @Test fun confirmedCommandRejectionDoesNotBlockNextCorrection() = runBlocking {
        val cache = Cache()
        val transport = object : PersonalMemoryTransport {
            override suspend fun read() = snapshot()
            override suspend fun command(body: JSONObject): JSONObject = throw PersonalMemoryCommandRejected("stale")
        }
        assertTrue(runCatching { PersonalMemoryClient(cache, transport).mutate("forget", JSONObject().put("id", "context-a")) }.isFailure)
        assertNull(cache.pending)
    }

    @Test fun responseEvidenceMustMatchTheFrozenStoreAndRevision() {
        val request = personalMemoryRequestFields(PersonalMemorySnapshot.parse(snapshot(3)), "day-two", "")
        fun response(store: String, revision: Long) = JSONObject().put("memory_context", JSONObject()
            .put("status", "ready").put("store_id", store).put("revision", revision)
            .put("evidence_ids", JSONArray().put("context-a")).put("items", snapshot().getJSONArray("items")))
        assertTrue(personalMemoryEvidence(request, response("other-store", 3)).isEmpty())
        assertTrue(personalMemoryEvidence(request, response("store-a", 2)).isEmpty())
        assertEquals("这周少走路", personalMemoryEvidence(request, response("store-a", 3)).single().text)
    }

    @Test fun rejectedAndPartialWritesAreReportedWithoutClaimingFullSuccess() {
        val allRejected = JSONObject().put("write_status", "applied").put("applied_ids", JSONArray()).put("rejected_count", 1)
        assertTrue(personalMemoryWriteMessage(allRejected).contains("未保存"))
        assertFalse(personalMemoryWriteMessage(allRejected).contains("已确认"))
        val partial = JSONObject(allRejected.toString()).put("applied_ids", JSONArray().put("a"))
        assertTrue(personalMemoryWriteMessage(partial).contains("已确认 1"))
        assertTrue(personalMemoryWriteMessage(partial).contains("未通过校验"))
        assertTrue(personalMemoryWriteMessage(JSONObject().put("write_status", "unavailable")).contains("未成功保存"))
    }
}
