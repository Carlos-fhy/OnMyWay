package com.onmyway.app.data.local.dao

import androidx.room.*
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.onmyway.app.data.local.entity.SuggestionFeedback

@Dao
interface SuggestionFeedbackDao {
    @Insert suspend fun show(feedback: SuggestionFeedback)
    @Query("UPDATE suggestion_feedback SET decision = :decision, selectedTaskCount = :selectedTaskCount, selectedExtraSeconds = :selectedExtraSeconds, decidedAt = :decidedAt, routeSessionId = :routeSessionId WHERE id = :id AND decision = 'PENDING'")
    suspend fun decide(id: String, decision: String, selectedTaskCount: Int, selectedExtraSeconds: Int, decidedAt: Long, routeSessionId: String): Int
    @Query("SELECT * FROM suggestion_feedback WHERE decision != 'PENDING' AND decision != 'SUPERSEDED' ORDER BY decidedAt DESC LIMIT 100")
    suspend fun recentDecisions(): List<SuggestionFeedback>
    @Query("DELETE FROM suggestion_feedback")
    suspend fun clear()
}
