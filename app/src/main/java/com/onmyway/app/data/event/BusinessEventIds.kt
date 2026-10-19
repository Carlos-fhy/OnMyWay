package com.onmyway.app.data.event

import java.util.UUID

/**
 * Event identity follows the M2 contract: one stable UUID per observation.
 * A retry reuses the row's event id; a genuinely new observation gets a new id.
 */
internal object BusinessEventIds {
    fun forObservation(kind: String, subject: String): String = UUID.nameUUIDFromBytes(
        "onmyway-business-event:v1:$kind:$subject".toByteArray(Charsets.UTF_8),
    ).toString()

    fun newObservation(): String = UUID.randomUUID().toString()
}
