package com.terraformation.backend.tracking.event

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.BiomassSpeciesId
import com.terraformation.backend.db.tracking.MangroveTide
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.ratelimit.RateLimitedEvent
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import com.terraformation.backend.tracking.model.SpeciesDensityChangedEventModel
import com.terraformation.backend.tracking.model.ZoneT0DensityChangedEventModel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.locationtech.jts.geom.Point

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

data class ObservationStateUpdatedEvent(
    val observationId: ObservationId,
    val newState: ObservationState,
)

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

data class T0ZoneDataAssignedEvent(val plantingZoneId: PlantingZoneId)

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

sealed interface ObservationMediaFilePersistentEvent : PersistentEvent {
  val fileId: FileId
  val monitoringPlotId: MonitoringPlotId
  val observationId: ObservationId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
}

data class ObservationMediaFileDeletedEventV1(
    override val fileId: FileId,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
) : EntityDeletedPersistentEvent, ObservationMediaFilePersistentEvent

typealias ObservationMediaFileDeletedEvent = ObservationMediaFileDeletedEventV1

data class ObservationMediaFileEditedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val fileId: FileId,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
) : FieldsUpdatedPersistentEvent, ObservationMediaFilePersistentEvent {
  data class Values(
      val caption: String?,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(createUpdatedField("caption", changedFrom.caption, changedTo.caption))
}

typealias ObservationMediaFileEditedEvent = ObservationMediaFileEditedEventV1

typealias ObservationMediaFileEditedEventValues = ObservationMediaFileEditedEventV1.Values

data class ObservationMediaFileUploadedEventV1(
    val caption: String?,
    val contentType: String,
    override val fileId: FileId,
    val geolocation: Point?,
    val isOriginal: Boolean,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    val position: ObservationPlotPosition?,
    val type: ObservationMediaType,
) : EntityCreatedPersistentEvent, ObservationMediaFilePersistentEvent

typealias ObservationMediaFileUploadedEvent = ObservationMediaFileUploadedEventV1

sealed interface ObservationPlotPersistentEvent : PersistentEvent {
  val monitoringPlotId: MonitoringPlotId
  val observationId: ObservationId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
}

data class ObservationPlotCreatedEventV1(
    val isPermanent: Boolean,
    val monitoringPlotHistoryId: MonitoringPlotHistoryId,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    val plotNumber: Long,
) : EntityCreatedPersistentEvent, ObservationPlotPersistentEvent

typealias ObservationPlotCreatedEvent = ObservationPlotCreatedEventV1

data class ObservationPlotEditedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
) : FieldsUpdatedPersistentEvent, ObservationPlotPersistentEvent {
  data class Values(
      val conditions: Set<ObservableCondition>?,
      val notes: String?,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "conditions",
              changedFrom.conditions?.let { messages.sortedEnumList(it) },
              changedTo.conditions?.let { messages.sortedEnumList(it) },
          ),
          createUpdatedField("notes", changedFrom.notes, changedTo.notes),
      )
}

typealias ObservationPlotEditedEvent = ObservationPlotEditedEventV1

typealias ObservationPlotEditedEventValues = ObservationPlotEditedEventV1.Values

sealed interface BiomassDetailsPersistentEvent : PersistentEvent {
  val monitoringPlotId: MonitoringPlotId
  val observationId: ObservationId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
}

data class BiomassDetailsCreatedEventV1(
    val description: String? = null,
    val forestType: BiomassForestType,
    val herbaceousCoverPercent: Int,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    val ph: BigDecimal? = null,
    override val plantingSiteId: PlantingSiteId,
    val salinityPpt: BigDecimal? = null,
    val smallTreesCountHigh: Int,
    val smallTreesCountLow: Int,
    val soilAssessment: String,
    val tide: MangroveTide? = null,
    val tideTime: Instant? = null,
    val waterDepthCm: Int? = null,
) : EntityCreatedPersistentEvent, BiomassDetailsPersistentEvent

typealias BiomassDetailsCreatedEvent = BiomassDetailsCreatedEventV1

data class BiomassDetailsUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
) : FieldsUpdatedPersistentEvent, BiomassDetailsPersistentEvent {
  data class Values(
      val description: String? = null,
      val forestType: BiomassForestType? = null,
      val herbaceousCoverPercent: Int? = null,
      val ph: BigDecimal? = null,
      val salinity: BigDecimal? = null,
      val smallTreeCountRange: Pair<Int, Int>? = null,
      val soilAssessment: String? = null,
      val tide: MangroveTide? = null,
      val tideTime: Instant? = null,
      val waterDepth: Int? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField("description", changedFrom.description, changedTo.description),
          createUpdatedField(
              "forestType",
              changedFrom.forestType?.getDisplayName(currentLocale()),
              changedTo.forestType?.getDisplayName(currentLocale()),
          ),
          createUpdatedField(
              "herbaceousCoverPercent",
              messages.numericValueOrNull(changedFrom.herbaceousCoverPercent),
              messages.numericValueOrNull(changedTo.herbaceousCoverPercent),
          ),
          createUpdatedField(
              "ph",
              messages.numericValueOrNull(changedFrom.ph),
              messages.numericValueOrNull(changedTo.ph),
          ),
          createUpdatedField(
              "salinity",
              messages.numericValueOrNull(changedFrom.salinity),
              messages.numericValueOrNull(changedTo.salinity),
          ),
          createUpdatedField(
              "smallTreeCount",
              renderSmallTreeCountRange(changedFrom, messages),
              renderSmallTreeCountRange(changedTo, messages),
          ),
          createUpdatedField(
              "soilAssessment",
              changedFrom.soilAssessment,
              changedTo.soilAssessment,
          ),
          createUpdatedField(
              "tide",
              changedFrom.tide?.getDisplayName(currentLocale()),
              changedTo.tide?.getDisplayName(currentLocale()),
          ),
          // The tide timestamp is shown in the browser's time zone in the web app; we don't have
          // access to that here but we can use the user's selected time zone as a substitute.
          createUpdatedField(
              "tideTime",
              messages.timestampOrNull(changedFrom.tideTime, currentUser().timeZone),
              messages.timestampOrNull(changedTo.tideTime, currentUser().timeZone),
          ),
          createUpdatedField(
              "waterDepth",
              messages.numericValueOrNull(changedFrom.waterDepth),
              messages.numericValueOrNull(changedTo.waterDepth),
          ),
      )

  private fun renderSmallTreeCountRange(values: Values, messages: Messages): String? {
    val (low, high) = values.smallTreeCountRange ?: return null
    val lowString = messages.numericValueOrNull(low)
    val highString = messages.numericValueOrNull(high)

    return if (high > 0) {
      "$lowString-$highString"
    } else {
      "+$lowString"
    }
  }
}

typealias BiomassDetailsUpdatedEvent = BiomassDetailsUpdatedEventV1

typealias BiomassDetailsUpdatedEventValues = BiomassDetailsUpdatedEventV1.Values

sealed interface BiomassQuadratPersistentEvent : PersistentEvent {
  val monitoringPlotId: MonitoringPlotId
  val observationId: ObservationId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val position: ObservationPlotPosition
}

data class BiomassQuadratCreatedEventV1(
    val description: String?,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val position: ObservationPlotPosition,
) : BiomassQuadratPersistentEvent, EntityCreatedPersistentEvent

typealias BiomassQuadratCreatedEvent = BiomassQuadratCreatedEventV1

data class BiomassQuadratDetailsUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val position: ObservationPlotPosition,
) : BiomassQuadratPersistentEvent, FieldsUpdatedPersistentEvent {
  data class Values(
      val description: String?,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField("description", changedFrom.description, changedTo.description),
      )
}

typealias BiomassQuadratDetailsUpdatedEvent = BiomassQuadratDetailsUpdatedEventV1

typealias BiomassQuadratDetailsUpdatedEventValues = BiomassQuadratDetailsUpdatedEventV1.Values

sealed interface BiomassQuadratSpeciesPersistentEvent : PersistentEvent {
  val biomassSpeciesId: BiomassSpeciesId
  val monitoringPlotId: MonitoringPlotId
  val observationId: ObservationId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val position: ObservationPlotPosition
}

data class BiomassQuadratSpeciesUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val biomassSpeciesId: BiomassSpeciesId,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val position: ObservationPlotPosition,
) : BiomassQuadratSpeciesPersistentEvent, FieldsUpdatedPersistentEvent {
  data class Values(
      val abundance: Int?,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "abundance",
              messages.numericValueOrNull(changedFrom.abundance),
              messages.numericValueOrNull(changedTo.abundance),
          ),
      )
}

typealias BiomassQuadratSpeciesUpdatedEvent = BiomassQuadratSpeciesUpdatedEventV1

typealias BiomassQuadratSpeciesUpdatedEventValues = BiomassQuadratSpeciesUpdatedEventV1.Values

sealed interface BiomassSpeciesPersistentEvent : PersistentEvent {
  val biomassSpeciesId: BiomassSpeciesId
  val monitoringPlotId: MonitoringPlotId
  val observationId: ObservationId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
}

data class BiomassSpeciesCreatedEventV1(
    override val biomassSpeciesId: BiomassSpeciesId,
    val commonName: String?,
    val isInvasive: Boolean,
    val isThreatened: Boolean,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    val scientificName: String?,
    val speciesId: SpeciesId?,
) : BiomassSpeciesPersistentEvent, EntityCreatedPersistentEvent

typealias BiomassSpeciesCreatedEvent = BiomassSpeciesCreatedEventV1

data class BiomassSpeciesUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val biomassSpeciesId: BiomassSpeciesId,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
) : BiomassSpeciesPersistentEvent, FieldsUpdatedPersistentEvent {
  data class Values(
      val isInvasive: Boolean?,
      val isThreatened: Boolean?,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField(
              "isInvasive",
              messages.booleanOrNull(changedFrom.isInvasive),
              messages.booleanOrNull(changedTo.isInvasive),
          ),
          createUpdatedField(
              "isThreatened",
              messages.booleanOrNull(changedFrom.isThreatened),
              messages.booleanOrNull(changedTo.isThreatened),
          ),
      )
}

typealias BiomassSpeciesUpdatedEvent = BiomassSpeciesUpdatedEventV1

typealias BiomassSpeciesUpdatedEventValues = BiomassSpeciesUpdatedEventV1.Values

sealed interface RecordedTreePersistentEvent : PersistentEvent {
  val monitoringPlotId: MonitoringPlotId
  val observationId: ObservationId
  val organizationId: OrganizationId
  val plantingSiteId: PlantingSiteId
  val recordedTreeId: RecordedTreeId
}

data class RecordedTreeCreatedEventV1(
    val biomassSpeciesId: BiomassSpeciesId,
    val description: String? = null,
    val diameterAtBreastHeightCm: BigDecimal? = null,
    val gpsCoordinates: Point? = null,
    val heightM: BigDecimal? = null,
    val isDead: Boolean,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    val pointOfMeasurementM: BigDecimal? = null,
    override val recordedTreeId: RecordedTreeId,
    val shrubDiameterCm: Int? = null,
    val speciesId: SpeciesId? = null,
    val speciesName: String? = null,
    val treeGrowthForm: TreeGrowthForm,
    val treeNumber: Int,
    val trunkNumber: Int,
) : EntityCreatedPersistentEvent, RecordedTreePersistentEvent

typealias RecordedTreeCreatedEvent = RecordedTreeCreatedEventV1

data class RecordedTreeUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val monitoringPlotId: MonitoringPlotId,
    override val observationId: ObservationId,
    override val organizationId: OrganizationId,
    override val plantingSiteId: PlantingSiteId,
    override val recordedTreeId: RecordedTreeId,
) : FieldsUpdatedPersistentEvent, RecordedTreePersistentEvent {
  data class Values(
      val description: String? = null,
      val diameterAtBreastHeightCm: BigDecimal? = null,
      val heightM: BigDecimal? = null,
      val isDead: Boolean? = null,
      val pointOfMeasurementM: BigDecimal? = null,
      val shrubDiameterCm: Int? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField("description", changedFrom.description, changedTo.description),
          createUpdatedField(
              "diameterAtBreastHeightCm",
              messages.numericValueOrNull(changedFrom.diameterAtBreastHeightCm),
              messages.numericValueOrNull(changedTo.diameterAtBreastHeightCm),
          ),
          createUpdatedField(
              "heightM",
              messages.numericValueOrNull(changedFrom.heightM),
              messages.numericValueOrNull(changedTo.heightM),
          ),
          createUpdatedField(
              "isDead",
              messages.booleanOrNull(changedFrom.isDead),
              messages.booleanOrNull(changedTo.isDead),
          ),
          createUpdatedField(
              "pointOfMeasurementM",
              messages.numericValueOrNull(changedFrom.pointOfMeasurementM),
              messages.numericValueOrNull(changedTo.pointOfMeasurementM),
          ),
          createUpdatedField(
              "shrubDiameterCm",
              messages.numericValueOrNull(changedFrom.shrubDiameterCm),
              messages.numericValueOrNull(changedTo.shrubDiameterCm),
          ),
      )
}

typealias RecordedTreeUpdatedEvent = RecordedTreeUpdatedEventV1

typealias RecordedTreeUpdatedEventValues = RecordedTreeUpdatedEventV1.Values
