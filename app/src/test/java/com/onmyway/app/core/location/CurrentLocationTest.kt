package com.onmyway.app.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationTest {
    private val freshLimitNanos = FRESH_FIX_MAX_AGE_MILLIS * 1_000_000L
    private val cachedLimitNanos = CACHED_FIX_MAX_AGE_MILLIS * 1_000_000L

    @Test fun recentAccurateFixIsUsableAtThreshold() {
        assertTrue(usablePlanningFix(freshLimitNanos, 100f))
        assertTrue(usablePlanningFix(0, 0f))
    }

    @Test fun staleImpreciseOrUnknownFixCannotPriceADetour() {
        assertFalse(usablePlanningFix(freshLimitNanos + 1, 20f))
        assertFalse(usablePlanningFix(-1, 20f))
        assertFalse(usablePlanningFix(1_000_000_000L, 101f))
        assertFalse(usablePlanningFix(1_000_000_000L, null))
        assertFalse(usablePlanningFix(1_000_000_000L, Float.NaN))
    }

    @Test fun cachedFixMayBeOlderButStillNeedsAccuracy() {
        val oneHour = 60L * 60L * 1_000_000_000L
        assertTrue(usableCachedFix(oneHour, 30f))
        assertTrue(usableCachedFix(cachedLimitNanos, 100f))
        assertFalse(usableCachedFix(cachedLimitNanos + 1, 30f))
        assertFalse(usableCachedFix(oneHour, 101f))
        assertFalse(usableCachedFix(oneHour, null))
    }

    @Test fun quickCachedWindowStopsWaitingForGps() {
        val quickLimitNanos = QUICK_CACHED_FIX_MAX_AGE_MILLIS * 1_000_000L
        assertTrue(usableCachedFix(2 * 60_000_000_000L, 23f, quickLimitNanos))
        assertFalse(usableCachedFix(quickLimitNanos + 1, 23f, quickLimitNanos))
    }

    @Test fun cachedAgeIsShownInReadableUnits() {
        assertEquals("不到 1 分钟", readableLocationAge(0))
        assertEquals("5 分钟", readableLocationAge(5 * 60_000L))
        assertEquals("3 小时", readableLocationAge(3 * 3_600_000L))
        assertEquals("1 天", readableLocationAge(25 * 3_600_000L))
    }
}
