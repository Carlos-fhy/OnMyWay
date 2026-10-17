package com.onmyway.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AssistantRequestJournalTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var journal: AssistantRequestJournal

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        databaseName = "assistant_request_test_${UUID.randomUUID()}.db"
        journal = AssistantRequestJournal(context, databaseName)
    }

    @After fun tearDown() {
        journal.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun reopenPreservesOriginalRequestBodyAndIdentity() {
        val original = request()
        assertNull(journal.pending())
        journal.create(original)
        reopen()
        assertEquals(original, journal.pending())
        // Formatting, now and original context are deliberately not regenerated on retry.
        assertEquals(original.bodyJson, journal.pending()!!.bodyJson)
        assertEquals("2026-10-07T08:00:00+08:00", JSONObject(journal.pending()!!.bodyJson).getString("now"))
    }

    @Test fun identicalCreateIsIdempotentButChangedBodyForSameIdIsRejected() {
        val original = request()
        journal.create(original)
        journal.create(original)
        reopen()
        journal.create(original)
        val changed = JSONObject(original.bodyJson).put("now", "2026-10-08T08:00:00+08:00").toString()
        expectConflict { journal.create(original.copy(bodyJson = changed)) }
        expectConflict { journal.create(original.copy(contextKey = "different-context")) }
        expectConflict { journal.create(original.copy(server = "http://127.0.0.1:8001")) }
        assertEquals(original, journal.pending())
    }

    @Test fun newRequestCannotOverwriteUnconsumedRequest() {
        val first = request()
        journal.create(first)
        expectConflict { journal.create(request()) }
        assertEquals(first, journal.pending())
        assertTrue(journal.finish(first.requestId))
        val second = request()
        journal.create(second)
        assertEquals(second, journal.pending())
    }

    @Test fun savedResultSurvivesReopenUntilExplicitlyConsumed() {
        val first = request()
        val result = "{ \"action\": \"plan_route\", \"destination\": \"图书馆\" }"
        journal.create(first)
        assertTrue(journal.saveResult(first.requestId, result))
        reopen()
        val completed = first.copy(resultJson = result)
        assertEquals(completed, journal.pending())
        journal.create(completed)
        expectConflict { journal.create(first) }
        assertTrue(journal.finish(first.requestId))
        reopen()
        assertNull(journal.pending())
        assertFalse(journal.finish(first.requestId))
    }

    @Test fun lateResponseAndFinishCannotAffectNewRequest() {
        val first = request()
        val second = request()
        journal.create(first)
        assertTrue(journal.finish(first.requestId))
        journal.create(second)
        assertFalse(journal.saveResult(first.requestId, "{\"action\":\"reply\"}"))
        assertFalse(journal.finish(first.requestId))
        assertEquals(second, journal.pending())
        assertTrue(journal.saveResult(second.requestId, "{\"action\":\"plan_route\"}"))
        assertFalse(journal.saveResult(first.requestId, "{}"))
        assertEquals("plan_route", JSONObject(journal.pending()!!.resultJson!!).getString("action"))
    }

    @Test fun invalidBodyOrMetadataNeverCreatesPendingRequest() {
        val valid = request()
        val invalid = listOf(
            valid.copy(requestId = "1-1-1-1-1"),
            valid.copy(bodyJson = "not-json"),
            valid.copy(bodyJson = "[]"),
            valid.copy(bodyJson = "{\"request_id\":null}"),
            valid.copy(bodyJson = "{\"request_id\":42}"),
            valid.copy(bodyJson = "{\"request_id\":\"${UUID.randomUUID()}\"}"),
            valid.copy(bodyJson = valid.bodyJson + " trailing"),
            valid.copy(server = "https://user:password@example.com"),
            valid.copy(server = "https://example.com/assistant/chat"),
            valid.copy(createdAt = 0),
            valid.copy(userText = ""),
            valid.copy(userMessageId = ""),
            valid.copy(contextKey = ""),
            valid.copy(bodyJson = " ".repeat(1_024 * 1_024 + 1)),
        )
        invalid.forEach { candidate ->
            expectInvalid { journal.create(candidate) }
            assertNull(journal.pending())
        }
        journal.create(valid)
        expectInvalid { journal.saveResult(valid.requestId, "[]") }
        assertEquals(valid, journal.pending())
    }

    @Test fun concurrentWritersCannotReplaceOrQueueTwoRequests() {
        val secondConnection = AssistantRequestJournal(context, databaseName)
        val executor = Executors.newFixedThreadPool(2)
        try {
            // Open both connections before starting the racing write transactions.
            assertNull(journal.pending())
            assertNull(secondConnection.pending())
            val start = CountDownLatch(1)
            val first = request()
            val second = request()
            val futures = listOf(journal to first, secondConnection to second).map { (database, candidate) ->
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try {
                        database.create(candidate)
                        true
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
            }
            start.countDown()
            assertEquals(1, futures.count { it.get(10, TimeUnit.SECONDS) })
            val persisted = journal.pending()
            assertTrue(persisted == first || persisted == second)
            assertEquals(persisted, secondConnection.pending())
        } finally {
            executor.shutdownNow()
            secondConnection.close()
        }
    }

    private fun request(): PendingAssistantRequest {
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("request_id", id)
            .put("now", "2026-10-07T08:00:00+08:00")
            .put("route_active", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "去图书馆")))
        return PendingAssistantRequest(
            requestId = id,
            server = "http://127.0.0.1:8000",
            bodyJson = body.toString(2),
            userText = "去图书馆",
            userMessageId = UUID.randomUUID().toString(),
            createdAt = 1_791_331_200_000L,
            contextKey = "test-context-v1",
        )
    }

    private fun reopen() {
        journal.close()
        journal = AssistantRequestJournal(context, databaseName)
    }

    private fun expectConflict(block: () -> Unit) {
        try {
            block()
            fail("Expected an outstanding request conflict")
        } catch (_: IllegalStateException) {
            // No payload is included in test failure output.
        }
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected request validation to reject the value")
        } catch (_: IllegalArgumentException) {
            // No payload is included in test failure output.
        }
    }
}
