package com.onmyway.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.onmyway.app.data.local.entity.BusinessEventOutbox

@Dao
interface BusinessEventOutboxDao {
    /** Re-enqueuing the same observation keeps exactly one durable row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(event: BusinessEventOutbox): Long

    @Query("SELECT * FROM business_event_outbox WHERE deliveredAt IS NULL AND terminalAt IS NULL " +
        "ORDER BY occurredAt, createdAt, eventId LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<BusinessEventOutbox>

    @Query("SELECT COUNT(*) FROM business_event_outbox WHERE deliveredAt IS NULL AND terminalAt IS NULL")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM business_event_outbox WHERE eventId = :eventId")
    suspend fun find(eventId: String): BusinessEventOutbox?

    @Query("UPDATE business_event_outbox SET deliveredAt = :at, lastAttemptAt = :at, lastError = '' " +
        "WHERE eventId = :eventId AND deliveredAt IS NULL AND terminalAt IS NULL")
    suspend fun markDelivered(eventId: String, at: Long): Int

    /** The event was rejected by the envelope contract; retrying the same bytes cannot help. */
    @Query("UPDATE business_event_outbox SET terminalAt = :at, lastAttemptAt = :at, lastError = :error " +
        "WHERE eventId = :eventId AND deliveredAt IS NULL AND terminalAt IS NULL")
    suspend fun markTerminal(eventId: String, at: Long, error: String): Int

    @Query("UPDATE business_event_outbox SET attempts = attempts + 1, lastAttemptAt = :at, lastError = :error " +
        "WHERE eventId = :eventId AND deliveredAt IS NULL AND terminalAt IS NULL")
    suspend fun markFailed(eventId: String, at: Long, error: String): Int
}
