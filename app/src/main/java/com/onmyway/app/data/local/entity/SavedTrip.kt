package com.onmyway.app.data.local.entity

import androidx.room.*
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "saved_trip")
data class SavedTrip(@PrimaryKey val id: Int = 1, val requestJson: String, val resultJson: String, val started: Boolean = false,
    @ColumnInfo(defaultValue = "''") val sessionId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val startedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val plannedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val needsReplan: Boolean = false,
    @ColumnInfo(defaultValue = "''") val planId: String = sessionId)
