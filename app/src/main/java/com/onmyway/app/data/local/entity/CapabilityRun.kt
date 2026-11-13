package com.onmyway.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local execution evidence is not part of the portable task backup. */
@Entity(tableName = "capability_runs", indices = [Index(value = ["sessionId", "updatedAt"])])
data class CapabilityRun(
    @PrimaryKey val runId: String,
    val capabilityId: String,
    val capabilityVersion: Int = 1,
    val executor: String,
    val state: String,
    val step: String,
    val snapshotJson: String,
    val correlationId: String,
    val planId: String = "",
    val planVersion: Int = 0,
    val sessionId: String = "",
    val attemptId: String = "",
    val resultJson: String = "",
    val error: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)
