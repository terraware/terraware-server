package com.terraformation.backend.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalAdjusters

/**
 * Returns the most recent ZonedDateTime in the past where the time of day is a certain value.
 *
 * For example, given a ZonedDateTime of Monday at 11:00, `atMostRecent(LocalTime.of(10, 0))` would
 * return Monday at 10:00, while `atMostRecent(LocalTime.of(12, 0))` would return Sunday at 12:00
 * (since Monday at 12:00 would be in the future relative to the original value).
 */
fun ZonedDateTime.atMostRecent(timeOfDay: LocalTime): ZonedDateTime {
  val atTimeOfDay = with(timeOfDay)
  return if (isBefore(atTimeOfDay)) {
    atTimeOfDay.minusDays(1)
  } else {
    atTimeOfDay
  }
}

/**
 * Returns the most recent ZonedDateTime in the past where the day of the week is a certain value.
 * If the original value is already on the requested day of the week, it is returned unaltered. For
 * example, given a ZonedDateTime of Monday at 11:00, `atMostRecent(MONDAY)` would return the
 * original time, while `atMostRecent(SUNDAY)` would subtract 1 day.
 */
fun ZonedDateTime.atMostRecent(dayOfWeek: DayOfWeek): ZonedDateTime {
  return with(TemporalAdjusters.previousOrSame(dayOfWeek))
}

/**
 * Converts an arbitrary time value to an [Instant]. This is just syntax sugar for [Instant.from] to
 * work more cleanly with `?.` expressions.
 */
fun TemporalAccessor.toInstant() = Instant.from(this)!!
