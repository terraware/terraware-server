package com.terraformation.backend.time

import java.time.Duration

/**
 * Published when the application's clock is advanced by an administrator. This only happens in test
 * environments; it is used to test time-based workflows by advancing the clock to the time when an
 * operation is scheduled to be performed.
 *
 * @see DatabaseBackedClock
 */
data class ClockAdvancedEvent(val duration: Duration)
