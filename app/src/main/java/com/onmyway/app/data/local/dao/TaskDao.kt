package com.onmyway.app.data.local.dao

import androidx.room.*
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.onmyway.app.data.local.entity.Errand
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") fun observe(): Flow<List<Errand>>
    @Upsert suspend fun save(task: Errand)
    @Delete suspend fun delete(task: Errand)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: String): Errand?
    @Query("SELECT * FROM tasks WHERE status = 'pending' AND ((reminderAt > 0 AND reminderAt <= :now) OR (reminderAt = 0 AND (CASE WHEN lastRemindedAt > 0 THEN lastRemindedAt ELSE createdAt END) <= :cutoff))")
    suspend fun dueForReminder(now: Long, cutoff: Long): List<Errand>
    @Query("SELECT * FROM tasks WHERE status = 'pending' AND reminderAt > 0")
    suspend fun scheduledReminders(): List<Errand>
    @Query("SELECT * FROM tasks WHERE status = 'pending' AND reminderPending = 1")
    suspend fun unreadReminders(): List<Errand>
    @Query("UPDATE tasks SET reminderPending = 0") suspend fun acknowledgeReminders()
}
