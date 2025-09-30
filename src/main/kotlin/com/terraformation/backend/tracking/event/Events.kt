package com.terraformation.backend.tracking.event

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.ratelimit.RateLimitedEvent
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.tracking.model.SpeciesDensityChangedEventModel
import com.terraformation.backend.tracking.model.ZoneT0DensityChangedEventModel
import java.time.Duration
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

data class T0PlotDataAssignedEvent(
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId? = null,
)

data class RateLimitedT0DataAssignedEvent(
    val organizationId: OrganizationId,
    val plantingSiteId: PlantingSiteId,
    val previousSiteTempSetting: Boolean? = null,
    val newSiteTempSetting: Boolean? = null,
    val monitoringPlots: List<PlotT0DensityChangedEventModel>? = null,
    val plantingZones: List<ZoneT0DensityChangedEventModel>? = null,
) : RateLimitedEvent<RateLimitedT0DataAssignedEvent> {
  companion object {
    private fun combineSpeciesChanges(
        existingSpecies: List<SpeciesDensityChangedEventModel>,
        newSpecies: List<SpeciesDensityChangedEventModel>,
    ): List<SpeciesDensityChangedEventModel> {
      val combinedChanges = mutableMapOf<SpeciesId, SpeciesDensityChangedEventModel>()

      existingSpecies.forEach { change -> combinedChanges[change.speciesId] = change }

      // Use previousDensity from existing and newDensity from current
      newSpecies.forEach { newChange ->
        val existingChange = combinedChanges[newChange.speciesId]
        combinedChanges[newChange.speciesId] =
            if (existingChange == null) {
              newChange
            } else {
              newChange.copy(previousDensity = existingChange.previousDensity)
            }
      }

      return combinedChanges.values.filter { it.previousDensity != it.newDensity }
    }
  }

  override fun getRateLimitKey() =
      mapOf("organizationId" to organizationId, "plantingSiteId" to plantingSiteId)

  override fun getMinimumInterval(): Duration = Duration.ofHours(24)

  override fun combine(existing: RateLimitedT0DataAssignedEvent): RateLimitedT0DataAssignedEvent {
    require(existing.organizationId == organizationId) {
      "Cannot combine events for different organizationIds"
    }
    require(existing.plantingSiteId == plantingSiteId) {
      "Cannot combine events for different plantingSiteIds"
    }

    val plotsMap = mutableMapOf<MonitoringPlotId, PlotT0DensityChangedEventModel>()
    existing.monitoringPlots?.forEach { plot -> plotsMap[plot.monitoringPlotId] = plot }
    // Merge current plots, combining speciesDensityChanges if plot already exists
    monitoringPlots?.forEach { newPlot ->
      val existingPlot = plotsMap[newPlot.monitoringPlotId]
      if (existingPlot == null) {
        plotsMap[newPlot.monitoringPlotId] = newPlot
      } else {
        plotsMap[newPlot.monitoringPlotId] =
            newPlot.copy(
                speciesDensityChanges =
                    combineSpeciesChanges(
                        existingPlot.speciesDensityChanges,
                        newPlot.speciesDensityChanges,
                    )
            )
      }
    }

    val zonesMap = mutableMapOf<PlantingZoneId, ZoneT0DensityChangedEventModel>()
    existing.plantingZones?.forEach { zone -> zonesMap[zone.plantingZoneId] = zone }
    // Merge current zones, combining speciesDensityChanges if zone already exists
    plantingZones?.forEach { newZone ->
      val existingZone = zonesMap[newZone.plantingZoneId]
      if (existingZone == null) {
        zonesMap[newZone.plantingZoneId] = newZone
      } else {
        zonesMap[newZone.plantingZoneId] =
            newZone.copy(
                speciesDensityChanges =
                    combineSpeciesChanges(
                        existingZone.speciesDensityChanges,
                        newZone.speciesDensityChanges,
                    )
            )
      }
    }

    return RateLimitedT0DataAssignedEvent(
        organizationId = organizationId,
        plantingSiteId = plantingSiteId,
        previousSiteTempSetting = existing.previousSiteTempSetting ?: previousSiteTempSetting,
        newSiteTempSetting = newSiteTempSetting ?: existing.newSiteTempSetting,
        monitoringPlots =
            if (monitoringPlots != null) plotsMap.values.toList() else existing.monitoringPlots,
        plantingZones =
            if (plantingZones != null) zonesMap.values.toList() else existing.plantingZones,
    )
  }
}
