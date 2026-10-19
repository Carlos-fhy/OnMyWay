package com.onmyway.app.feature.navigation

import com.onmyway.app.core.model.TripMetrics

/**
 * Pure accumulator for one trip, shared by every NavigationController created
 * for the same session (route adjustments replace the controller, not the trip).
 *
 * Duration uses a monotonic elapsed value supplied by the caller, so a wall
 * clock adjustment cannot inflate or shrink the trip. Distance is inferred from
 * decreasing remaining-distance samples; a route replacement establishes a new
 * baseline so the jump is never counted as travelled distance.
 */
internal class NavigationMeter {
    private var startedWall = 0L
    private var startedElapsed = 0L
    private var endedWall = 0L
    private var endedElapsed = 0L
    private var initialRemaining: Int? = null
    private var lastRemaining: Int? = null
    private var inferFromInitial = false
    private var traveledMeters = 0
    private var samples = 0
    private var reroutes = 0

    val started: Boolean get() = startedWall > 0
    val finished: Boolean get() = endedWall > 0

    /** Call only when the SDK confirms that navigation actually started. */
    fun start(wallMillis: Long, elapsedMillis: Long) {
        if (started) return
        startedWall = wallMillis
        startedElapsed = elapsedMillis
    }

    /** Baseline from the currently calculated route, before the SDK start callback. */
    fun initialRoute(meters: Int?) {
        if (started || finished) return
        initialRemaining = meters?.takeIf { it > 0 }
        lastRemaining = null
        inferFromInitial = initialRemaining != null
    }

    /**
     * A replacement route (yaw, pause/resume, confirmed adjustment) starts a new
     * baseline. The remaining-distance jump between routes is not travelled.
     */
    fun rerouted(meters: Int?) {
        if (!started || finished) return
        reroutes++
        lastRemaining = null
        initialRemaining = meters?.takeIf { it > 0 }
        inferFromInitial = initialRemaining != null
    }

    fun sample(remainingMeters: Int) {
        if (!started || finished || remainingMeters < 0) return
        val previous = lastRemaining
        if (previous == null) {
            val initial = initialRemaining
            if (inferFromInitial && initial != null) {
                if (remainingMeters <= initial) traveledMeters += initial - remainingMeters
                else reroutes++
            }
            inferFromInitial = false
        } else if (remainingMeters <= previous) {
            traveledMeters += previous - remainingMeters
        } else {
            // The SDK replaced the route without a signal; reset instead of
            // adding a negative delta.
            reroutes++
        }
        lastRemaining = remainingMeters
        samples++
    }

    /** Freeze the end point at arrival; ending later must not extend the trip. */
    fun finish(wallMillis: Long, elapsedMillis: Long) {
        if (!started || finished) return
        endedWall = wallMillis
        endedElapsed = elapsedMillis
    }

    fun snapshot(
        fallbackWall: Long,
        fallbackElapsed: Long,
        predictedSeconds: Int? = null,
        predictedMeters: Int? = null,
    ): TripMetrics? {
        if (!started) return null
        val endWall = if (finished) endedWall else fallbackWall
        val endElapsed = if (finished) endedElapsed else fallbackElapsed
        val seconds = ((endElapsed - startedElapsed).coerceAtLeast(0L) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return TripMetrics(
            startedAt = startedWall,
            endedAt = endWall.coerceAtLeast(startedWall),
            actualSeconds = seconds,
            actualMeters = traveledMeters.takeIf { samples > 0 },
            predictedSeconds = predictedSeconds,
            predictedMeters = predictedMeters,
            distanceSamples = samples,
            reroutes = reroutes,
        )
    }
}
