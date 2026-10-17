// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026
// Modified: Oct-17-2026 (day, time and countdown parts for the upcoming card)

package com.pandiyarajk.sportshub.ui

import com.pandiyarajk.sportshub.data.parseMillis
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The day part of a start: Today, Tomorrow, or Sat 18 Oct. Blank when the date is unreadable. */
fun startDay(iso: String, now: ZonedDateTime = ZonedDateTime.now()): String {
    val millis = parseMillis(iso) ?: return ""
    val t = Instant.ofEpochMilli(millis).atZone(now.zone)
    return when (ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
}

/** The clock part of a start in the phone's zone, such as 7:00 PM. */
fun startTime(iso: String, now: ZonedDateTime = ZonedDateTime.now()): String {
    val millis = parseMillis(iso) ?: return ""
    return Instant.ofEpochMilli(millis).atZone(now.zone)
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
}

/**
 * "Starts in 1 hr 48 min" within a day of the start, as Cricinfo shows it; blank further out,
 * and "Starting soon" once the time has passed but the feed still says scheduled.
 */
fun startsIn(iso: String, now: ZonedDateTime = ZonedDateTime.now()): String {
    val millis = parseMillis(iso) ?: return ""
    val minutes = (millis - now.toInstant().toEpochMilli()) / 60_000
    return when {
        minutes <= 0 -> "Starting soon"
        minutes < 60 -> "Starts in $minutes min"
        minutes < 24 * 60 -> "Starts in ${minutes / 60} hr ${minutes % 60} min"
        else -> ""
    }
}

/** Start time in the phone's own zone: Today 7:00 PM, Tomorrow 1:30 PM, or Sat 18 Oct, 7:00 PM. */
fun startLabel(iso: String, now: ZonedDateTime = ZonedDateTime.now()): String {
    val millis = parseMillis(iso) ?: return ""
    val t = Instant.ofEpochMilli(millis).atZone(now.zone)
    val time = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    return when (ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())) {
        0L -> "Today $time"
        1L -> "Tomorrow $time"
        -1L -> "Yesterday $time"
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.getDefault()))
    }
}
