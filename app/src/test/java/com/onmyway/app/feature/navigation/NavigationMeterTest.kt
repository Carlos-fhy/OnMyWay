package com.onmyway.app.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMeterTest {
    @Test fun infersFirstLegAndAccumulatesDecreasingRemainingDistance() {
        val meter = NavigationMeter()
        meter.initialRoute(1_000)
        meter.start(wallMillis = 1_000_000, elapsedMillis = 10_000)
        meter.sample(950)
        meter.sample(800)
        meter.sample(700)
        meter.finish(wallMillis = 1_060_000, elapsedMillis = 700_000)

        val metrics = meter.snapshot(fallbackWall = 9_999_999, fallbackElapsed = 9_999_999,
            predictedSeconds = 600, predictedMeters = 280)

        requireNotNull(metrics)
        assertEquals(300, metrics.actualMeters)
        assertEquals(690, metrics.actualSeconds)
        assertEquals(1_000_000L, metrics.startedAt)
        assertEquals(1_060_000L, metrics.endedAt)
        assertEquals(3, metrics.distanceSamples)
        assertEquals(0, metrics.reroutes)
        assertEquals(90, metrics.extraSeconds)
        assertEquals(20, metrics.extraMeters)
    }

    @Test fun replacementRouteStartsNewBaselineWithoutCountingTheJump() {
        val meter = NavigationMeter()
        meter.initialRoute(1_000)
        meter.start(1_000, 0)
        meter.sample(900)
        meter.rerouted(2_000)
        meter.sample(1_900)
        meter.sample(1_850)
        meter.finish(2_000, 120_000)

        val metrics = requireNotNull(meter.snapshot(3_000, 999_000))

        assertEquals(250, metrics.actualMeters)
        assertEquals(1, metrics.reroutes)
        assertEquals(3, metrics.distanceSamples)
        assertEquals(120, metrics.actualSeconds)
    }

    @Test fun upwardSampleWithoutSignalResetsInsteadOfSubtracting() {
        val meter = NavigationMeter()
        meter.initialRoute(1_000)
        meter.start(1_000, 0)
        meter.sample(900)
        meter.sample(1_200)
        meter.sample(1_100)

        val metrics = requireNotNull(meter.snapshot(2_000, 60_000))

        assertEquals(200, metrics.actualMeters)
        assertEquals(1, metrics.reroutes)
    }

    @Test fun rerouteBeforeFirstSampleDoesNotReuseTheOldBaseline() {
        val meter = NavigationMeter()
        meter.initialRoute(5_000)
        meter.start(1_000, 0)
        meter.rerouted(1_200)
        meter.sample(1_150)
        meter.rerouted(null)
        meter.sample(1_000)

        val metrics = requireNotNull(meter.snapshot(2_000, 60_000))

        assertEquals(50, metrics.actualMeters)
        assertEquals(2, metrics.reroutes)
    }

    @Test fun noDistanceSamplesKeepsDistanceUnknownButRetainsDuration() {
        val meter = NavigationMeter()
        meter.initialRoute(800)
        meter.start(1_000, 5_000)
        meter.finish(2_000, 65_000)

        val metrics = requireNotNull(meter.snapshot(3_000, 99_000))

        assertNull(metrics.actualMeters)
        assertEquals(60, metrics.actualSeconds)
        assertEquals(0, metrics.distanceSamples)
    }

    @Test fun negativeSamplesAndSamplesBeforeStartAreIgnored() {
        val meter = NavigationMeter()
        meter.sample(500)
        meter.initialRoute(1_000)
        meter.sample(900)
        meter.start(1_000, 0)
        meter.sample(-1)
        meter.sample(850)

        val metrics = requireNotNull(meter.snapshot(2_000, 30_000))

        assertEquals(150, metrics.actualMeters)
        assertEquals(1, metrics.distanceSamples)
    }

    @Test fun snapshotIsNullBeforeStartAndFinishDoesNotExtendTheTrip() {
        val meter = NavigationMeter()
        meter.initialRoute(1_000)
        assertFalse(meter.started)
        assertNull(meter.snapshot(1_000, 1_000))

        meter.start(1_000, 10_000)
        assertTrue(meter.started)
        meter.finish(2_000, 70_000)
        meter.finish(9_000, 900_000)

        val metrics = requireNotNull(meter.snapshot(9_000, 900_000))
        assertEquals(60, metrics.actualSeconds)
        assertEquals(2_000L, metrics.endedAt)
    }
}
