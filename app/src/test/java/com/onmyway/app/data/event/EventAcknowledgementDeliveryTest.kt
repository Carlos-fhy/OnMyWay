package com.onmyway.app.data.event

import com.onmyway.app.data.local.dao.BusinessEventOutboxDao
import com.onmyway.app.data.local.entity.BusinessEventOutbox
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventAcknowledgementDeliveryTest {
    private fun event(id: String, at: Long) = BusinessEventOutbox(
        eventId = id, correlationId = "22222222-2222-4222-8222-222222222222",
        kind = "NAVIGATION_STARTED", source = "device", occurredAt = at,
    )

    private fun response(event: BusinessEventOutbox, status: String) = JSONObject()
        .put("event_id", event.eventId).put("correlation_id", event.correlationId)
        .put("status", status).put("recorded", true)

    @Test fun protocolRejectionRetainsTheRowWithoutMarkingDeliveryAndContinues() = runBlocking {
        val first = event("11111111-1111-4111-8111-111111111111", 100)
        val next = event("33333333-3333-4333-8333-333333333333", 200)
        val dao = MemoryDao(mutableListOf(first, next))
        val transport = EventTransport { item ->
            val body = if (item.eventId == first.eventId) JSONObject() else response(item, "accepted")
            EventAcknowledgement.fromJson(body).requireMatches(item)
        }

        assertFalse(EventOutboxSender(dao, transport) { 1_000 }.flush())

        assertNull(dao.find(first.eventId)!!.deliveredAt)
        assertEquals(1_000L, dao.find(first.eventId)!!.terminalAt)
        assertEquals(1_000L, dao.find(next.eventId)!!.deliveredAt)
        assertEquals(0, dao.pendingCount())
        assertEquals(1, dao.terminalCount())
    }

    @Test fun acceptedAndDuplicateResponsesBothMarkTheirExactRowsDelivered() = runBlocking {
        val first = event("11111111-1111-4111-8111-111111111111", 100)
        val next = event("33333333-3333-4333-8333-333333333333", 200)
        val dao = MemoryDao(mutableListOf(first, next))
        val transport = EventTransport { item ->
            EventAcknowledgement.fromJson(response(item,
                if (item.eventId == first.eventId) "accepted" else "duplicate")).requireMatches(item)
        }

        assertFalse(EventOutboxSender(dao, transport) { 1_000 }.flush())

        assertEquals(1_000L, dao.find(first.eventId)!!.deliveredAt)
        assertEquals(1_000L, dao.find(next.eventId)!!.deliveredAt)
        assertEquals(0, dao.terminalCount())
    }

    private class MemoryDao(private val rows: MutableList<BusinessEventOutbox>) : BusinessEventOutboxDao {
        override suspend fun enqueue(event: BusinessEventOutbox): Long = throw UnsupportedOperationException()
        override suspend fun pending(limit: Int) = rows.filter { it.deliveredAt == null && it.terminalAt == null }.take(limit)
        override suspend fun pendingCount() = rows.count { it.deliveredAt == null && it.terminalAt == null }
        override suspend fun terminalCount() = rows.count { it.terminalAt != null }
        override suspend fun find(eventId: String) = rows.firstOrNull { it.eventId == eventId }
        override suspend fun markDelivered(eventId: String, at: Long) = update(eventId) {
            it.copy(deliveredAt = at, lastAttemptAt = at, lastError = "")
        }
        override suspend fun markTerminal(eventId: String, at: Long, error: String) = update(eventId) {
            it.copy(terminalAt = at, lastAttemptAt = at, lastError = error)
        }
        override suspend fun markFailed(eventId: String, at: Long, error: String) = update(eventId) {
            it.copy(attempts = it.attempts + 1, lastAttemptAt = at, lastError = error)
        }

        private fun update(eventId: String, change: (BusinessEventOutbox) -> BusinessEventOutbox): Int {
            val index = rows.indexOfFirst { it.eventId == eventId && it.deliveredAt == null && it.terminalAt == null }
            if (index < 0) return 0
            rows[index] = change(rows[index])
            return 1
        }
    }
}
