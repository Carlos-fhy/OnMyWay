package com.onmyway.app.feature.navigation

import com.onmyway.app.core.model.TripMetrics
import org.json.JSONObject

/**
 * Persist measured trip values inside the final route snapshot. The object is
 * nested under `navigation_metrics`, so archived routes keep their existing
 * schema and older readers simply ignore it.
 */
internal fun withNavigationMetrics(resultJson: String, metrics: TripMetrics?): String {
    if (metrics == null) return resultJson
    return runCatching {
        val result = JSONObject(resultJson)
        result.put("navigation_metrics", JSONObject().apply {
            put("started_at", metrics.startedAt)
            put("ended_at", metrics.endedAt)
            put("actual_seconds", metrics.actualSeconds)
            put("actual_meters", metrics.actualMeters ?: JSONObject.NULL)
            put("predicted_seconds", metrics.predictedSeconds ?: JSONObject.NULL)
            put("predicted_meters", metrics.predictedMeters ?: JSONObject.NULL)
            put("extra_seconds", metrics.extraSeconds ?: JSONObject.NULL)
            put("extra_meters", metrics.extraMeters ?: JSONObject.NULL)
            put("distance_samples", metrics.distanceSamples)
            put("reroutes", metrics.reroutes)
            put("distance_source", "amap_remaining_distance")
            put("duration_source", "elapsed_realtime")
        })
        result.toString()
    }.getOrDefault(resultJson)
}

internal fun tripMetricsFrom(result: JSONObject): TripMetrics? {
    val metrics = result.optJSONObject("navigation_metrics") ?: return null
    val actualSeconds = metrics.optInt("actual_seconds", -1)
    if (actualSeconds < 0) return null
    fun optionalInt(key: String): Int? =
        if (!metrics.has(key) || metrics.isNull(key)) null
        else metrics.optInt(key, -1).takeIf { it >= 0 }
    return TripMetrics(
        startedAt = metrics.optLong("started_at", 0L),
        endedAt = metrics.optLong("ended_at", 0L),
        actualSeconds = actualSeconds,
        actualMeters = optionalInt("actual_meters"),
        predictedSeconds = optionalInt("predicted_seconds"),
        predictedMeters = optionalInt("predicted_meters"),
        distanceSamples = metrics.optInt("distance_samples", 0).coerceAtLeast(0),
        reroutes = metrics.optInt("reroutes", 0).coerceAtLeast(0),
    )
}
