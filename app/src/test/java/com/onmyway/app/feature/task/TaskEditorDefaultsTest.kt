package com.onmyway.app.feature.task

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskEditorDefaultsTest {
    private val now = LocalDateTime.of(2026, 10, 19, 10, 0)

    @Test fun deadlineFallsOnEndOfPlannedDay() {
        assertEquals(
            LocalDateTime.of(2026, 10, 20, 23, 59),
            defaultDeadlineAfter("2026-10-20", now),
        )
    }

    @Test fun blankOrInvalidPlanFallsBackToAnHourLater() {
        assertEquals(now.plusHours(1), defaultDeadlineAfter("", now))
        assertEquals(now.plusHours(1), defaultDeadlineAfter("not-a-date", now))
    }

    @Test fun deadlineNeverDefaultsIntoThePast() {
        assertEquals(now.plusHours(1), defaultDeadlineAfter("2026-10-18", now))
        val lateNow = LocalDateTime.of(2026, 10, 19, 23, 59, 30)
        assertEquals(lateNow.plusHours(1), defaultDeadlineAfter("2026-10-19", lateNow))
    }
}
