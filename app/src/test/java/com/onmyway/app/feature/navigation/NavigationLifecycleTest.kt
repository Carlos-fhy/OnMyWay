package com.onmyway.app.feature.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationLifecycleTest {
    @Test fun acceptedStartRequiresSdkCallback() {
        var state = navigationTransition(NavigationPhase.READY, NavigationEvent.START)
        assertEquals(NavigationPhase.STARTING, state)
        assertTrue(state.needsService)
        state = navigationTransition(state, NavigationEvent.SDK_STARTED)
        assertEquals(NavigationPhase.NAVIGATING, state)
        assertEquals(state, navigationTransition(state, NavigationEvent.SDK_STARTED))
    }
    @Test fun timeoutDoesNotAcceptLateSuccessOrStartCallbacks() {
        val failed = navigationTransition(NavigationPhase.STARTING, NavigationEvent.FAIL)
        assertFalse(failed.needsService)
        assertEquals(failed, navigationTransition(failed, NavigationEvent.SDK_STARTED))
        assertEquals(failed, navigationTransition(failed, NavigationEvent.ROUTE_READY))
        assertEquals(NavigationPhase.CALCULATING, navigationTransition(failed, NavigationEvent.CALCULATE))
    }
    @Test fun explicitPauseCannotBeUndoneByAnSdkCallback() {
        val paused = navigationTransition(NavigationPhase.NAVIGATING, NavigationEvent.PAUSE)
        assertFalse(paused.needsService)
        assertEquals(paused, navigationTransition(paused, NavigationEvent.SDK_STARTED))
        assertEquals(paused, navigationTransition(paused, NavigationEvent.ROUTE_READY))
        assertEquals(paused, navigationTransition(paused, NavigationEvent.START))
        assertEquals(NavigationPhase.CALCULATING, navigationTransition(paused, NavigationEvent.CALCULATE))
    }
    @Test fun replanningKeepsServiceUntilSuccessOrFailure() {
        val replanning = navigationTransition(NavigationPhase.NAVIGATING, NavigationEvent.REPLAN)
        assertTrue(replanning.needsService)
        assertEquals(NavigationPhase.NAVIGATING, navigationTransition(replanning, NavigationEvent.ROUTE_READY))
        assertEquals(NavigationPhase.FAILED, navigationTransition(replanning, NavigationEvent.FAIL))
    }
    @Test fun arrivalAndCloseReleaseServiceAndRejectStaleEvents() {
        assertFalse(navigationTransition(NavigationPhase.NAVIGATING, NavigationEvent.ARRIVE).needsService)
        val closed = navigationTransition(NavigationPhase.NAVIGATING, NavigationEvent.CLOSE)
        NavigationEvent.entries.forEach { assertEquals(closed, navigationTransition(closed, it)) }
    }
    @Test fun arrivalCannotBeOverwrittenByLateFailureOrCalculation() {
        val arrived = navigationTransition(NavigationPhase.NAVIGATING, NavigationEvent.ARRIVE)
        for (event in NavigationEvent.entries.filter { it != NavigationEvent.CLOSE }) {
            assertEquals("late $event must preserve arrival", arrived, navigationTransition(arrived, event))
        }
        assertEquals(NavigationPhase.CLOSED, navigationTransition(arrived, NavigationEvent.CLOSE))
    }
    @Test fun pausedNavigationIgnoresLateFailure() {
        assertEquals(NavigationPhase.PAUSED, navigationTransition(NavigationPhase.PAUSED, NavigationEvent.FAIL))
    }
    @Test fun sdkArrivalWaitsForObservedStartButNotForItsDatabaseCommit() {
        assertFalse(acceptsNavigationArrival(NavigationPhase.STARTING, false))
        assertTrue(acceptsNavigationArrival(NavigationPhase.STARTING, true))
        assertFalse(acceptsNavigationArrival(NavigationPhase.FAILED, true))
        assertFalse(acceptsNavigationArrival(NavigationPhase.CLOSED, true))
        assertFalse(shouldPauseRecordedNavigationStart(NavigationPhase.ARRIVED, true, false))
        assertTrue(shouldPauseRecordedNavigationStart(NavigationPhase.PAUSED, true, false))
        assertTrue(shouldPauseRecordedNavigationStart(NavigationPhase.STARTING, false, false))
    }
    @Test fun staleServiceCommandCannotStopCurrentNavigation() {
        assertEquals(NavigationServiceCommand.IGNORE,
            navigationServiceCommand("current", 4, true, "previous", 2))
        assertEquals(NavigationServiceCommand.IGNORE,
            navigationServiceCommand("current", 4, true, "current", 3))
        assertEquals(NavigationServiceCommand.ACCEPT,
            navigationServiceCommand("current", 4, true, "current", 4))
        assertEquals(NavigationServiceCommand.STOP,
            navigationServiceCommand("current", 4, false, "current", 4))
        assertEquals(NavigationServiceCommand.STOP,
            navigationServiceCommand(null, -1, false, "previous", 2))
    }
    @Test fun duplicateCalculationCallbackDoesNotRestartNavigation() {
        val starting = navigationTransition(NavigationPhase.READY, NavigationEvent.START)
        assertEquals(starting, navigationTransition(starting, NavigationEvent.ROUTE_READY))
        assertEquals(NavigationPhase.NAVIGATING,
            navigationTransition(NavigationPhase.NAVIGATING, NavigationEvent.ROUTE_READY))
    }
    @Test fun pausedRouteAlwaysRequiresNewOrigin() {
        assertTrue(requiresFreshNavigationRoute(NavigationPhase.PAUSED, 0))
        assertTrue(requiresFreshNavigationRoute(NavigationPhase.PAUSED, 60_000))
    }
    @Test fun preparedRouteExpiresWithoutInterruptingActiveNavigation() {
        assertFalse(requiresFreshNavigationRoute(NavigationPhase.READY, 20_000))
        assertTrue(requiresFreshNavigationRoute(NavigationPhase.READY, 20_001))
        assertTrue(requiresFreshNavigationRoute(NavigationPhase.READY, -1))
        assertFalse(requiresFreshNavigationRoute(NavigationPhase.NAVIGATING, 60_000))
        assertFalse(requiresFreshNavigationRoute(NavigationPhase.REPLANNING, 60_000))
    }
}
