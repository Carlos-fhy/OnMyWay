package com.onmyway.app.feature.task

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 截止时间未填写时的默认值：计划日当天 23:59，且必须晚于当前时间。
 * 没有计划日期或日期无效时，退回当前时间后一小时。
 */
internal fun defaultDeadlineAfter(plannedDate: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
    val plannedEnd = runCatching { LocalDate.parse(plannedDate) }.getOrNull()?.atTime(23, 59)
    return plannedEnd?.takeIf { it.isAfter(now) } ?: now.plusHours(1)
}
