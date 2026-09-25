package com.onmyway.app

import org.junit.Assert.*
import org.junit.Test

class InputTest {
    @Test fun optionalTimeStaysOptional() { assertNull(parseTime("")) }
    @Test fun invalidCalendarDateIsRejected() {
        assertThrows(java.time.format.DateTimeParseException::class.java) { parseTime("2026-02-30 12:00") }
    }
    @Test fun serverAddressRejectsCredentialsAndQuery() {
        assertEquals("http://127.0.0.1:8000", Api.validateBase(" http://127.0.0.1:8000/ "))
        assertThrows(IllegalArgumentException::class.java) { Api.validateBase("http://user:password@host") }
        assertThrows(IllegalArgumentException::class.java) { Api.validateBase("http://host?key=secret") }
    }
    @Test fun formatsDurationCorrectly() {
        assertEquals("0 分钟", formatDuration(0))
        assertEquals("3 分钟", formatDuration(150))
        assertEquals("59 分钟", formatDuration(3540))
        assertEquals("1 小时", formatDuration(3600))
        assertEquals("1 小时 5 分钟", formatDuration(3900))
        assertEquals("2 小时 30 分钟", formatDuration(9000))
    }
    @Test fun formatsDistanceCorrectly() {
        assertEquals("0 米", formatDistance(0))
        assertEquals("500 米", formatDistance(500))
        assertEquals("999 米", formatDistance(999))
        assertEquals("1.0 公里", formatDistance(1000))
        assertEquals("1.5 公里", formatDistance(1500))
        assertEquals("12.3 公里", formatDistance(12345))
    }
}
