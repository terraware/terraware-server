package com.terraformation.seedbank.services

import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
