package com.terraformation.backend.daily

/**
 * Published when a job that can generate notifications starts on the current thread. There may be
 * other such jobs running on other threads.
 */
class NotificationJobStartedEvent
