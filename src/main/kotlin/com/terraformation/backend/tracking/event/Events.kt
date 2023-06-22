package com.terraformation.backend.tracking.event

import com.terraformation.backend.tracking.model.ExistingObservationModel

/** Published when an observation has just started. */
data class ObservationStartedEvent(
    val observation: ExistingObservationModel,
)

/**
 * Published when an observation is scheduled to start in 1 month o less and no notification has
 * been sent about it yet.
 */
data class ObservationUpcomingNotificationDueEvent(
    val observation: ExistingObservationModel,
)
