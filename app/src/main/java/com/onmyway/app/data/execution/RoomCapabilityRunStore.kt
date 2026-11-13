package com.onmyway.app.data.execution

import androidx.room.withTransaction
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.CapabilityRun

internal class RoomCapabilityRunStore(private val database: TaskDatabase) : CapabilityRunStore {
    override suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction { block() }
    override suspend fun load(id: String) = database.capabilityRuns().load(id)
    override suspend fun save(run: CapabilityRun) = database.capabilityRuns().save(run)
}
