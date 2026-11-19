package com.drdisagree.teledrive.presentation.common

import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localeIdentifier
import platform.Foundation.timeIntervalSince1970

// iOS has no process-wide default locale to switch, so the formatters are rebuilt for the locale
// setFormattingLocale picks. Foundation formatters are thread-safe since iOS 7.
private var formattingLocale: NSLocale = NSLocale.currentLocale
private var formatters = LocaleFormatters(formattingLocale)

private class LocaleFormatters(locale: NSLocale) {
    val oneDecimal = NSNumberFormatter().apply {
        this.locale = locale
        numberStyle = NSNumberFormatterDecimalStyle
        usesGroupingSeparator = false
        minimumFractionDigits = 1u
        maximumFractionDigits = 1u
        // HALF_UP, like java.util.Formatter's "%.1f".
        roundingMode = NSNumberFormatterRoundHalfUp
    }
    val mediumDate = NSDateFormatter().apply {
        this.locale = locale
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterNoStyle
    }
    val mediumDateShortTime = NSDateFormatter().apply {
        this.locale = locale
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    }
    val monthDayYear = NSDateFormatter().apply {
        this.locale = locale
        dateFormat = "MMM d, yyyy"
    }
}

private fun Long.toNSDate(): NSDate = NSDate.dateWithTimeIntervalSince1970(this / 1000.0)

internal actual object PlatformFormat {

    actual fun oneDecimal(value: Double): String =
        formatters.oneDecimal.stringFromNumber(NSNumber(value)) ?: value.toString()

    actual fun clock(hours: Long, minutes: Long, seconds: Long): String {
        val mm = minutes.toString().padStart(2, '0')
        val ss = seconds.toString().padStart(2, '0')
        return if (hours > 0) "$hours:$mm:$ss" else "$minutes:$ss"
    }

    actual fun mediumDate(epochMillis: Long): String =
        formatters.mediumDate.stringFromDate(epochMillis.toNSDate())

    actual fun mediumDateShortTime(epochMillis: Long): String =
        formatters.mediumDateShortTime.stringFromDate(epochMillis.toNSDate())

    actual fun monthDayYear(epochMillis: Long): String =
        formatters.monthDayYear.stringFromDate(epochMillis.toNSDate())

    actual fun localDayStart(epochMillis: Long): Long {
        val start = NSCalendar.currentCalendar.startOfDayForDate(epochMillis.toNSDate())
        return (start.timeIntervalSince1970 * 1000.0).toLong()
    }
}

actual fun currentFormattingLocale(): Any = formattingLocale

actual fun setFormattingLocale(languageTag: String?, systemDefault: Any) {
    applyResourceLanguage(languageTag)
    val locale = languageTag?.let { NSLocale(localeIdentifier = it.replace('-', '_')) }
        ?: systemDefault as NSLocale
    if (locale.localeIdentifier == formattingLocale.localeIdentifier) return
    formattingLocale = locale
    formatters = LocaleFormatters(locale)
}

/**
 * Compose resources pick strings by Locale.current, which on iOS is NSLocale.preferredLanguages and
 * cannot be overridden in-process the way the desktop app's Locale.setDefault does. The per-app
 * "AppleLanguages" default is the standard iOS switch (Settings › Strato › Language writes the same
 * key); iOS reads it at launch, so a new language applies from the next start.
 */
private fun applyResourceLanguage(languageTag: String?) {
    val defaults = NSUserDefaults.standardUserDefaults
    if (languageTag != null) {
        defaults.setObject(listOf(languageTag), forKey = APPLE_LANGUAGES)
        defaults.setBool(true, forKey = SET_BY_APP)
    } else if (defaults.boolForKey(SET_BY_APP)) {
        // Back to "System": drop only what the app wrote, so a choice made in iOS Settings stays.
        defaults.removeObjectForKey(APPLE_LANGUAGES)
        defaults.removeObjectForKey(SET_BY_APP)
    }
}

private const val APPLE_LANGUAGES = "AppleLanguages"
private const val SET_BY_APP = "strato.appleLanguagesSetByApp"
