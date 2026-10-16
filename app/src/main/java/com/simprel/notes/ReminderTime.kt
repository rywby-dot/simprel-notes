package com.simprel.notes

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

fun reminderDates(now: ZonedDateTime): List<LocalDate> {
    val first = now.toLocalDate()
    return (0..ChronoUnit.DAYS.between(first, now.plusYears(1).toLocalDate()).toInt()).map { first.plusDays(it.toLong()) }
}

/** Today and Tomorrow are supplied by the UI; other dates include the localized weekday. */
fun reminderDateLabel(date: LocalDate, first: LocalDate, locale: java.util.Locale): String =
    date.format(java.time.format.DateTimeFormatter.ofPattern(
        if (date.year != first.year || date.month != first.month) "d MMM EEE" else "d EEE", locale))

/** Reject past times and nonexistent local times during the spring clock change. */
fun reminderTime(date: LocalDate, hour: Int, minute: Int, now: ZonedDateTime): Long? {
    val selected = date.atTime(hour, minute).atZone(now.zone)
    if (selected <= now || selected > now.plusYears(1) || selected.hour != hour || selected.minute != minute) return null
    return selected.toInstant().toEpochMilli()
}
