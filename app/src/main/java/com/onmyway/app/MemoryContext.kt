package com.onmyway.app

import java.time.Instant
import java.time.ZoneId

/** A compact, privacy-minimal view of what is relevant to one planning request. */
internal data class PlanningContext(
    val mode: String,
    val timeBand: String,
    val pendingTaskCount: Int,
    val activeRouteSessionId: String?,
    val detour: DetourGuidance,
)

internal enum class GuidanceSource { DEFAULT, LEARNED, EXPLICIT }

internal data class DetourGuidance(
    val minutes: Int,
    val source: GuidanceSource,
    val evidenceCount: Int = 0,
    val matchingTimeBand: Boolean = false,
)

internal fun timeBand(hour: Int): String = when (hour) {
    in 5..10 -> "morning"
    in 11..16 -> "daytime"
    in 17..21 -> "evening"
    else -> "night"
}

/** Only an explicit "detour too long" choice establishes a rejected time boundary. */
internal fun detourGuidance(
    mode: String,
    currentTimeBand: String,
    defaultMinutes: Int,
    explicitMinutes: Int?,
    learningEnabled: Boolean,
    feedback: List<SuggestionFeedback>,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): DetourGuidance {
    explicitMinutes?.let { return DetourGuidance(it.coerceIn(0, 240), GuidanceSource.EXPLICIT) }
    val fallback = DetourGuidance(defaultMinutes.coerceIn(0, 240), GuidanceSource.DEFAULT)
    if (!learningEnabled || mode !in setOf("walking", "cycling")) return fallback

    val recent = feedback.filter {
        it.mode == mode && it.shownAt in (now - 90L * 24 * 60 * 60 * 1000)..now &&
            it.offeredExtraSeconds in 0..3600 && it.decision in setOf("ACCEPTED", "ADJUSTED", "DIRECT_TIME")
    }
    fun bandOf(entry: SuggestionFeedback) = entry.timeBand.ifBlank {
        timeBand(Instant.ofEpochMilli(entry.shownAt).atZone(zone).hour)
    }
    val sameBand = recent.filter { bandOf(it) == currentTimeBand }
    val scoped = if (sameBand.size >= 4) sameBand else recent
    val accepted = scoped.filter { it.decision in setOf("ACCEPTED", "ADJUSTED") &&
        it.selectedTaskCount > 0 && it.selectedExtraSeconds in 0..3600 }
    val direct = scoped.filter { it.decision == "DIRECT_TIME" && it.selectedTaskCount == 0 }
    if (accepted.size < 2 || direct.size < 2) return fallback
    val acceptedUpper = accepted.maxOf { it.selectedExtraSeconds }
    val rejectedLower = direct.minOf { it.offeredExtraSeconds }
    if (acceptedUpper + 60 >= rejectedLower) return fallback
    val midpointMinutes = ((acceptedUpper + rejectedLower) / 120).coerceIn(0, 60)
    return DetourGuidance(midpointMinutes, GuidanceSource.LEARNED, accepted.size + direct.size,
        scoped === sameBand)
}
