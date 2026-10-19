package com.onmyway.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TripMetricsTest {
    @Test fun formatShowsActualAndPredictedValuesSeparately() {
        val metrics = TripMetrics(
            startedAt = 1_000, endedAt = 601_000, actualSeconds = 600,
            actualMeters = 930, predictedSeconds = 540, predictedMeters = 800,
        )
        assertEquals(
            "实际用时 10 分钟 · 930 米（当时预计 9 分钟 · 800 米）",
            formatTripMetrics(metrics),
        )
    }

    @Test fun formatWithoutPredictionDoesNotInventAComparison() {
        val metrics = TripMetrics(
            startedAt = 1_000, endedAt = 601_000, actualSeconds = 600, actualMeters = 930,
        )
        assertEquals("实际用时 10 分钟 · 930 米", formatTripMetrics(metrics))
    }

    @Test fun formatWithoutMeasuredDistanceKeepsTimeOnly() {
        val metrics = TripMetrics(
            startedAt = 1_000, endedAt = 601_000, actualSeconds = 600,
            predictedSeconds = 540,
        )
        assertEquals("实际用时 10 分钟（当时预计 9 分钟）", formatTripMetrics(metrics))
    }

    @Test fun extraValuesAreDerivedOnlyWhenBothSidesExist() {
        val metrics = TripMetrics(
            startedAt = 1_000, endedAt = 601_000, actualSeconds = 600,
            actualMeters = 930, predictedSeconds = 540, predictedMeters = 800,
        )
        assertEquals(60, metrics.extraSeconds)
        assertEquals(130, metrics.extraMeters)

        val unknownDistance = metrics.copy(actualMeters = null)
        assertEquals(60, unknownDistance.extraSeconds)
        assertEquals(null, unknownDistance.extraMeters)
    }
}
