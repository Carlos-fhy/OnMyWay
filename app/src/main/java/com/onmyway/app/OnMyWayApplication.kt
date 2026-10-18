package com.onmyway.app

import android.app.Application
import android.content.SharedPreferences
import androidx.room.InvalidationTracker
import androidx.work.*
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.sync.BackupCredentials
import com.onmyway.app.data.sync.PcBackupSync

class OnMyWayApplication : Application() {
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        PcBackupSync.schedule(this)
    }
    private var databaseObserver: InvalidationTracker.Observer? = null

    override fun onCreate() {
        super.onCreate()
        startObserving()
    }

    fun startObserving() {
        if (databaseObserver != null || BackupCredentials.load(this) == null) return
        listOf("settings", "assistant_conversation", "navigation_context").forEach {
            getSharedPreferences(it, 0).registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        databaseObserver = object : InvalidationTracker.Observer(
            "tasks", "saved_trip", "route_history", "suggestion_feedback", "agent_events",
            "assistant_messages", "assistant_draft", "assistant_task_drafts", "planning_draft", "planning_draft_tasks", "assistant_clarification", "assistant_place_choices") {
            override fun onInvalidated(tables: Set<String>) { PcBackupSync.schedule(this@OnMyWayApplication) }
        }.also { TaskDatabase.get(this).invalidationTracker.addObserver(it) }
        PcBackupSync.schedule(this)
    }
}
