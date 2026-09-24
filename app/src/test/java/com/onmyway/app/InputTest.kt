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
}
