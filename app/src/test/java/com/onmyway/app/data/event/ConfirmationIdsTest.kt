package com.onmyway.app.data.event

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConfirmationIdsTest {
    @Test fun retryOfOneConfirmedVersionKeepsTheSameReceiptId() {
        assertEquals(
            ConfirmationIds.forVersion("plan", "plan-1", 2),
            ConfirmationIds.forVersion("plan", "plan-1", 2),
        )
    }

    @Test fun changedSubjectOrVersionUsesANewReceipt() {
        val receipt = ConfirmationIds.forVersion("plan", "plan-1", 1)
        assertNotEquals(receipt, ConfirmationIds.forVersion("plan", "plan-1", 2))
        assertNotEquals(receipt, ConfirmationIds.forVersion("plan", "plan-2", 1))
        assertNotEquals(receipt, ConfirmationIds.forVersion("proposal", "plan-1", 1))
    }

    @Test fun receiptIdsAreValidUuids() {
        val id = ConfirmationIds.forVersion("proposal", "draft-1", 1)
        assertEquals(id, UUID.fromString(id).toString())
    }
}
