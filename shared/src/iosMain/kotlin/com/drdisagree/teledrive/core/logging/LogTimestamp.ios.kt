package com.drdisagree.teledrive.core.logging

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

// NSDateFormatter is thread-safe since iOS 7. en_US_POSIX keeps the digits and the 24-hour clock
// fixed whatever the user's region settings are.
private val logTimestamps = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
}

internal actual fun formatLogTimestamp(epochMillis: Long): String =
    logTimestamps.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
