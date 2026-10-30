package com.onmyway.app.data.event

import com.onmyway.app.data.local.entity.BusinessEventOutbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EventAcknowledgementTest {
    private val event = BusinessEventOutbox(
        eventId = "11111111-1111-4111-8111-111111111111",
        correlationId = "22222222-2222-4222-8222-222222222222",
        kind = "NAVIGATION_STARTED", source = "device", occurredAt = 100,
    )

    private fun response() = JSONObject().put("event_id", event.eventId)
        .put("correlation_id", event.correlationId).put("status", "accepted").put("recorded", true)

    @Test fun acceptedResponseRequiresTheExactEventAndCorrelation() {
        val acknowledgement = EventAcknowledgement.fromJson(response())
        acknowledgement.requireMatches(event)
        assertEquals(event.eventId, acknowledgement.eventId)
    }

    @Test fun duplicateResponseAcknowledgesTheSameEvent() {
        val acknowledgement = EventAcknowledgement.fromJson(response().put("status", "duplicate"))
        acknowledgement.requireMatches(event)
        assertEquals("duplicate", acknowledgement.status)
    }

    @Test fun neitherAcceptedNorDuplicateCanAcknowledgeAnotherEventOrCorrelation() {
        for (status in listOf("accepted", "duplicate")) {
            for (field in listOf("event_id", "correlation_id")) {
                assertThrows(EventProtocolException::class.java) {
                    EventAcknowledgement.fromJson(response().put("status", status)
                        .put(field, "33333333-3333-4333-8333-333333333333")).requireMatches(event)
                }
            }
        }
    }

    @Test fun successfulHttpBodyMustActuallyConfirmStorage() {
        listOf(response().put("recorded", false), response().put("recorded", "true"),
            response().put("recorded", JSONObject.NULL), response().put("status", "executed"),
            response().put("status", "pending"), JSONObject()).forEach { value ->
            assertThrows(EventProtocolException::class.java) {
                EventAcknowledgement.fromJson(value).requireMatches(event)
            }
        }
    }

    @Test fun malformedOrMissingIdentityCannotAcknowledgeDelivery() {
        for (field in listOf("event_id", "correlation_id")) {
            listOf("", "not-a-uuid", 1, JSONObject.NULL).forEach { value ->
                assertThrows(EventProtocolException::class.java) {
                    EventAcknowledgement.fromJson(response().put(field, value)).requireMatches(event)
                }
            }
            assertThrows(EventProtocolException::class.java) {
                EventAcknowledgement.fromJson(response().apply { remove(field) }).requireMatches(event)
            }
        }
    }
}
