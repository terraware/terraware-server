package com.terraformation.seedbank.services

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Returns a logger object that is associated with the receiver's class. Typical usage is to create
 * a private logger field on a class, like
 *
 *     class Foo {
 *       private val log = perClassLogger()
 *       fun doWork() {
 *         log.info("Doing work now")
 *       }
 *     }
 */
inline fun <reified T : Any> T.perClassLogger(): Logger {
  val loggingClass = if (javaClass.kotlin.isCompanion) {
    javaClass.enclosingClass
  } else {
    javaClass
  }
  return LoggerFactory.getLogger(loggingClass)
}
