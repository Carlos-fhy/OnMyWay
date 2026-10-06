package com.onmyway.app

internal enum class NavigationPhase {
    PREPARING, CALCULATING, READY, STARTING, NAVIGATING, REPLANNING, PAUSED, ARRIVED, FAILED, CLOSED;
    val needsService get() = this in setOf(STARTING, NAVIGATING, REPLANNING)
}
internal enum class NavigationEvent { CALCULATE, ROUTE_READY, START, SDK_STARTED, REPLAN, PAUSE, ARRIVE, FAIL, CLOSE }

/** SDK callbacks cannot revive a timed-out, paused or closed run. */
internal fun navigationTransition(state: NavigationPhase, event: NavigationEvent): NavigationPhase {
    if (state == NavigationPhase.CLOSED) return state
    return when (event) {
        NavigationEvent.CLOSE -> NavigationPhase.CLOSED
        NavigationEvent.FAIL -> NavigationPhase.FAILED
        NavigationEvent.CALCULATE -> if (!state.needsService) NavigationPhase.CALCULATING else state
        NavigationEvent.ROUTE_READY -> when (state) {
            NavigationPhase.CALCULATING -> NavigationPhase.READY
            NavigationPhase.REPLANNING -> NavigationPhase.NAVIGATING
            else -> state
        }
        NavigationEvent.START -> if (state == NavigationPhase.READY) NavigationPhase.STARTING else state
        NavigationEvent.SDK_STARTED -> if (state == NavigationPhase.STARTING) NavigationPhase.NAVIGATING else state
        NavigationEvent.REPLAN -> if (state == NavigationPhase.NAVIGATING) NavigationPhase.REPLANNING else state
        NavigationEvent.PAUSE -> if (state.needsService) NavigationPhase.PAUSED else state
        NavigationEvent.ARRIVE -> if (state in setOf(NavigationPhase.NAVIGATING, NavigationPhase.REPLANNING)) NavigationPhase.ARRIVED else state
    }
}

/** A suspended trip needs a new origin; elapsed realtime is unaffected by wall-clock edits. */
internal fun requiresFreshNavigationRoute(phase: NavigationPhase, originAgeMillis: Long): Boolean =
    phase == NavigationPhase.PAUSED ||
        (phase == NavigationPhase.READY && originAgeMillis !in 0..20_000)
