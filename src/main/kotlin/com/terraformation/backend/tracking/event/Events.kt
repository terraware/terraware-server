package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.tracking.PlantingSiteId
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

/** Published when an observation is scheduled by an end user in Terraware. */
data class ObservationScheduledEvent(
    val observation: ExistingObservationModel,
)

/** Published when an observation is rescheduled by an end user in Terraware. */
data class ObservationRescheduledEvent(
    val originalObservation: ExistingObservationModel,
    val rescheduledObservation: ExistingObservationModel,
)

/** Published when a site is ready to have observations scheduled */
data class ScheduleObservationNotificationEvent(
    val plantingSiteId: PlantingSiteId,
)

/** Published when a site is reminded to schedule observations */
data class ScheduleObservationReminderNotificationEvent(
    val plantingSiteId: PlantingSiteId,
)
