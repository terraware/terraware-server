package com.terraformation.backend.daily

/**
 * Published when a job that can generate notifications finishes successfully on the current thread.
 * [NotificationJobFinishedEvent] is published after this. There may be other jobs still running on
 * other threads.
 */
class NotificationJobSucceededEvent
