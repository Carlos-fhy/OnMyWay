package com.onmyway.app.core.context

import org.json.JSONObject

/** A restored route must retain the original observation time, never the restore time. */
internal fun restoredContextLocation(request: JSONObject): ContextLocation = runCatching {
    val snapshot = request.optJSONObject("context_snapshot") ?: return ContextLocation()
    val location = ContextSnapshot.fromJson(snapshot).json().getJSONObject("location")
    if (location.getString("source") == "unknown") return ContextLocation()
    val origin = request.getJSONObject("origin")
    if (origin.getDouble("latitude") != location.getDouble("latitude") ||
        origin.getDouble("longitude") != location.getDouble("longitude")) return ContextLocation()
    ContextLocation(location.getString("source"),
        if (location.isNull("observed_at")) null else location.getLong("observed_at"),
        location.getDouble("latitude"), location.getDouble("longitude"),
        if (location.isNull("accuracy_m")) null else location.getDouble("accuracy_m"))
}.getOrDefault(ContextLocation())
