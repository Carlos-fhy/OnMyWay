package com.onmyway.app.data.event

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.BusinessEventOutbox
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.remote.ApiHttpException
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BusinessEventOutboxTest {
    private lateinit var context: Context
    private lateinit var database: TaskDatabase
    private lateinit var dao: com.onmyway.app.data.local.dao.BusinessEventOutboxDao

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("business_event_correlation", 0).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        dao = database.businessEvents()
    }

    @After fun tearDown() {
        database.close()
        context.getSharedPreferences("business_event_correlation", 0).edit().clear().commit()
    }

    @Test fun duplicateObservationKeepsOneRowAndPendingOrderIsStable() = runBlocking {
        val later = event("later", occurredAt = 200)
        val earlier = event("earlier", occurredAt = 100)
        assertTrue(dao.enqueue(later) >= 0)
        assertTrue(dao.enqueue(earlier) >= 0)
        // Same observation (same event id) is ignored, including a changed payload.
        assertEquals(-1L, dao.enqueue(later.copy(payloadJson = """{"changed":true}""")))
        assertEquals(listOf("earlier", "later"), dao.pending().map { it.eventId })
        assertEquals("""{"route_session_id":"trip-1"}""", dao.find("later")!!.payloadJson)
        assertEquals(2, dao.pendingCount())
    }

    @Test fun networkFailureKeepsSameRowForRetry() = runBlocking {
        val queued = event("retry-me", occurredAt = 100)
        dao.enqueue(queued)
        val transport = object : EventTransport {
            override suspend fun deliver(event: BusinessEventOutbox) {
                throw IOException("offline")
            }
        }
        val sender = EventOutboxSender(dao, transport, now = { 1_000 })
        assertTrue(sender.flush())
        val stored = dao.find("retry-me")!!
        assertNull(stored.deliveredAt)
        assertNull(stored.terminalAt)
        assertEquals(1, stored.attempts)
        assertEquals(1_000L, stored.lastAttemptAt)
        assertEquals("offline", stored.lastError)
        assertEquals("retry-me", stored.eventId)
    }

    @Test fun deliveredRowLeavesPendingQueue() = runBlocking {
        dao.enqueue(event("ok", occurredAt = 100))
        val transport = object : EventTransport {
            override suspend fun deliver(event: BusinessEventOutbox) = Unit
        }
        val sender = EventOutboxSender(dao, transport, now = { 2_000 })
        assertFalse(sender.flush())
        val stored = dao.find("ok")!!
        assertEquals(2_000L, stored.deliveredAt)
        assertNull(stored.terminalAt)
        assertEquals(0, dao.pendingCount())
    }

    @Test fun contractRejectionIsTerminalAndDoesNotBlockFollowingEvents() = runBlocking {
        dao.enqueue(event("bad", occurredAt = 100))
        dao.enqueue(event("good", occurredAt = 200))
        val delivered = mutableListOf<String>()
        val transport = object : EventTransport {
            override suspend fun deliver(event: BusinessEventOutbox) {
                if (event.eventId == "bad") throw ApiHttpException(422, "invalid envelope")
                delivered += event.eventId
            }
        }
        val sender = EventOutboxSender(dao, transport, now = { 3_000 })
        assertFalse(sender.flush())
        assertEquals(listOf("good"), delivered)
        val rejected = dao.find("bad")!!
        assertEquals(3_000L, rejected.terminalAt)
        assertNull(rejected.deliveredAt)
        assertNotNull(dao.find("good")!!.deliveredAt)
        assertEquals(0, dao.pendingCount())
    }

    @Test fun requestJsonCarriesEnvelopeAndLocalReceipt() {
        val queued = event("envelope", occurredAt = 1_792_300_800_000L, correlationId = UUID.randomUUID().toString())
        val request = queued.toRequestJson(ZoneId.of("Asia/Shanghai"))
        assertEquals(1, request.getInt("schema_version"))
        assertEquals("envelope", request.getString("event_id"))
        assertEquals(queued.correlationId, request.getString("correlation_id"))
        assertEquals("device", request.getString("source"))
        assertEquals("NAVIGATION_STARTED", request.getString("kind"))
        assertTrue(request.getString("occurred_at").endsWith("+08:00"))
        assertFalse(request.has("valid_until"))
        assertEquals("local_receipt",
            request.getJSONArray("evidence").getJSONObject(0).getString("kind"))
        assertEquals("route-session:trip-1",
            request.getJSONArray("evidence").getJSONObject(0).getString("ref"))
        assertEquals("trip-1", request.getJSONObject("payload").getString("route_session_id"))
    }

    @Test fun confirmationsAndDeviceReceiptShareOneCorrelation() {
        val correlation = EventCorrelation(context)
        val now = 1_792_300_800_000L
        val planningId = correlation.active(now)
        val task = Errand(id = "task-1", title = "取快递", placeType = "PARCEL")
        val taskEvent = BusinessEventFactory.taskConfirmed(
            task, "draft-1", "assistant-task-confirmed-draft-1", planningId, now,
        )
        val sessionId = "session-1"
        val routeId = correlation.bind(sessionId, planningId, now + 1)
        val routeRequest = JSONObject()
            .put("mode", "walking")
            .put("tasks", org.json.JSONArray().put(JSONObject().put("id", "task-1")))
        val routeEvent = BusinessEventFactory.routeConfirmed(
            historyId = "history-1", sessionId = sessionId, planId = "plan-1",
            request = routeRequest, route = JSONObject().put("total_seconds", 900),
            correlationId = routeId, occurredAt = now + 2, replanned = false,
        )
        val navEvent = BusinessEventFactory.navigationStarted(
            sessionId = sessionId, planId = "plan-1", request = routeRequest,
            correlationId = correlation.forSession(sessionId, now + 3), occurredAt = now + 3,
        )
        assertEquals(taskEvent.correlationId, routeEvent.correlationId)
        assertEquals(routeEvent.correlationId, navEvent.correlationId)
        assertEquals(3, setOf(taskEvent.eventId, routeEvent.eventId, navEvent.eventId).size)
        assertEquals("TASK_CONFIRMED", taskEvent.kind)
        assertEquals("ROUTE_CONFIRMED", routeEvent.kind)
        assertEquals("NAVIGATION_STARTED", navEvent.kind)
    }

    @Test fun finishingTripClearsBindingAndActiveCorrelation() {
        val correlation = EventCorrelation(context)
        val now = 1_792_300_800_000L
        val planningId = correlation.active(now)
        correlation.bind("session-finish", planningId, now)
        assertEquals(planningId, correlation.forSession("session-finish", now))
        correlation.finishTrip("session-finish")
        val fallback = correlation.forSession("session-finish", now + 10)
        assertTrue(EventCorrelation.isUuid(fallback))
        assertFalse(fallback == planningId)
        assertFalse(correlation.active(now + 20) == planningId)
    }

    private fun event(id: String, occurredAt: Long, correlationId: String = UUID.randomUUID().toString()) =
        BusinessEventOutbox(
            eventId = id,
            correlationId = correlationId,
            kind = "NAVIGATION_STARTED",
            source = "device",
            occurredAt = occurredAt,
            evidenceJson = """[{"kind":"local_receipt","ref":"route-session:trip-1"}]""",
            payloadJson = """{"route_session_id":"trip-1"}""",
            createdAt = occurredAt,
        )
}
