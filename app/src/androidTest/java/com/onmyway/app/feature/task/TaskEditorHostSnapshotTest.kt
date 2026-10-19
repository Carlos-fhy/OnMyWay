package com.onmyway.app.feature.task

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.entity.Errand
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskEditorHostSnapshotTest {
    @Test fun snapshotRoundTripPreservesEveryFieldIncludingPlannedDate() {
        val place = Place("print", "论文打印点", 120.01, 31.01, "东门")
        val task = Errand(
            id = "task-1",
            title = "打印论文",
            priority = 3,
            serviceMinutes = 20,
            deadline = "2026-10-20T10:00:00+08:00",
            placeJson = place.json().toString(),
            placeType = "PRINT_SHOP",
            status = "pending",
            createdAt = 1_000,
            updatedAt = 2_000,
            completedAt = 0,
            lastRemindedAt = 0,
            reminderCount = 0,
            reminderPending = false,
            plannedDate = "2026-10-19",
            reminderAt = 0,
        )

        assertEquals(task, errandFromEditorSnapshot(task.editorSnapshot()))
    }
}
