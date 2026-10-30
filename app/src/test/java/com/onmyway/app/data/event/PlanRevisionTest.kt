package com.onmyway.app.data.event

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanRevisionTest {
    @Test fun aNewPlanStartsAtOne() {
        assertEquals(PlanRevision("plan", 1), nextPlanRevision("plan", 0, 0, false) { "new" })
    }
    @Test fun restoredUnknownPlanGetsNewIdentityInsteadOfReusingServerVersionOne() {
        assertEquals(PlanRevision("new", 1), nextPlanRevision("plan", 0, 0, true) { "new" })
    }
    @Test fun upgradeUsesExistingReceiptEvenWhenLegacyTripHasNoVersion() {
        assertEquals(PlanRevision("plan", 5), nextPlanRevision("plan", 0, 4, true) { "new" })
    }
    @Test fun newConfirmationAdvancesHighestDurableVersion() {
        assertEquals(PlanRevision("plan", 4), nextPlanRevision("plan", 3, 2, false) { "new" })
    }
    @Test(expected = IllegalStateException::class) fun versionDoesNotOverflow() {
        nextPlanRevision("plan", Int.MAX_VALUE - 1, 0, false) { "new" }
    }
}
