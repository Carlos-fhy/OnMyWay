package com.onmyway.app.core.context

import com.onmyway.app.core.model.Place
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextOriginTest {
    private val origin = Place("origin", "教学楼", 116.4, 39.9)
    private val observedAt = 1_800_000_000_000L
    private fun request(location: ContextLocation) = JSONObject().put("origin", origin.json())
        .put("context_snapshot", ContextSnapshot.capture(emptyList(), null, location = location,
            now = observedAt + 20_000).json())

    @Test fun reopeningKeepsCachedObservationTimeAndDoesNotTurnItIntoManualSelection() {
        val location = ContextLocation("cached", observedAt, origin.latitude, origin.longitude, 12.0)
        assertEquals(location, restoredContextLocation(request(location)))
    }

    @Test fun explicitSelectedOriginRemainsSelectedWithoutInventedGpsTime() {
        val location = ContextLocation("selected", latitude = origin.latitude, longitude = origin.longitude)
        assertEquals(location, restoredContextLocation(request(location)))
    }

    @Test fun missingOrMismatchedOriginEvidenceRemainsUnknown() {
        assertEquals(ContextLocation(), restoredContextLocation(JSONObject().put("origin", origin.json())))
        val original = request(ContextLocation("device", observedAt, origin.latitude, origin.longitude))
        original.put("origin", origin.copy(longitude = 116.7).json())
        assertEquals(ContextLocation(), restoredContextLocation(original))
    }
}
