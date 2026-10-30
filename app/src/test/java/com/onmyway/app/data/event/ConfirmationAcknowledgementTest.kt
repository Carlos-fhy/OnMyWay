package com.onmyway.app.data.event

import com.onmyway.app.data.local.entity.ConfirmationOutbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfirmationAcknowledgementTest {
    private val receipt = ConfirmationOutbox(
        receiptId = "11111111-1111-4111-8111-111111111111", subjectType = "plan",
        subjectId = "plan-1", subjectVersion = 2,
        correlationId = "22222222-2222-4222-8222-222222222222", confirmedAt = 100,
    )

    private fun response() = JSONObject()
        .put("receipt_id", receipt.receiptId).put("subject_type", receipt.subjectType)
        .put("subject_id", receipt.subjectId).put("subject_version", receipt.subjectVersion)
        .put("correlation_id", receipt.correlationId).put("status", "accepted")
        .put("recorded", true).put("recorded_is_not_executed", true)

    @Test fun acceptedAcknowledgementRequiresExactIdentity() {
        val acknowledgement = ConfirmationAcknowledgement.fromJson(response())
        acknowledgement.requireMatches(receipt)
        assertEquals(receipt.receiptId, acknowledgement.receiptId)
    }

    @Test fun duplicateMayReferToTheFirstStoredReceiptId() {
        val acknowledgement = ConfirmationAcknowledgement.fromJson(response()
            .put("status", "duplicate")
            .put("receipt_id", "33333333-3333-4333-8333-333333333333"))
        acknowledgement.requireMatches(receipt)
        assertEquals("duplicate", acknowledgement.status)
    }

    @Test fun acceptedDifferentReceiptIdMustNotAcknowledgeTheLocalRow() {
        assertThrows(ConfirmationProtocolException::class.java) {
            ConfirmationAcknowledgement.fromJson(response()
                .put("receipt_id", "33333333-3333-4333-8333-333333333333")).requireMatches(receipt)
        }
    }

    @Test fun wrongSubjectVersionOrCorrelationMustNotAcknowledgeDelivery() {
        listOf("subject_type" to "proposal", "subject_id" to "other-plan", "subject_version" to 1,
            "correlation_id" to "33333333-3333-4333-8333-333333333333").forEach { (key, value) ->
            assertThrows(ConfirmationProtocolException::class.java) {
                ConfirmationAcknowledgement.fromJson(response().put(key, value)).requireMatches(receipt)
            }
        }
    }

    @Test fun duplicateStillRequiresMatchingSubjectAndVersion() {
        assertThrows(ConfirmationProtocolException::class.java) {
            ConfirmationAcknowledgement.fromJson(response().put("status", "duplicate")
                .put("subject_version", 3)).requireMatches(receipt)
        }
    }

    @Test fun malformedOrUnrecordedSuccessBodyIsRejected() {
        listOf(response().put("recorded", false), response().put("recorded", "true"),
            response().put("recorded_is_not_executed", false), response().put("status", "executed"),
            response().put("receipt_id", ""), response().put("subject_version", "2"),
            response().put("subject_version", 2.5), JSONObject()).forEach { value ->
            assertThrows(ConfirmationProtocolException::class.java) {
                ConfirmationAcknowledgement.fromJson(value).requireMatches(receipt)
            }
        }
    }
}
