package com.drdisagree.teledrive.core.logging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The log line prefix must not change now that it is formatted through DateTimeFormatter. */
class LogTimestampTest {

    @Test
    fun `matches the SimpleDateFormat prefix the recorder used before`() {
        val legacy = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        listOf(0L, 1_700_000_000_123L, 1_795_000_000_007L, 4_102_444_799_999L).forEach { millis ->
            assertEquals(legacy.format(Date(millis)), formatLogTimestamp(millis))
        }
    }
}
