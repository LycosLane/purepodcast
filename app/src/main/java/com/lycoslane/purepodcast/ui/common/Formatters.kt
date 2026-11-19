package com.lycoslane.purepodcast.ui.common

import android.text.Html
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@Volatile
private var cachedLocale: Locale? = null
@Volatile
private var cachedDateFormatter: DateTimeFormatter? = null

private fun appendTwoDigits(sb: StringBuilder, value: Long) {
    if (value < 10L) sb.append('0')
    sb.append(value)
}

fun formatPlaybackSpeed(speed: Float, locale: Locale = Locale.getDefault()): String {
    val hundredths = (speed * 100f).roundToInt()
    val normalized = hundredths / 100f
    return if (hundredths % 10 == 0) {
        String.format(locale, "%.1fx", normalized)
    } else {
        String.format(locale, "%.2fx", normalized)
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return buildString(8) {
        if (hours > 0L) {
            append(hours)
            append(':')
            appendTwoDigits(this, minutes)
            append(':')
            appendTwoDigits(this, seconds)
        } else {
            append(minutes)
            append(':')
            appendTwoDigits(this, seconds)
        }
    }
}

@Synchronized
private fun dateFormatterFor(locale: Locale): DateTimeFormatter {
    val existing = cachedDateFormatter
    if (cachedLocale == locale && existing != null) return existing
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .also {
            cachedLocale = locale
            cachedDateFormatter = it
        }
}

fun formatPubDate(ms: Long): String {
    if (ms <= 0L) return ""
    val formatter = dateFormatterFor(Locale.getDefault())
    return Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(formatter)
}

fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    return Html.fromHtml(html.take(65_536), Html.FROM_HTML_MODE_COMPACT).toString().trim()
}
