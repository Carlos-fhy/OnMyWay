package com.onmyway.app.data.local.entity

import androidx.room.*
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "route_history")
data class RouteHistory(@PrimaryKey val id: String = UUID.randomUUID().toString(), val sessionId: String,
    val state: String, val timestamp: Long, val requestJson: String, val resultJson: String,
    @ColumnInfo(defaultValue = "''") val planId: String = "")
