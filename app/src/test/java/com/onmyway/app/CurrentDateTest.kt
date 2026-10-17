package com.onmyway.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CurrentDateTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun nextDateWaitsUntilLocalMidnight() {
        assertEquals(1L, millisUntilNextLocalDate(Instant.parse("2026-10-16T15:59:59.999Z"), shanghai))
        assertEquals(Duration.ofHours(24).toMillis(),
            millisUntilNextLocalDate(Instant.parse("2026-10-16T16:00:00Z"), shanghai))
    }

    @Test fun springDayWaits23HoursInsteadOfAdding24Hours() {
        assertEquals(Duration.ofHours(23).toMillis(),
            millisUntilNextLocalDate(Instant.parse("2026-03-08T05:00:00Z"), ZoneId.of("America/New_York")))
    }

    @Test fun autumnDayWaits25HoursInsteadOfAdding24Hours() {
        assertEquals(Duration.ofHours(25).toMillis(),
            millisUntilNextLocalDate(Instant.parse("2026-11-01T04:00:00Z"), ZoneId.of("America/New_York")))
    }

    @Test fun skippedCalendarDateUsesNextValidStartOfDay() {
        val apia = ZoneId.of("Pacific/Apia")
        val now = Instant.parse("2011-12-30T09:59:59Z")
        val wait = millisUntilNextLocalDate(now, apia)
        assertEquals(1_000L, wait)
        assertEquals(LocalDate.of(2011, 12, 31), currentDate(now.plusMillis(wait), apia).date)
    }

    @Test fun timezoneChangeRecomputesDateAndKeepsZoneForDeadlineGrouping() {
        val now = Instant.parse("2026-10-16T16:30:00Z")
        val east = currentDate(now, shanghai)
        val west = currentDate(now, ZoneId.of("America/Los_Angeles"))
        assertEquals(LocalDate.of(2026, 10, 17), east.date)
        assertEquals(LocalDate.of(2026, 10, 16), west.date)
        val task = Errand(title = "打印论文", deadline = "2026-10-17T01:00:00+08:00")
        assertEquals("今天 · 10月17日", taskDateSections(listOf(task), "pending", east.date, east.zone).single().title)
        assertEquals("今天 · 10月16日", taskDateSections(listOf(task), "pending", west.date, west.zone).single().title)
    }

    @Test fun midnightMovesParcelToEarlierAndPrintToTodayWithoutChangingTasks() {
        val tasks = listOf(
            Errand(id = "parcel", title = "取快递", plannedDate = "2026-10-16"),
            Errand(id = "print", title = "打印论文", plannedDate = "2026-10-17"),
        )
        val before = currentDate(Instant.parse("2026-10-16T15:59:59Z"), shanghai)
        val after = currentDate(Instant.parse("2026-10-16T16:00:00Z"), shanghai)
        assertEquals(listOf("今天 · 10月16日", "明天 · 10月17日"),
            taskDateSections(tasks, "pending", before.date, before.zone).map { it.title })
        val refreshed = taskDateSections(tasks, "pending", after.date, after.zone)
        assertEquals(listOf("此前待办 · 10月16日", "今天 · 10月17日"), refreshed.map { it.title })
        assertEquals(tasks, refreshed.flatMap { it.tasks })
    }
}
