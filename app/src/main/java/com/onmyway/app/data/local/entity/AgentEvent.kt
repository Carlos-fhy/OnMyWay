package com.onmyway.app.data.local.entity

import androidx.room.*
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.onmyway.app.core.memory.timeBand
import java.util.UUID

@Entity(tableName = "agent_events")
data class AgentEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String,
    val occurredAt: Long = System.currentTimeMillis(),
    val source: String,
    val correlationId: String = "",
    val routeSessionId: String = "",
    val actionId: String = "",
    val mode: String = "",
    val timeBand: String = "",
    val itemCount: Int = 0,
    val extraSeconds: Int = 0,
)
