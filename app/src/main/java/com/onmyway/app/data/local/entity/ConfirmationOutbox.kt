package com.onmyway.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable outbound queue for accepted proposal and plan versions.
 *
 * The receipt records what the user confirmed; it does not claim that the
 * route was navigated or the task completed. Rows are written in the same Room
 * transaction as the local confirmation and are delivered independently of
 * business events.
 *
 * Like the event outbox this table is local-only: PC backup snapshots use an
 * explicit table list and intentionally exclude it.
 */
@Entity(
    tableName = "confirmation_outbox",
    indices = [Index(value = ["deliveredAt", "terminalAt", "confirmedAt"])],
)
data class ConfirmationOutbox(
    @PrimaryKey val receiptId: String,
    val subjectType: String,
    val subjectId: String,
    val subjectVersion: Int,
    val correlationId: String,
    val confirmedAt: Long,
    val validUntil: Long? = null,
    val snapshotJson: String = "{}",
    val createdAt: Long = confirmedAt,
    val deliveredAt: Long? = null,
    /** A permanent server rejection; kept for audit and never retried. */
    val terminalAt: Long? = null,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String = "",
)
