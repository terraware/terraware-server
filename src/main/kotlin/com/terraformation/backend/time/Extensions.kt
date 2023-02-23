package com.terraformation.backend.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAccessor

/**
 * Returns the next ZonedDateTime in the future where the time of day is a certain value.
 *
 * For example, given a ZonedDateTime of Monday at 11:00, `atNext(LocalDateTime.of(10, 0))` would
 * return Tuesday at 10:00, while `atNext(LocalDateTime.of(11, 30))` would return Monday at 11:30.
 */
fun ZonedDateTime.atNext(timeOfDay: LocalTime): ZonedDateTime {
  val atTimeOfDay = with(timeOfDay)
  return if (isBefore(atTimeOfDay)) {
    atTimeOfDay
  } else {
    atTimeOfDay.plusDays(1)
  }
}

/**
 * Converts an arbitrary time value to an [Instant]. This is just syntax sugar for [Instant.from] to
 * work more cleanly with `?.` expressions.
 */
fun TemporalAccessor.toInstant() = Instant.from(this)!!

/** The date's calendar quarter, from 1 to 4 inclusive. */
val ZonedDateTime.quarter: Int
  get() = (monthValue + 2) / 3
