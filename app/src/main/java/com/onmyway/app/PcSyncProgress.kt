package com.onmyway.app

import android.content.SharedPreferences
import androidx.core.content.edit

/** UI progress only. The Room data and SQLite journal remain the durable source of pending edits. */
internal class PcSyncProgress(private val prefs: SharedPreferences) {
    companion object { private val lock = Any() }

    fun changed(): Long = synchronized(lock) {
        val next = prefs.getLong("sync_generation", 0) + 1
        prefs.edit { putLong("sync_generation", next); putString("sync_state", "pending") }
        next
    }

    fun covered(generation: Long): Boolean = synchronized(lock) {
        generation >= 0 && generation <= prefs.getLong("synced_generation", -1) &&
            prefs.getString("sync_state", null) == "synced"
    }

    fun begin(): Long = synchronized(lock) {
        prefs.edit { putString("sync_state", "syncing") }
        prefs.getLong("sync_generation", 0)
    }

    fun succeeded(generation: Long, taskProtocol: Int, restored: Boolean) = synchronized(lock) {
        prefs.edit {
            putLong("last_success_at", System.currentTimeMillis())
            putLong("synced_generation", maxOf(generation, prefs.getLong("synced_generation", -1)))
            putInt("task_protocol", taskProtocol)
            remove("last_error")
            putString("sync_state", if (prefs.getLong("sync_generation", 0) == generation) "synced" else "pending")
            if (restored) putBoolean("needs_reload", true)
        }
    }

    fun failed(error: Exception) = synchronized(lock) {
        prefs.edit {
            putString("last_error", error.message?.take(160) ?: "同步失败")
            putString("sync_state", if (error is SyncConflict || error is ApiHttpException && error.status == 409)
                "conflict" else "pending")
        }
    }
}
