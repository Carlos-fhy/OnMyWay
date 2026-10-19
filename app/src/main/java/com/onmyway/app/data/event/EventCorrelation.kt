package com.onmyway.app.data.event

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Groups the confirmations and device receipts of one errand plan under one
 * `correlation_id`.
 *
 * The active id lives in its own preferences file, outside the backup snapshot:
 * it is only meaningful on the device that observed the action. A confirmed
 * route binds the active id to its `sessionId`; navigation receipts read that
 * binding, so process death does not split one trip into several correlations.
 */
internal class EventCorrelation(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reuses the current planning correlation for a bounded time. */
    @Synchronized
    fun active(now: Long = System.currentTimeMillis()): String {
        val existing = prefs.getString(KEY_ACTIVE, null)
        val createdAt = prefs.getLong(KEY_ACTIVE_AT, 0)
        if (existing != null && isUuid(existing) && now - createdAt in 0..ACTIVE_TTL_MILLIS) {
            return existing
        }
        return UUID.randomUUID().toString().also { writeActive(it, now) }
    }

    /** Links task confirmation and route confirmation to the new trip session. */
    @Synchronized
    fun bind(
        sessionId: String,
        correlationId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): String {
        require(sessionId.isNotBlank()) { "路线会话不能为空" }
        val id = correlationId?.takeIf(::isUuid) ?: active(now)
        writeBinding(sessionId, id, now)
        return id
    }

    /**
     * Correlation for a route already stored in Room. A trip restored from a
     * build without a binding still receives a deterministic id rather than a
     * fresh random one on every call.
     */
    @Synchronized
    fun forSession(sessionId: String, now: Long = System.currentTimeMillis()): String {
        if (sessionId.isBlank()) return active(now)
        val boundSession = prefs.getString(KEY_SESSION, null)
        val bound = prefs.getString(KEY_SESSION_CORRELATION, null)
        if (boundSession == sessionId && bound != null && isUuid(bound)) return bound
        val activeId = prefs.getString(KEY_ACTIVE, null)
        val activeAt = prefs.getLong(KEY_ACTIVE_AT, 0)
        val id = if (activeId != null && isUuid(activeId) && now - activeAt in 0..ACTIVE_TTL_MILLIS) {
            activeId
        } else {
            UUID.nameUUIDFromBytes("onmyway-session:$sessionId".toByteArray(Charsets.UTF_8)).toString()
        }
        writeBinding(sessionId, id, now)
        return id
    }

    /** Ends the planning correlation after the trip is archived. */
    @Synchronized
    fun finishTrip(sessionId: String) {
        if (sessionId.isBlank()) return
        val boundSession = prefs.getString(KEY_SESSION, null)
        val bound = prefs.getString(KEY_SESSION_CORRELATION, null)
        val activeId = prefs.getString(KEY_ACTIVE, null)
        prefs.edit(commit = true) {
            if (boundSession == sessionId) {
                remove(KEY_SESSION)
                remove(KEY_SESSION_CORRELATION)
                remove(KEY_SESSION_AT)
            }
            // Only clear the correlation that belonged to this trip. A task
            // confirmed after arrival may already have started a new one.
            if (boundSession == sessionId && bound != null && bound == activeId) {
                remove(KEY_ACTIVE)
                remove(KEY_ACTIVE_AT)
            }
        }
    }

    private fun writeActive(correlationId: String, now: Long) {
        prefs.edit {
            putString(KEY_ACTIVE, correlationId)
            putLong(KEY_ACTIVE_AT, now)
        }
    }

    private fun writeBinding(sessionId: String, correlationId: String, now: Long) {
        // A route confirmation is a rare boundary; commit so a process death
        // immediately after it cannot orphan navigation receipts.
        prefs.edit(commit = true) {
            putString(KEY_ACTIVE, correlationId)
            putLong(KEY_ACTIVE_AT, now)
            putString(KEY_SESSION, sessionId)
            putString(KEY_SESSION_CORRELATION, correlationId)
            putLong(KEY_SESSION_AT, now)
        }
    }

    companion object {
        const val ACTIVE_TTL_MILLIS = 24 * 60 * 60 * 1000L
        private const val PREFS = "business_event_correlation"
        private const val KEY_ACTIVE = "active_id"
        private const val KEY_ACTIVE_AT = "active_at"
        private const val KEY_SESSION = "session_id"
        private const val KEY_SESSION_CORRELATION = "session_correlation"
        private const val KEY_SESSION_AT = "session_at"

        fun isUuid(value: String?): Boolean =
            value != null && runCatching { UUID.fromString(value) }.isSuccess
    }
}
