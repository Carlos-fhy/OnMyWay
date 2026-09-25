package com.onmyway.app

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class TaskDateSection(val key: String, val title: String, val tasks: List<Errand>)

/** Pending tasks use their deadline; completed tasks use completion time. No deadline stays visible. */
internal fun taskDateSections(
    tasks: List<Errand>,
    status: String,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<TaskDateSection> {
    fun taskDay(task: Errand): LocalDate? = when (status) {
        "pending" -> task.deadline.takeIf(String::isNotBlank)?.let {
            runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
        }
        "done" -> Instant.ofEpochMilli((task.completedAt.takeIf { it > 0 } ?: task.updatedAt.takeIf { it > 0 } ?: task.createdAt))
            .atZone(zone).toLocalDate()
        else -> Instant.ofEpochMilli(task.createdAt).atZone(zone).toLocalDate()
    }
    val dayFormat = DateTimeFormatter.ofPattern("M月d日")
    val fullFormat = DateTimeFormatter.ofPattern("yyyy年M月d日")
    val grouped = tasks.filter { it.status == status }.groupBy(::taskDay)
    val dated = grouped.keys.filterNotNull().sortedWith(if (status == "pending") compareBy { it } else compareByDescending { it })
    return (dated + if (null in grouped) listOf(null) else emptyList()).map { day ->
        val title = when {
            day == null -> "未设截止时间"
            status == "pending" && day.isBefore(today) -> "已逾期 · ${day.format(dayFormat)}"
            day == today -> "今天 · ${day.format(dayFormat)}"
            day == today.plusDays(1) -> "明天 · ${day.format(dayFormat)}"
            else -> day.format(fullFormat)
        }
        val group = grouped.getValue(day)
        TaskDateSection(day?.toString() ?: "none", title,
            if (status == "pending") group.sortedWith(compareByDescending<Errand> { it.priority }.thenBy { it.deadline })
            else group.sortedByDescending { if (status == "done") it.completedAt else it.createdAt })
    }
}
