package com.terraformation.seedbank.services

import java.math.BigDecimal
import java.util.EnumSet
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
  return LoggerFactory.getLogger(loggingClass)
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

/** Transforms a Collection to a Set if it has non-null values, or to null if not. */
fun <T : Collection<V?>, V> T.toSetOrNull(): Set<V>? =
    if (isEmpty()) null else filterNotNull().toSet()

/** Transforms a Collection to a List if it has non-null values, or to null if not. */
fun <T : Collection<V?>, V> T.toListOrNull(): List<V>? = if (isEmpty()) null else filterNotNull()

/** Tests two nullable BigDecimal values for equality ignoring their scale. */
fun BigDecimal?.equalsIgnoreScale(other: BigDecimal?) =
    this == null && other == null || this != null && other != null && compareTo(other) == 0
