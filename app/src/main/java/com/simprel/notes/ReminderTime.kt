package com.simprel.notes

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

fun reminderDates(now: ZonedDateTime): List<LocalDate> {
    val first = now.toLocalDate()
    return (0..ChronoUnit.DAYS.between(first, now.plusYears(1).toLocalDate()).toInt()).map { first.plusDays(it.toLong()) }
}

/** Reject past times and nonexistent local times during the spring clock change. */
fun reminderTime(date: LocalDate, hour: Int, minute: Int, now: ZonedDateTime): Long? {
    val selected = date.atTime(hour, minute).atZone(now.zone)
    if (selected <= now || selected > now.plusYears(1) || selected.hour != hour || selected.minute != minute) return null
    return selected.toInstant().toEpochMilli()
}
