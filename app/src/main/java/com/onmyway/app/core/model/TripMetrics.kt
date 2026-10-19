package com.onmyway.app.core.model

import com.onmyway.app.core.format.formatDistance
import com.onmyway.app.core.format.formatDuration

/**
 * Values measured by the embedded navigation session for one trip.
 *
 * Actual duration comes from a monotonic clock; actual distance is inferred
 * from the AMap remaining-distance callback. Optional fields stay null when the
 * SDK did not provide a reliable value; they are never filled with the planner
 * estimate, so "actual" and "predicted" remain distinguishable.
 */
internal data class TripMetrics(
    val startedAt: Long,
    val endedAt: Long,
    val actualSeconds: Int,
    val actualMeters: Int? = null,
    val predictedSeconds: Int? = null,
    val predictedMeters: Int? = null,
    val distanceSamples: Int = 0,
    val reroutes: Int = 0,
) {
    val extraSeconds: Int? get() = predictedSeconds?.let { actualSeconds - it }
    val extraMeters: Int? get() = if (actualMeters != null && predictedMeters != null) actualMeters - predictedMeters else null
}

internal fun formatTripMetrics(metrics: TripMetrics): String {
    val actual = buildList {
        add("实际用时 ${formatDuration(metrics.actualSeconds)}")
        metrics.actualMeters?.let { add(formatDistance(it)) }
    }.joinToString(" · ")
    val predicted = buildList {
        metrics.predictedSeconds?.let { add(formatDuration(it)) }
        metrics.predictedMeters?.let { add(formatDistance(it)) }
    }
    return if (predicted.isEmpty()) actual else "$actual（当时预计 ${predicted.joinToString(" · ")}）"
}
