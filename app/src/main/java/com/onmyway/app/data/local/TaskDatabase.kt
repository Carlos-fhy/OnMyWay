package com.onmyway.app.data.local

import android.content.Context
import androidx.room.*
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.onmyway.app.data.local.dao.AgentEventDao
import com.onmyway.app.data.local.dao.SuggestionFeedbackDao
import com.onmyway.app.data.local.dao.TaskDao
import com.onmyway.app.data.local.dao.TripDao
import com.onmyway.app.data.local.entity.AgentEvent
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.local.entity.SuggestionFeedback
import com.onmyway.app.feature.assistant.AssistantClarification
import com.onmyway.app.feature.assistant.AssistantDao
import com.onmyway.app.feature.assistant.AssistantDraft
import com.onmyway.app.feature.assistant.AssistantDraftItem
import com.onmyway.app.feature.assistant.AssistantMessage
import com.onmyway.app.feature.assistant.AssistantPlaceChoice
import com.onmyway.app.feature.planning.PlanningDraft
import com.onmyway.app.feature.planning.PlanningDraftTask

@Database(entities = [Errand::class, SavedTrip::class, RouteHistory::class, SuggestionFeedback::class, AgentEvent::class, AssistantMessage::class, AssistantDraft::class, AssistantDraftItem::class, PlanningDraft::class, PlanningDraftTask::class, AssistantClarification::class, AssistantPlaceChoice::class], version = 13, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun trips(): TripDao
    abstract fun suggestionFeedback(): SuggestionFeedbackDao
    abstract fun agentEvents(): AgentEventDao
    abstract fun assistant(): AssistantDao
    companion object {
        @Volatile private var instance: TaskDatabase? = null
        fun get(context: Context): TaskDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TaskDatabase::class.java, "onmyway.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                .build().also { instance = it }
        }
    }
}
