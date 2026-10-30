package com.onmyway.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.onmyway.app.data.local.entity.ConfirmationOutbox

@Dao
interface ConfirmationOutboxDao {
    /** Re-enqueuing the same subject version keeps exactly one durable row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(receipt: ConfirmationOutbox): Long

    @Query("SELECT * FROM confirmation_outbox WHERE deliveredAt IS NULL AND terminalAt IS NULL " +
        "ORDER BY confirmedAt, createdAt, receiptId LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<ConfirmationOutbox>

    @Query("SELECT COUNT(*) FROM confirmation_outbox WHERE deliveredAt IS NULL AND terminalAt IS NULL")
    suspend fun pendingCount(): Int

    @Query("SELECT COALESCE(MAX(subjectVersion), 0) FROM confirmation_outbox " +
        "WHERE subjectType = 'plan' AND subjectId = :planId")
    suspend fun latestPlanVersion(planId: String): Int

    @Query("SELECT * FROM confirmation_outbox ORDER BY confirmedAt DESC, createdAt DESC, receiptId DESC LIMIT 1")
    suspend fun latest(): ConfirmationOutbox?

    @Query("SELECT COUNT(*) FROM confirmation_outbox WHERE terminalAt IS NOT NULL")
    suspend fun terminalCount(): Int

    @Query("SELECT * FROM confirmation_outbox WHERE receiptId = :receiptId")
    suspend fun find(receiptId: String): ConfirmationOutbox?

    @Query("UPDATE confirmation_outbox SET deliveredAt = :at, lastAttemptAt = :at, lastError = '' " +
        "WHERE receiptId = :receiptId AND deliveredAt IS NULL AND terminalAt IS NULL")
    suspend fun markDelivered(receiptId: String, at: Long): Int

    @Query("UPDATE confirmation_outbox SET terminalAt = :at, lastAttemptAt = :at, lastError = :error " +
        "WHERE receiptId = :receiptId AND deliveredAt IS NULL AND terminalAt IS NULL")
    suspend fun markTerminal(receiptId: String, at: Long, error: String): Int

    @Query("UPDATE confirmation_outbox SET attempts = attempts + 1, lastAttemptAt = :at, lastError = :error " +
        "WHERE receiptId = :receiptId AND deliveredAt IS NULL AND terminalAt IS NULL")
    suspend fun markFailed(receiptId: String, at: Long, error: String): Int
}
