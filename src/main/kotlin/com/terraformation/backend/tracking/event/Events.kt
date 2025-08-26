package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import java.time.LocalDate

/** Published when an organization requests that a monitoring plot be replaced in an observation. */
data class ObservationPlotReplacedEvent(
    val duration: ReplacementDuration,
    val justification: String,
    val observation: ExistingObservationModel,
    val monitoringPlotId: MonitoringPlotId,
)

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

interface ObservationSchedulingNotificationEvent

/** Published when a site is ready to have observations scheduled */
data class ScheduleObservationNotificationEvent(
    val plantingSiteId: PlantingSiteId,
) : ObservationSchedulingNotificationEvent

/** Published when a site is reminded to schedule observations */
data class ScheduleObservationReminderNotificationEvent(
    val plantingSiteId: PlantingSiteId,
) : ObservationSchedulingNotificationEvent

/** Published when a site has not had observations scheduled */
data class ObservationNotScheduledNotificationEvent(
    val plantingSiteId: PlantingSiteId,
) : ObservationSchedulingNotificationEvent

/** Published when we're unable to start a scheduled observation. */
data class ObservationNotStartedEvent(
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
) : ObservationSchedulingNotificationEvent

data class PlantingSiteDeletionStartedEvent(val plantingSiteId: PlantingSiteId)

data class PlantingSeasonRescheduledEvent(
    val plantingSiteId: PlantingSiteId,
    val plantingSeasonId: PlantingSeasonId,
    val oldStartDate: LocalDate,
    val oldEndDate: LocalDate,
    val newStartDate: LocalDate,
    val newEndDate: LocalDate,
)

data class PlantingSeasonScheduledEvent(
    val plantingSiteId: PlantingSiteId,
    val plantingSeasonId: PlantingSeasonId,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class PlantingSeasonStartedEvent(
    val plantingSiteId: PlantingSiteId,
    val plantingSeasonId: PlantingSeasonId,
)

data class PlantingSeasonEndedEvent(
    val plantingSiteId: PlantingSiteId,
    val plantingSeasonId: PlantingSeasonId,
)

interface PlantingSeasonSchedulingNotificationEvent {
  val plantingSiteId: PlantingSiteId
  val notificationNumber: Int
}

data class PlantingSeasonNotScheduledNotificationEvent(
    override val plantingSiteId: PlantingSiteId,
    override val notificationNumber: Int,
) : PlantingSeasonSchedulingNotificationEvent

data class PlantingSeasonNotScheduledSupportNotificationEvent(
    override val plantingSiteId: PlantingSiteId,
    override val notificationNumber: Int,
) : PlantingSeasonSchedulingNotificationEvent

data class PlantingSiteMapEditedEvent(
    val edited: ExistingPlantingSiteModel,
    val plantingSiteEdit: PlantingSiteEdit,
    val monitoringPlotReplacements: ReplacementResult,
)

data class T0SpeciesDensityAssignedEvent(
    val plotDensity: Int,
    val monitoringPlotId: MonitoringPlotId,
    val speciesId: SpeciesId,
)

data class T0ObservationAssignedEvent(
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
)
