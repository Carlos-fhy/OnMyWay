package com.onmyway.app.feature.task

import com.onmyway.app.data.local.entity.Errand
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskDateSectionsTest {
    @Test fun pendingTasksUseDeadlinesAndUnscheduledTasksRemainVisible() {
        val today = LocalDate.of(2026, 9, 25)
        val tasks = listOf(
            Errand(id = "undated", title = "买水", deadline = "", createdAt = 1),
            Errand(id = "tomorrow", title = "打印", deadline = "2026-09-26T11:00:00+08:00", createdAt = 1),
            Errand(id = "overdue", title = "取件", deadline = "2026-09-24T23:00:00+08:00", createdAt = 1),
            Errand(id = "today", title = "交材料", deadline = "2026-09-25T15:00:00+08:00", createdAt = 1),
        )
        val sections = taskDateSections(tasks, "pending", today, ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf("overdue", "today", "tomorrow", "undated"), sections.flatMap { it.tasks }.map { it.id })
        assertEquals(listOf("此前待办 · 9月24日", "今天 · 9月25日", "明天 · 9月26日", "未安排日期"), sections.map { it.title })
    }

    @Test fun plannedDateGroupsTasksWithoutChangingDeadline() {
        val task = Errand(title = "打印论文", plannedDate = "2026-10-15", deadline = "2026-10-18T18:00:00+08:00")
        val sections = taskDateSections(listOf(task), "pending", LocalDate.of(2026, 10, 16), ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-10-15", sections.single().key)
        assertEquals("此前待办 · 10月15日", sections.single().title)
        assertEquals("2026-10-18T18:00:00+08:00", sections.single().tasks.single().deadline)
    }

    @Test fun completedTasksUseCompletionDateRatherThanDeadline() {
        val today = LocalDate.of(2026, 9, 25)
        val tasks = listOf(
            Errand(id = "old", title = "旧任务", status = "done", deadline = "2026-10-01T10:00:00+08:00",
                createdAt = 1, completedAt = 1_790_250_000_000),
            Errand(id = "new", title = "新任务", status = "done", deadline = "2026-09-01T10:00:00+08:00",
                createdAt = 1, completedAt = 1_790_337_600_000),
        )
        val sections = taskDateSections(tasks, "done", today, ZoneId.of("Asia/Shanghai"))
        assertEquals(listOf("new", "old"), sections.flatMap { it.tasks }.map { it.id })
    }
}
