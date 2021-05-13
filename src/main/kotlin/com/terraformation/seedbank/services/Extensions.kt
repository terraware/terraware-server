package com.terraformation.seedbank.services

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalAdjusters
import java.util.EnumSet
import org.jooq.Field
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

// One-off extension functions for third-party classes. Extensions that are only useful in the
// context of a specific bit of application code should live alongside that code, but functions that
// are generally useful and that can't be logically grouped together can go here.

/**
 * Returns a logger object that is associated with the receiver's class. Typical usage is to create
 * a private logger field on a class, like
 *
 * ```
 * class Foo {
 *   private val log = perClassLogger()
 *   fun doWork() {
 *     log.info("Doing work now")
 *   }
 * }
 * ```
 */
inline fun <reified T : Any> T.perClassLogger(): Logger {
  val loggingClass =
      if (javaClass.kotlin.isCompanion) {
        javaClass.enclosingClass
      } else {
        javaClass
      }

  // Remove CGLIB suffixes from classes Spring decides to rewrite
  val className = loggingClass.canonicalName.substringBefore("\$\$")

  return LoggerFactory.getLogger(className)
}

/** Returns an empty EnumSet without having to pass in a `Class` explicitly. */
inline fun <reified T : Enum<T>> emptyEnumSet(): EnumSet<T> = EnumSet.noneOf(T::class.java)

fun Logger.log(level: Level, text: String) {
  when (level) {
    Level.TRACE -> this.trace(text)
    Level.DEBUG -> this.debug(text)
    Level.INFO -> this.info(text)
    Level.WARN -> this.warn(text)
    Level.ERROR -> this.error(text)
  }
}

/** Transforms a Collection to null if it is empty. */
fun <T : Collection<*>> T.orNull(): T? = ifEmpty { null }

/** Tests two nullable BigDecimal values for equality ignoring their scale. */
fun BigDecimal?.equalsIgnoreScale(other: BigDecimal?) =
    this == null && other == null || this != null && other != null && compareTo(other) == 0

/**
 * Generates an equality condition for a jOOQ field if the value is non-null, or an IS NULL if the
 * value is null.
 */
fun <T> Field<T>.eqOrIsNull(value: T) = if (value != null) eq(value) else isNull

/**
 * Logs how long a function takes to run. This isn't a robust performance monitoring tool; use a
 * real metrics library if you want to track statistics over time. But it's useful for debugging.
 */
fun <T> Logger.debugWithTiming(message: String, func: () -> T): T {
  val startTime = System.currentTimeMillis()
  val result = func()
  val endTime = System.currentTimeMillis()

  debug("$message: ${endTime-startTime}ms")

  return result
}

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

/** Compares two comparable values, treating null values as less than non-null ones. */
fun <T : Comparable<T>> T?.compareNullsFirst(other: T?): Int {
  return when {
    this != null && other != null -> this.compareTo(other)
    this != null && other == null -> 1
    this == null && other != null -> -1
    else -> 0
  }
}
