package com.onmyway.app.feature.settings

import com.onmyway.app.feature.assistant.PersonalMemoryItem
import com.onmyway.app.feature.assistant.PersonalMemorySnapshot
import org.junit.Assert.*
import org.junit.Test

class MemorySettingsStateTest {
    private fun item(revision: Long = 3, status: String = "active") = PersonalMemoryItem(
        "walking", "preference", "以后出门我优先步行", "travel", "personal", "", null, null,
        "manual", "source", "以后出门我优先步行", emptyList(), revision, status, "",
    )
    private fun snapshot(storeId: String = "store-a", items: List<PersonalMemoryItem> = listOf(item())) =
        PersonalMemorySnapshot(storeId, 8, true, true, items, "")

    @Test fun unchangedItemCanBeSavedAfterUnrelatedMemoryUpdate() {
        val target = MemoryEditTarget("service-a", "store-a", "walking", 3)
        assertNull(memoryEditIssue(target, "service-a", snapshot().copy(revision = 15)))
    }

    @Test fun credentialChangeRejectsEvenNewDraftForSameStore() {
        val target = MemoryEditTarget("credential-a", "store-a", "", 0)
        assertNotNull(memoryEditIssue(target, "credential-b", snapshot()))
    }

    @Test fun replacedServerDatabaseRejectsNewDraft() {
        val target = MemoryEditTarget("service-a", "store-a", "", 0)
        assertNotNull(memoryEditIssue(target, "service-a", snapshot("store-b")))
    }

    @Test fun correctedOrForgottenItemCannotBeOverwrittenByOldEditor() {
        val target = MemoryEditTarget("service-a", "store-a", "walking", 3)
        assertNotNull(memoryEditIssue(target, "service-a", snapshot(items = listOf(item(4)))))
        assertNotNull(memoryEditIssue(target, "service-a", snapshot(items = listOf(item(status = "deleted")))))
        assertNotNull(memoryEditIssue(target, "service-a", snapshot(items = emptyList())))
    }

    @Test fun unavailableSnapshotRetainsDraftButPreventsSaving() {
        val target = MemoryEditTarget("service-a", "store-a", "walking", 3)
        assertNotNull(memoryEditIssue(target, "service-a", null))
    }
}
