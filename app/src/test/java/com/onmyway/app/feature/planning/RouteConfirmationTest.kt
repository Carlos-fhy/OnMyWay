package com.onmyway.app.feature.planning

import org.junit.Test

class RouteConfirmationTest {
    @Test fun unchangedPreviewCanBeConfirmed() {
        validateRouteConfirmation(1000, 2000, true, true)
    }
    @Test(expected = IllegalStateException::class) fun expiredPreviewIsRejected() {
        validateRouteConfirmation(1000, 301001, true, true)
    }
    @Test(expected = IllegalStateException::class) fun changedTripIsRejected() {
        validateRouteConfirmation(1000, 2000, false, true)
    }
    @Test(expected = IllegalStateException::class) fun changedTasksAreRejected() {
        validateRouteConfirmation(1000, 2000, true, false)
    }
    @Test(expected = IllegalStateException::class) fun ClockMovingBackwardsRequiresRecalculation() {
        validateRouteConfirmation(2000, 1000, true, true)
    }
}
