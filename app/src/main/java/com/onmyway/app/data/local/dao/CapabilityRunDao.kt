package com.onmyway.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.onmyway.app.data.local.entity.CapabilityRun

@Dao
interface CapabilityRunDao {
    @Query("SELECT * FROM capability_runs WHERE runId = :runId")
    suspend fun load(runId: String): CapabilityRun?

    @Upsert suspend fun save(run: CapabilityRun)

    @Query("SELECT * FROM capability_runs WHERE sessionId = :sessionId ORDER BY updatedAt DESC, runId")
    suspend fun forSession(sessionId: String): List<CapabilityRun>

    @Query("SELECT * FROM capability_runs ORDER BY updatedAt DESC, runId LIMIT 5")
    suspend fun recent(): List<CapabilityRun>
}
