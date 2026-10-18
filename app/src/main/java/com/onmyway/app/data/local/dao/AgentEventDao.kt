package com.onmyway.app.data.local.dao

import androidx.room.*
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.onmyway.app.data.local.entity.AgentEvent

@Dao
interface AgentEventDao {
    @Insert suspend fun append(event: AgentEvent)
    @Query("SELECT * FROM agent_events ORDER BY occurredAt DESC, rowid DESC LIMIT 100")
    suspend fun recent(): List<AgentEvent>
    @Query("DELETE FROM agent_events")
    suspend fun clear()
    @Query("DELETE FROM agent_events WHERE actionId = :actionId")
    suspend fun deleteForAction(actionId: String)
}
