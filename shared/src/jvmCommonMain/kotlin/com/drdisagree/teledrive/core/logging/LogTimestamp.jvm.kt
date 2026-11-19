package com.drdisagree.teledrive.core.logging

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// DateTimeFormatter is immutable and thread-safe, unlike the SimpleDateFormat it replaces; the
// output is the same.
private val logTimestamps: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

internal actual fun formatLogTimestamp(epochMillis: Long): String =
    logTimestamps.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
