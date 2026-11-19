package com.drdisagree.teledrive.presentation.common

import com.drdisagree.teledrive.core.common.nowMillis
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object Formatters {

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val exponent = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
        val scaled = value / 1024.0.pow(exponent)
        return if (exponent == 0) {
            "$value B"
        } else {
            "${PlatformFormat.oneDecimal(scaled)} ${units[exponent]}"
        }
    }

    fun badgeCount(count: Int): String =
        if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString()

    fun speed(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

    fun percent(fraction: Float): String =
        "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"

    fun date(epochMillis: Long): String = PlatformFormat.mediumDate(epochMillis)

    fun dayStart(epochMillis: Long): Long = PlatformFormat.localDayStart(epochMillis)

    fun dayBucket(dayStartMillis: Long, now: Long = nowMillis()): DayBucket {
        val days = ((dayStart(now) - dayStartMillis) / DAY_MILLIS).toInt()
        return when {
            days <= 0 -> DayBucket.Today
            days == 1 -> DayBucket.Yesterday
            days <= 7 -> DayBucket.DaysAgo(days)
            else -> DayBucket.Absolute(PlatformFormat.monthDayYear(dayStartMillis))
        }
    }

    fun dateTime(epochMillis: Long): String = PlatformFormat.mediumDateShortTime(epochMillis)

    fun duration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return PlatformFormat.clock(hours, minutes, seconds)
    }

    fun eta(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    private const val MAX_BADGE_COUNT = 99
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun relativeAge(epochMillis: Long, now: Long = nowMillis()): AgeBucket {
        val elapsed = (now - epochMillis).coerceAtLeast(0L)
        val minutes = elapsed / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1L -> AgeBucket.JustNow
            minutes < 60L -> AgeBucket.Minutes(minutes.toInt())
            hours < 24L -> AgeBucket.Hours(hours.toInt())
            days < 30L -> AgeBucket.Days(days.toInt())
            else -> AgeBucket.Longer(epochMillis)
        }
    }
}

/**
 * The locale-sensitive part of [Formatters]. Everything here follows the formatting locale set by
 * [setFormattingLocale], which tracks the in-app language.
 */
internal expect object PlatformFormat {
    /** [value] with exactly one fraction digit and no grouping, e.g. "1.5" or "1,5". */
    fun oneDecimal(value: Double): String

    /** "h:mm:ss" when [hours] > 0, otherwise "m:ss". */
    fun clock(hours: Long, minutes: Long, seconds: Long): String

    /** Medium-style date, e.g. "Nov 19, 2026". */
    fun mediumDate(epochMillis: Long): String

    /** Medium-style date with short-style time. */
    fun mediumDateShortTime(epochMillis: Long): String

    /** The fixed pattern "MMM d, yyyy" with localized month names. */
    fun monthDayYear(epochMillis: Long): String

    /** Local midnight of the day containing [epochMillis]. */
    fun localDayStart(epochMillis: Long): Long
}

/** Opaque snapshot of the current formatting locale, to restore later with [setFormattingLocale]. */
expect fun currentFormattingLocale(): Any

/**
 * Formats with [languageTag] from now on, or with [systemDefault] again when it is null. Also
 * steers the language Compose resources resolve strings in (on iOS from the next launch).
 */
expect fun setFormattingLocale(languageTag: String?, systemDefault: Any)
