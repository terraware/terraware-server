package com.terraformation.backend.log

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level

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
 *
 * For classes that get instantiated frequently, you can also put the logger in the companion
 * object, like
 *
 * ```
 * class Foo {
 *   fun doWork() {
 *     log.info("Doing work now")
 *   }
 *
 *   companion object {
 *     private val log = perClassLogger()
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
  val className = loggingClass.canonicalName.substringBefore("$$")

  return LoggerFactory.getLogger(className)
}

/**
 * Logs a message with a caller-specified log level. The logging API doesn't allow the log level to
 * be specified dynamically, but when we're forwarding log messages from clients, the level depends
 * on the input.
 */
fun Logger.log(level: Level, text: String) {
  when (level) {
    Level.TRACE -> this.trace(text)
    Level.DEBUG -> this.debug(text)
    Level.INFO -> this.info(text)
    Level.WARN -> this.warn(text)
    Level.ERROR -> this.error(text)
  }
}

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
 * Adds key/value pairs to the mapped diagnostic context and calls a function. The values will be
 * removed when the function returns.
 */
fun <T> Logger.withMDC(vararg items: Pair<String, Any?>, func: () -> T): T {
  val oldMdc = MDC.getCopyOfContextMap() ?: emptyMap()
  try {
    items.forEach { MDC.put(it.first, "${it.second}") }
    return func()
  } finally {
    MDC.setContextMap(oldMdc)
  }
}
