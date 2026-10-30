package com.onmyway.app.data.event

import java.util.UUID

/**
 * Event identity follows the M2 contract: one stable UUID per observation.
 * A retry reuses the row's event id; a genuinely new observation gets a new id.
 */
internal object BusinessEventIds {
    fun forObservation(kind: String, subject: String, planVersion: Int = 0): String {
        val identity = if (planVersion > 0)
            "onmyway-business-event:v2:$kind:$subject:plan-version:$planVersion"
        else "onmyway-business-event:v1:$kind:$subject"
        return UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
    }

    fun newObservation(): String = UUID.randomUUID().toString()
}
