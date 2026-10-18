package com.onmyway.app.core.memory

import com.onmyway.app.data.local.entity.SuggestionFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryContextTest {
    private val now = 1_800_000_000_000L

    private fun evidence(mode: String, decision: String, offered: Int, selected: Int,
        band: String = "daytime", ageDays: Int = 0) =
        SuggestionFeedback(mode = mode, shownAt = now - ageDays * 86_400_000L,
            offeredTaskCount = 1, offeredExtraSeconds = offered, decision = decision,
            selectedTaskCount = if (decision.startsWith("DIRECT")) 0 else 1,
            selectedExtraSeconds = selected, timeBand = band)

    @Test fun learnsOnlyFromRepeatedConsistentChoicesForTheSameMode() {
        val choices = listOf(
            evidence("walking", "ACCEPTED", 240, 240),
            evidence("walking", "ADJUSTED", 300, 300),
            evidence("walking", "DIRECT_TIME", 900, 0),
            evidence("walking", "DIRECT_TIME", 840, 0),
            evidence("cycling", "ACCEPTED", 1200, 1200),
            evidence("cycling", "DIRECT", 1500, 0),
        )
        val walking = detourGuidance("walking", "daytime", 10, null, true, choices, now)
        val cycling = detourGuidance("cycling", "daytime", 10, null, true, choices, now)
        assertEquals(GuidanceSource.LEARNED, walking.source)
        assertEquals(9, walking.minutes)
        assertEquals(4, walking.evidenceCount)
        assertEquals(GuidanceSource.DEFAULT, cycling.source)
    }

    @Test fun explicitChoiceWinsAndLearningCanBePaused() {
        val choices = listOf(
            evidence("walking", "ACCEPTED", 240, 240),
            evidence("walking", "ACCEPTED", 300, 300),
            evidence("walking", "DIRECT_TIME", 840, 0),
            evidence("walking", "DIRECT_TIME", 900, 0),
        )
        assertEquals(18, detourGuidance("walking", "daytime", 10, 18, true, choices, now).minutes)
        assertEquals(GuidanceSource.EXPLICIT,
            detourGuidance("walking", "daytime", 10, 18, true, choices, now).source)
        assertEquals(GuidanceSource.DEFAULT,
            detourGuidance("walking", "daytime", 10, null, false, choices, now).source)
    }

    @Test fun conflictingAndOldChoicesDoNotForceAPreference() {
        val conflicting = listOf(
            evidence("walking", "ACCEPTED", 600, 600),
            evidence("walking", "ACCEPTED", 540, 540),
            evidence("walking", "DIRECT", 480, 0),
            evidence("walking", "DIRECT", 600, 0),
            evidence("walking", "DIRECT", 1800, 0, ageDays = 91),
        )
        val guidance = detourGuidance("walking", "daytime", 10, null, true, conflicting, now)
        assertEquals(GuidanceSource.DEFAULT, guidance.source)
        assertEquals(10, guidance.minutes)
        assertFalse(guidance.matchingTimeBand)
    }

    @Test fun timeBandUsesLocalChoicesWhenEnoughEvidenceExists() {
        val choices = listOf(
            evidence("walking", "ACCEPTED", 180, 180, "morning"),
            evidence("walking", "ACCEPTED", 240, 240, "morning"),
            evidence("walking", "DIRECT_TIME", 660, 0, "morning"),
            evidence("walking", "DIRECT_TIME", 720, 0, "morning"),
            evidence("walking", "ACCEPTED", 1500, 1500, "evening"),
            evidence("walking", "ACCEPTED", 1560, 1560, "evening"),
            evidence("walking", "DIRECT_TIME", 2100, 0, "evening"),
            evidence("walking", "DIRECT_TIME", 2160, 0, "evening"),
        )
        val morning = detourGuidance("walking", "morning", 10, null, true, choices, now)
        val evening = detourGuidance("walking", "evening", 10, null, true, choices, now)
        assertEquals(7, morning.minutes)
        assertEquals(30, evening.minutes)
        assertEquals(true, morning.matchingTimeBand)
        assertEquals(true, evening.matchingTimeBand)
    }
    @Test fun unspecifiedDirectChoiceDoesNotTeachATimeLimit() {
        val choices = listOf(
            evidence("walking", "ACCEPTED", 240, 240),
            evidence("walking", "ADJUSTED", 300, 300),
            evidence("walking", "DIRECT", 900, 0),
            evidence("walking", "DIRECT", 840, 0),
        )
        val guidance = detourGuidance("walking", "daytime", 10, null, true, choices, now)
        assertEquals(GuidanceSource.DEFAULT, guidance.source)
        assertEquals(10, guidance.minutes)
    }

}
