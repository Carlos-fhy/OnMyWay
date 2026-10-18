package com.onmyway.app.data.local.entity

import androidx.room.*
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.onmyway.app.core.memory.timeBand
import java.util.UUID

/**
 * Privacy-minimal trace of one route suggestion. No transcript, coordinates, or task titles.
 * PENDING and SUPERSEDED entries are excluded from acceptance statistics.
 */
@Entity(tableName = "suggestion_feedback")
data class SuggestionFeedback(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shownAt: Long = System.currentTimeMillis(),
    val mode: String,
    val offeredTaskCount: Int,
    val offeredExtraSeconds: Int,
    val decision: String = "PENDING",
    val selectedTaskCount: Int = 0,
    val selectedExtraSeconds: Int = 0,
    val decidedAt: Long = 0,
    @ColumnInfo(defaultValue = "''") val timeBand: String = "",
    @ColumnInfo(defaultValue = "''") val routeSessionId: String = "",
)
