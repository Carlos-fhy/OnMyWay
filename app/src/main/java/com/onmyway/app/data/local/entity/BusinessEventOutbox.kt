package com.onmyway.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable outbound queue for the M2 business-event envelope.
 *
 * Rows are inserted in the same Room transaction as the local action they
 * describe. Delivery only marks a row; it never changes task, trip or draft
 * state, so a failed network request cannot block navigation.
 *
 * The table is intentionally excluded from PC backup snapshots. A correlation
 * belongs to the device that observed it and is not restored onto another one.
 */
@Entity(
    tableName = "business_event_outbox",
    indices = [Index(value = ["deliveredAt", "terminalAt", "occurredAt"])],
)
data class BusinessEventOutbox(
    @PrimaryKey val eventId: String,
    val correlationId: String,
    val kind: String,
    val source: String,
    val schemaVersion: Int = 1,
    val occurredAt: Long,
    val validUntil: Long? = null,
    val evidenceJson: String = "[]",
    val payloadJson: String = "{}",
    val createdAt: Long = occurredAt,
    val deliveredAt: Long? = null,
    /** A permanent server rejection; the row is kept for audit and never retried. */
    val terminalAt: Long? = null,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String = "",
)
