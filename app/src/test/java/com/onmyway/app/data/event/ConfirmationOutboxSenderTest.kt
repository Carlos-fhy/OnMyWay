package com.onmyway.app.data.event

import com.onmyway.app.data.local.dao.ConfirmationOutboxDao
import com.onmyway.app.data.local.entity.ConfirmationOutbox
import com.onmyway.app.data.remote.ApiHttpException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationOutboxSenderTest {
    private fun receipt(id: String, at: Long) = ConfirmationOutbox(
        receiptId = id,
        subjectType = "plan",
        subjectId = "plan-1",
        subjectVersion = at.toInt(),
        correlationId = "correlation-1",
        confirmedAt = at,
        snapshotJson = "{}",
    )

    private class FakeDao(private val rows: MutableList<ConfirmationOutbox>) : ConfirmationOutboxDao {
        override suspend fun latestPlanVersion(planId: String): Int = rows
            .filter { it.subjectType == "plan" && it.subjectId == planId }.maxOfOrNull { it.subjectVersion } ?: 0
        override suspend fun latest() = rows.maxByOrNull { it.confirmedAt }
        override suspend fun terminalCount() = rows.count { it.terminalAt != null }
        override suspend fun enqueue(receipt: ConfirmationOutbox): Long =
            throw UnsupportedOperationException()

        override suspend fun pending(limit: Int): List<ConfirmationOutbox> =
            rows.filter { it.deliveredAt == null && it.terminalAt == null }.take(limit)

        override suspend fun pendingCount(): Int =
            rows.count { it.deliveredAt == null && it.terminalAt == null }

        override suspend fun find(receiptId: String) = rows.firstOrNull { it.receiptId == receiptId }

        override suspend fun markDelivered(receiptId: String, at: Long): Int = update(receiptId) {
            it.copy(deliveredAt = at, lastAttemptAt = at, lastError = "")
        }

        override suspend fun markTerminal(receiptId: String, at: Long, error: String): Int =
            update(receiptId) { it.copy(terminalAt = at, lastAttemptAt = at, lastError = error) }

        override suspend fun markFailed(receiptId: String, at: Long, error: String): Int =
            update(receiptId) { it.copy(attempts = it.attempts + 1, lastAttemptAt = at, lastError = error) }

        private fun update(id: String, change: (ConfirmationOutbox) -> ConfirmationOutbox): Int {
            val index = rows.indexOfFirst {
                it.receiptId == id && it.deliveredAt == null && it.terminalAt == null
            }
            if (index < 0) return 0
            rows[index] = change(rows[index])
            return 1
        }

        fun row(id: String) = rows.first { it.receiptId == id }
    }

    private class FakeTransport(private val failure: (ConfirmationOutbox) -> Exception?) :
        ConfirmationTransport {
        val delivered = mutableListOf<String>()

        override suspend fun deliver(receipt: ConfirmationOutbox) {
            failure(receipt)?.let { throw it }
            delivered += receipt.receiptId
        }
    }

    @Test fun successfulPassMarksEveryReceiptDelivered() = runBlocking {
        val rows = mutableListOf(receipt("first", 100), receipt("second", 200))
        val dao = FakeDao(rows)
        val transport = FakeTransport { null }
        val sender = ConfirmationOutboxSender(dao, transport) { 1_000 }

        assertEquals(false, sender.flush())
        assertEquals(listOf("first", "second"), transport.delivered)
        assertEquals(1_000L, dao.row("first").deliveredAt)
        assertEquals(0, dao.pendingCount())
    }

    @Test fun networkFailureStopsThePassAndKeepsTheReceiptForRetry() = runBlocking {
        val rows = mutableListOf(receipt("first", 100), receipt("second", 200))
        val dao = FakeDao(rows)
        val failing = FakeTransport { error ->
            if (error.receiptId == "first") IOException("offline") else null
        }
        val sender = ConfirmationOutboxSender(dao, failing) { 1_000 }

        assertTrue(sender.flush())
        assertEquals(emptyList<String>(), failing.delivered)
        assertEquals(1, dao.row("first").attempts)
        assertNull(dao.row("first").deliveredAt)
        assertNull(dao.row("second").deliveredAt)

        // The same row and receipt id are retried; later receipts do not overtake it.
        val recovered = FakeTransport { null }
        assertEquals(false, ConfirmationOutboxSender(dao, recovered) { 2_000 }.flush())
        assertEquals(listOf("first", "second"), recovered.delivered)
    }

    @Test fun terminalConflictDoesNotBlockFollowingReceipts() = runBlocking {
        val rows = mutableListOf(receipt("stale", 100), receipt("next", 200))
        val dao = FakeDao(rows)
        val transport = FakeTransport { error ->
            if (error.receiptId == "stale") ApiHttpException(409, "确认版本已过期") else null
        }
        val sender = ConfirmationOutboxSender(dao, transport) { 1_000 }

        // The stale receipt is terminal, the valid one is still delivered.
        assertEquals(false, sender.flush())
        assertEquals(listOf("next"), transport.delivered)
        assertNotNull(dao.row("stale").terminalAt)
        assertNotNull(dao.row("next").deliveredAt)
    }

    @Test fun mismatchedAcknowledgementIsNotDeliveredAndDoesNotBlockNextReceipt() = runBlocking {
        val dao = FakeDao(mutableListOf(receipt("bad", 100), receipt("next", 200)))
        val transport = FakeTransport { if (it.receiptId == "bad") ConfirmationProtocolException() else null }
        assertEquals(false, ConfirmationOutboxSender(dao, transport) { 1_000 }.flush())
        assertNull(dao.row("bad").deliveredAt)
        assertNotNull(dao.row("bad").terminalAt)
        assertEquals(listOf("next"), transport.delivered)
    }
}
