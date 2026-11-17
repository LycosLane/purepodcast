package com.lycoslane.purepodcast.ui.common

import android.text.Html
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatDuration(ms: Long): String {
    if (ms <= 0L) return String.format(Locale.getDefault(), "%d:%02d", 0, 0)
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

fun formatPubDate(ms: Long): String {
    if (ms <= 0L) return ""
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
    return Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(formatter)
}

fun htmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    return Html.fromHtml(html.take(65_536), Html.FROM_HTML_MODE_COMPACT).toString().trim()
}
