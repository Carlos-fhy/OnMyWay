package com.onmyway.app.data.local.dao

import androidx.room.*
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip

@Dao
interface TripDao {
    @Query("SELECT * FROM route_history WHERE planId = :planId ORDER BY timestamp DESC, rowid DESC")
    suspend fun historyForPlan(planId: String): List<RouteHistory>
    @Query("SELECT * FROM saved_trip WHERE id = 1") suspend fun load(): SavedTrip?
    @Upsert suspend fun save(trip: SavedTrip)
    @Query("UPDATE saved_trip SET needsReplan = 1 WHERE id = 1") suspend fun markNeedsReplan(): Int
    @Query("DELETE FROM saved_trip") suspend fun clear()
    @Insert suspend fun record(event: RouteHistory)
    @Query("SELECT * FROM route_history ORDER BY timestamp DESC, rowid DESC LIMIT 50") suspend fun history(): List<RouteHistory>
    @Query("SELECT * FROM route_history WHERE sessionId = :sessionId ORDER BY timestamp DESC, rowid DESC")
    suspend fun historyForSession(sessionId: String): List<RouteHistory>
    @Query("SELECT state FROM route_history WHERE sessionId = :sessionId ORDER BY timestamp DESC, rowid DESC LIMIT 1")
    suspend fun latestStateForSession(sessionId: String): String?
    @Query("SELECT * FROM route_history WHERE id = :id LIMIT 1") suspend fun historyById(id: String): RouteHistory?
}
