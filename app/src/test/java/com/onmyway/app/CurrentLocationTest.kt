package com.onmyway.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationTest {
    @Test fun recentAccurateFixIsUsableAtThreshold() {
        assertTrue(usablePlanningFix(20_000_000_000L, 100f))
        assertTrue(usablePlanningFix(0, 0f))
    }

    @Test fun staleImpreciseOrUnknownFixCannotPriceADetour() {
        assertFalse(usablePlanningFix(20_000_000_001L, 20f))
        assertFalse(usablePlanningFix(-1, 20f))
        assertFalse(usablePlanningFix(1_000_000_000L, 101f))
        assertFalse(usablePlanningFix(1_000_000_000L, null))
        assertFalse(usablePlanningFix(1_000_000_000L, Float.NaN))
    }
}
