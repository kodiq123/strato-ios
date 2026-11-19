package com.drdisagree.teledrive.presentation.common

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The JVM formats through the process-wide default Locale, which setFormattingLocale switches.
internal actual object PlatformFormat {

    actual fun oneDecimal(value: Double): String =
        String.format(Locale.getDefault(), "%.1f", value)

    actual fun clock(hours: Long, minutes: Long, seconds: Long): String =
        if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }

    actual fun mediumDate(epochMillis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))

    actual fun mediumDateShortTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(epochMillis))

    actual fun monthDayYear(epochMillis: Long): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMillis))

    actual fun localDayStart(epochMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = epochMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

actual fun currentFormattingLocale(): Any = Locale.getDefault()

actual fun setFormattingLocale(languageTag: String?, systemDefault: Any) {
    Locale.setDefault(languageTag?.let(Locale::forLanguageTag) ?: systemDefault as Locale)
}
