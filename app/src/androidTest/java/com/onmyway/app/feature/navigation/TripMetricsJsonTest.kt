package com.onmyway.app.feature.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.core.model.TripMetrics
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripMetricsJsonTest {
    private val metrics = TripMetrics(
        startedAt = 1_760_000_000_000,
        endedAt = 1_760_000_660_000,
        actualSeconds = 660,
        actualMeters = 930,
        predictedSeconds = 600,
        predictedMeters = 900,
        distanceSamples = 42,
        reroutes = 1,
    )

    @Test fun metricsRoundTripInsideFinalRouteSnapshot() {
        val enriched = JSONObject(withNavigationMetrics(
            """{"stops":[],"total_seconds":600,"total_meters":900}""", metrics))

        assertEquals(600, enriched.optInt("total_seconds"))
        val stored = requireNotNull(enriched.optJSONObject("navigation_metrics"))
        assertEquals(60, stored.getInt("extra_seconds"))
        assertEquals(30, stored.getInt("extra_meters"))
        assertEquals("elapsed_realtime", stored.getString("duration_source"))
        assertEquals("amap_remaining_distance", stored.getString("distance_source"))
        assertEquals(metrics, tripMetricsFrom(enriched))
    }

    @Test fun missingMeasuredDistanceIsStoredAsNullNotAsPrediction() {
        val enriched = JSONObject(withNavigationMetrics(
            """{"total_seconds":600,"total_meters":900}""",
            metrics.copy(actualMeters = null)))

        val stored = requireNotNull(enriched.optJSONObject("navigation_metrics"))
        assertTrue(stored.isNull("actual_meters"))
        assertTrue(stored.isNull("extra_meters"))
        val parsed = requireNotNull(tripMetricsFrom(enriched))
        assertNull(parsed.actualMeters)
        assertEquals(930, metrics.actualMeters ?: -1)
    }

    @Test fun nullMetricsKeepTheOriginalSnapshotUnchanged() {
        val original = """{"total_seconds":600,"total_meters":900}"""
        assertEquals(original, withNavigationMetrics(original, null))
        assertNull(tripMetricsFrom(JSONObject(original)))
    }

    @Test fun malformedSnapshotIsReturnedUnchanged() {
        assertEquals("not-json", withNavigationMetrics("not-json", metrics))
    }

    @Test fun invalidMetricsShapeIsIgnored() {
        assertNull(tripMetricsFrom(JSONObject("""{"navigation_metrics":{}}""")))
        assertNull(tripMetricsFrom(JSONObject("""{"navigation_metrics":null}""")))
    }
}
