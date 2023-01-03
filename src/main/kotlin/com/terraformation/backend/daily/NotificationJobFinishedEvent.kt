package com.terraformation.backend.daily

/**
 * Published when a job that can generate notifications finishes on the current thread, whether or
 * not it succeeded. If it succeeded, [NotificationJobSucceededEvent] is published before this.
 * There may be other jobs still running on other threads.
 */
class NotificationJobFinishedEvent
