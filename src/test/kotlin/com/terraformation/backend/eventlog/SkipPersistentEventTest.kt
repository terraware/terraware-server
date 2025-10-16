package com.terraformation.backend.eventlog

/**
 * Tells [PersistentEventTest] to ignore a class when it scans to find all the event classes. This
 * annotation is intended to be used on event classes that are intentionally malformed for test
 * purposes and would otherwise cause that test to fail.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class SkipPersistentEventTest
