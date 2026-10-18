package com.onmyway.app.feature.navigation

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.AssistantRequestJournal
import com.onmyway.app.data.local.PendingAssistantRequest
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationRequestDeliveryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var transport: FakeTransport
    private lateinit var delivery: NavigationRequestDelivery

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        databaseName = "navigation_delivery_test_${UUID.randomUUID()}.db"
        transport = FakeTransport()
        delivery = NavigationRequestDelivery(context, databaseName, transport)
    }

    @After fun tearDown() {
        delivery.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun retryChecksStatusAndReusesThePersistedBodyAndId() = runBlocking {
        val original = request()
        var attempts = 0
        transport.onSubmit = { _, _ ->
            assertEquals(original, delivery.pending())
            if (++attempts == 1) throw IOException("Response lost")
            proposal()
        }
        try {
            delivery.submit(original)
            fail("The first attempt should lose its response")
        } catch (_: IOException) {
            assertEquals(original, delivery.pending())
        }

        reopen()
        val result = delivery.submit(original)
        assertTrue(result is NavigationDeliveryResult.Ready)
        assertEquals(listOf("submit", "status", "submit"), transport.calls.map { it.operation })
        transport.calls.forEach { call ->
            assertEquals(original.server, call.server)
            assertEquals(JSONObject(original.bodyJson).toString(), call.bodyJson)
            assertEquals(original.requestId, JSONObject(call.bodyJson).getString("request_id"))
        }
        assertEquals(original.bodyJson, delivery.pending()!!.bodyJson)
        assertEquals(proposal().toString(), delivery.pending()!!.resultJson)
    }

    @Test fun completedServerStatusRecoversWithoutSubmittingAgain() = runBlocking {
        val original = request()
        delivery.create(original)
        reopen()
        transport.onStatus = { _, body -> status(body, "completed").put("result", proposal()) }

        val result = delivery.obtain(original) as NavigationDeliveryResult.Ready
        assertEquals(proposal().toString(), result.result.toString())
        assertEquals(listOf("status"), transport.calls.map { it.operation })
        assertEquals(proposal().toString(), delivery.pending()!!.resultJson)
    }

    @Test fun pendingServerStatesNeverSubmitAgain() = runBlocking {
        val original = request()
        delivery.create(original)
        for (state in listOf("running", "failed", "unresolved")) {
            transport.onStatus = { _, body -> status(body, state).put("error_status", 502) }
            assertEquals(NavigationDeliveryResult.Waiting(state, 502), delivery.obtain(original))
            assertEquals(original, delivery.pending())
        }
        assertEquals(listOf("status", "status", "status"), transport.calls.map { it.operation })
    }

    @Test fun localResultSurvivesReopenAndDoesNotContactTheServer() = runBlocking {
        val original = request()
        delivery.create(original)
        assertTrue(delivery.storeResult(original.requestId, proposal()))
        reopen()

        val recovered = delivery.obtain(original) as NavigationDeliveryResult.Ready
        assertEquals(proposal().toString(), recovered.result.toString())
        assertTrue(delivery.submit(original) is NavigationDeliveryResult.Ready)
        assertTrue(transport.calls.isEmpty())
        assertEquals(original.requestId, delivery.pending()!!.requestId)
    }

    @Test fun lateDeliveryCannotOverwriteANewRequestOrBlockContextSaving() = runBlocking {
        val original = request()
        val replacement = request()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<JSONObject>()
        transport.onSubmit = { _, _ ->
            entered.complete(Unit)
            response.await()
        }
        withTimeout(5_000) {
            val inFlight = async { delivery.submit(original) }
            try {
                entered.await()
                val snapshot = JSONObject().put("context_key", "updated-during-network")
                delivery.saveContext(snapshot)
                assertEquals(snapshot.toString(), delivery.loadContext()!!.toString())
                assertTrue(delivery.finish(original.requestId))
                delivery.create(replacement)
                response.complete(proposal())
                assertEquals(NavigationDeliveryResult.Waiting("superseded"), inFlight.await())
                assertEquals(replacement, delivery.pending())
                assertEquals(snapshot.toString(), delivery.loadContext()!!.toString())
            } finally {
                inFlight.cancel()
                response.cancel()
            }
        }
    }

    @Test fun cancelledStatusRecoveryNeverStartsANewSubmission() = runBlocking {
        val original = request()
        val replacement = request()
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<JSONObject>()
        delivery.create(original)
        transport.onStatus = { _, _ ->
            entered.complete(Unit)
            response.await()
        }
        withTimeout(5_000) {
            val inFlight = async { delivery.obtain(original) }
            try {
                entered.await()
                assertTrue(delivery.finish(original.requestId))
                delivery.create(replacement)
                response.complete(status(JSONObject(original.bodyJson), "not_found"))
                assertEquals(NavigationDeliveryResult.Waiting("superseded"), inFlight.await())
                assertEquals(listOf("status"), transport.calls.map { it.operation })
                assertEquals(replacement, delivery.pending())
            } finally {
                inFlight.cancel()
                response.cancel()
            }
        }
    }

    @Test fun lateResultAndFinishCannotChangeTheReplacementRequest() = runBlocking {
        val original = request()
        val replacement = request()
        delivery.create(original)
        assertTrue(delivery.finish(original.requestId))
        delivery.create(replacement)

        assertFalse(delivery.storeResult(original.requestId, proposal()))
        assertFalse(delivery.finish(original.requestId))
        assertEquals(NavigationDeliveryResult.Waiting("superseded"), delivery.obtain(original))
        assertEquals(replacement, delivery.pending())
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun contextCanBeSavedReopenedAndClearedIndependently() = runBlocking {
        assertNull(delivery.loadContext())
        val snapshot = JSONObject().put("context_key", "route-a")
            .put("messages", JSONArray().put("导航上下文"))
        delivery.saveContext(snapshot)
        reopen()
        assertEquals(snapshot.toString(), delivery.loadContext()!!.toString())
        delivery.loadContext()!!.put("context_key", "only-in-memory")
        assertEquals(snapshot.toString(), delivery.loadContext()!!.toString())

        val original = request()
        delivery.create(original)
        delivery.saveContext(null)
        reopen()
        assertNull(delivery.loadContext())
        assertEquals(original, delivery.pending())
    }

    @Test fun consumeCommitsContextAndRemovesOnlyTheMatchingRequest() = runBlocking {
        val original = request()
        val snapshot = JSONObject().put("context_key", original.contextKey).put("accepted", false)
        // A database created before navigation_context existed must also support consumption.
        AssistantRequestJournal(context, databaseName).use { it.create(original) }
        assertTrue(delivery.storeResult(original.requestId, proposal()))
        assertTrue(delivery.consume(original.requestId, snapshot))
        reopen()

        assertNull(delivery.pending())
        assertEquals(snapshot.toString(), delivery.loadContext()!!.toString())
        assertFalse(delivery.consume(original.requestId, JSONObject().put("unexpected", true)))
        assertEquals(snapshot.toString(), delivery.loadContext()!!.toString())

        val replacement = request()
        delivery.create(replacement)
        assertFalse(delivery.consume(original.requestId, JSONObject().put("unexpected", true)))
        assertEquals(replacement, delivery.pending())
        assertEquals(snapshot.toString(), delivery.loadContext()!!.toString())
    }

    @Test fun failedConsumptionRollsBackBothContextAndPendingRequest() = runBlocking {
        val original = request()
        val previous = JSONObject().put("context_key", "before-consume")
        delivery.create(original)
        assertTrue(delivery.storeResult(original.requestId, proposal()))
        delivery.saveContext(previous)
        val persisted = delivery.pending()

        AssistantRequestJournal(context, databaseName).use { connection ->
            connection.writableDatabase.execSQL("""
                CREATE TRIGGER reject_navigation_consume BEFORE DELETE ON pending
                BEGIN
                    SELECT RAISE(ABORT, 'Injected consumption failure');
                END
            """.trimIndent())
            try {
                try {
                    delivery.consume(original.requestId, JSONObject().put("context_key", "after-consume"))
                    fail("The injected delete failure must prevent consumption")
                } catch (_: SQLiteException) {
                    // The snapshot was written first, so this failure exercises transaction rollback.
                }
            } finally {
                connection.writableDatabase.execSQL("DROP TRIGGER reject_navigation_consume")
            }
        }
        reopen()
        assertEquals(persisted, delivery.pending())
        assertEquals(previous.toString(), delivery.loadContext()!!.toString())
        assertTrue(delivery.consume(original.requestId, JSONObject().put("context_key", "after-retry")))
        assertNull(delivery.pending())
        assertEquals("after-retry", delivery.loadContext()!!.getString("context_key"))
    }

    @Test fun mismatchedStatusCannotBeStoredOrRetried() = runBlocking {
        val original = request()
        delivery.create(original)
        transport.onStatus = { _, body ->
            status(body, "completed").put("operation", "assistant").put("result", proposal())
        }
        try {
            delivery.obtain(original)
            fail("A status for another operation must be rejected")
        } catch (_: IllegalStateException) {
            assertEquals(original, delivery.pending())
            assertEquals(listOf("status"), transport.calls.map { it.operation })
        }
    }

    private fun request(): PendingAssistantRequest {
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("request_id", id)
            .put("now", "2026-10-09T08:00:00+08:00")
            .put("route_active", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "去图书馆")))
        return PendingAssistantRequest(
            requestId = id,
            server = "http://127.0.0.1:8000",
            bodyJson = body.toString(2),
            userText = "去图书馆",
            userMessageId = UUID.randomUUID().toString(),
            createdAt = 1_791_504_000_000L,
            contextKey = "navigation-context-v1",
        )
    }

    private fun proposal(): JSONObject = JSONObject().put("action", "plan_route").put("destination", "图书馆")

    private fun status(body: JSONObject, state: String): JSONObject = JSONObject()
        .put("request_id", body.getString("request_id"))
        .put("operation", "navigation")
        .put("state", state)

    private fun reopen() {
        delivery.close()
        delivery = NavigationRequestDelivery(context, databaseName, transport)
    }

    private inner class FakeTransport : NavigationRequestTransport {
        val calls = mutableListOf<Call>()
        var onSubmit: suspend (String, JSONObject) -> JSONObject = { _, _ -> proposal() }
        var onStatus: suspend (String, JSONObject) -> JSONObject = { _, body ->
            this@NavigationRequestDeliveryTest.status(body, "not_found")
        }

        override suspend fun submit(server: String, body: JSONObject): JSONObject {
            calls += Call("submit", server, body.toString())
            return onSubmit(server, body)
        }

        override suspend fun status(server: String, body: JSONObject): JSONObject {
            calls += Call("status", server, body.toString())
            return onStatus(server, body)
        }
    }

    private data class Call(val operation: String, val server: String, val bodyJson: String)
}
