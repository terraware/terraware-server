package com.terraformation.backend.eventlog.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.OrganizationCreatedEvent
import com.terraformation.backend.customer.event.OrganizationPersistentEvent
import com.terraformation.backend.customer.event.OrganizationRenamedEvent
import com.terraformation.backend.customer.event.ProjectCreatedEvent
import com.terraformation.backend.customer.event.ProjectPersistentEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.eventlog.EventLogPayloadContext
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.file.api.MediaKind
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestSpeciesPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDatePersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateSpeciesPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonWithdrawalCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionCreatedEvent
import com.terraformation.backend.seedbank.event.AccessionDeletedEvent
import com.terraformation.backend.seedbank.event.AccessionPersistentEvent
import com.terraformation.backend.seedbank.event.AccessionPhotoAddedEvent
import com.terraformation.backend.seedbank.event.AccessionPhotoPersistentEvent
import com.terraformation.backend.seedbank.event.AccessionUploadedEvent
import com.terraformation.backend.seedbank.event.ViabilityTestDeletedEvent
import com.terraformation.backend.seedbank.event.ViabilityTestPersistentEvent
import com.terraformation.backend.seedbank.event.WithdrawalDeletedEvent
import com.terraformation.backend.seedbank.event.WithdrawalPersistentEvent
import com.terraformation.backend.tracking.event.BiomassDetailsPersistentEvent
import com.terraformation.backend.tracking.event.BiomassQuadratPersistentEvent
import com.terraformation.backend.tracking.event.BiomassQuadratSpeciesPersistentEvent
import com.terraformation.backend.tracking.event.BiomassSpeciesPersistentEvent
import com.terraformation.backend.tracking.event.MonitoringSpeciesPersistentEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFilePersistentEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEvent
import com.terraformation.backend.tracking.event.ObservationPlotCreatedEvent
import com.terraformation.backend.tracking.event.ObservationPlotPersistentEvent
import com.terraformation.backend.tracking.event.RecordedTreeCreatedEvent
import com.terraformation.backend.tracking.event.RecordedTreePersistentEvent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import kotlin.reflect.KClass

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface EventSubjectPayload {
  @get:Schema(
      description =
          "If this is true, the entity referred to by this subject has been deleted. This " +
              "property will be omitted if the entity still exists, i.e., this property will " +
              "always be true if it exists."
  )
  val deleted: Boolean?
    get() = null

  @get:Schema(
      description =
          "A localized extended human-readable description of the subject of the event, suitable " +
              "for display in cases where events for many subjects are being shown in the same " +
              "list.",
      example = "Project Backyard Garden",
  )
  val fullText: String

  @get:Schema(
      description =
          "A localized short human-readable name (often a single word) for the subject of the " +
              "event, suitable for display in cases where only events for a single subject are " +
              "being shown or where the subject doesn't need to be distinguished from others " +
              "of the same type.",
      example = "Project",
  )
  val shortText: String
}

@JsonTypeName("Accession")
data class AccessionSubjectPayload(
    val accessionId: AccessionId,
    override val deleted: Boolean?,
    val facilityId: FacilityId,
    override val fullText: String,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: AccessionPersistentEvent,
        context: EventLogPayloadContext,
    ): AccessionSubjectPayload {
      val accessionNumber =
          context
              .firstOrNull<AccessionCreatedEvent> { it.accessionId == event.accessionId }
              ?.accessionNumber
              ?: context
                  .firstOrNull<AccessionUploadedEvent> { it.accessionId == event.accessionId }
                  ?.accessionNumber
              ?: event.accessionId.toString()
      val deleteEvent =
          context.firstOrNull<AccessionDeletedEvent> { it.accessionId == event.accessionId }

      return AccessionSubjectPayload(
          accessionId = event.accessionId,
          deleted = if (deleteEvent != null) true else null,
          facilityId = event.facilityId,
          fullText = context.subjectFullText<AccessionSubjectPayload>(accessionNumber),
          shortText = context.subjectShortText<AccessionSubjectPayload>(),
      )
    }
  }
}

@JsonTypeName("AccessionPhoto")
data class AccessionPhotoSubjectPayload(
    val accessionId: AccessionId,
    override val deleted: Boolean?,
    val facilityId: FacilityId,
    val fileId: FileId,
    override val fullText: String,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: AccessionPhotoPersistentEvent,
        context: EventLogPayloadContext,
    ): AccessionPhotoSubjectPayload {
      val filename =
          context.firstOrNull<AccessionPhotoAddedEvent> { it.fileId == event.fileId }?.filename
              ?: event.fileId.toString()

      return AccessionPhotoSubjectPayload(
          accessionId = event.accessionId,
          deleted = null,
          facilityId = event.facilityId,
          fileId = event.fileId,
          fullText = context.subjectFullText<AccessionPhotoSubjectPayload>(filename),
          shortText = context.subjectShortText<AccessionPhotoSubjectPayload>(),
      )
    }
  }
}

@JsonTypeName("BiomassDetails")
data class BiomassDetailsSubjectPayload(
    override val fullText: String,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: BiomassDetailsPersistentEvent,
        context: EventLogPayloadContext,
    ): BiomassDetailsSubjectPayload {
      return BiomassDetailsSubjectPayload(
          fullText = context.subjectFullText<BiomassDetailsSubjectPayload>(event.observationId),
          monitoringPlotId = event.monitoringPlotId,
          observationId = event.observationId,
          plantingSiteId = event.plantingSiteId,
          shortText = context.subjectShortText<BiomassDetailsSubjectPayload>(),
      )
    }
  }
}

@JsonTypeName("BiomassQuadrat")
data class BiomassQuadratSubjectPayload(
    override val fullText: String,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    val position: ObservationPlotPosition,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: BiomassQuadratPersistentEvent,
        context: EventLogPayloadContext,
    ): BiomassQuadratSubjectPayload {
      val localizedPosition = event.position.getDisplayName(currentUser().locale)

      return BiomassQuadratSubjectPayload(
          fullText = context.subjectFullText<BiomassQuadratSubjectPayload>(localizedPosition),
          monitoringPlotId = event.monitoringPlotId,
          observationId = event.observationId,
          plantingSiteId = event.plantingSiteId,
          position = event.position,
          shortText = context.subjectShortText<BiomassQuadratSubjectPayload>(),
      )
    }
  }
}

@JsonTypeName("BiomassQuadratSpecies")
data class BiomassQuadratSpeciesSubjectPayload(
    override val fullText: String,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    val position: ObservationPlotPosition,
    override val shortText: String,
    val scientificName: String?,
    val speciesId: SpeciesId?,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: BiomassQuadratSpeciesPersistentEvent,
        context: EventLogPayloadContext,
    ): BiomassQuadratSpeciesSubjectPayload {
      val biomassSpecies = context.getBiomassSpecies(event.biomassSpeciesId)
      val localizedPosition = event.position.getDisplayName(currentUser().locale)

      return BiomassQuadratSpeciesSubjectPayload(
          fullText =
              context.subjectFullText<BiomassQuadratSpeciesSubjectPayload>(
                  localizedPosition,
                  biomassSpecies.displayName,
              ),
          monitoringPlotId = event.monitoringPlotId,
          observationId = event.observationId,
          plantingSiteId = event.plantingSiteId,
          position = event.position,
          shortText = context.subjectShortText<BiomassQuadratSpeciesSubjectPayload>(),
          scientificName = biomassSpecies.scientificName,
          speciesId = biomassSpecies.speciesId,
      )
    }
  }
}

@JsonTypeName("BiomassSpecies")
data class BiomassSpeciesSubjectPayload(
    override val fullText: String,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    override val shortText: String,
    val speciesId: SpeciesId?,
    val scientificName: String?,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: BiomassSpeciesPersistentEvent,
        context: EventLogPayloadContext,
    ): BiomassSpeciesSubjectPayload {
      val biomassSpecies = context.getBiomassSpecies(event.biomassSpeciesId)

      return BiomassSpeciesSubjectPayload(
          fullText =
              context.subjectFullText<BiomassSpeciesSubjectPayload>(biomassSpecies.displayName),
          monitoringPlotId = event.monitoringPlotId,
          observationId = event.observationId,
          plantingSiteId = event.plantingSiteId,
          shortText = context.subjectShortText<BiomassSpeciesSubjectPayload>(),
          speciesId = biomassSpecies.speciesId,
          scientificName = biomassSpecies.scientificName,
      )
    }
  }
}

@JsonTypeName("MonitoringSpecies")
data class MonitoringSpeciesSubjectPayload(
    override val fullText: String,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    override val shortText: String,
    val speciesId: SpeciesId?,
    val scientificName: String?,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: MonitoringSpeciesPersistentEvent,
        context: EventLogPayloadContext,
    ): MonitoringSpeciesSubjectPayload {
      val displayName =
          context.getRecordedSpeciesName(
              event.certainty,
              event.speciesId,
              event.speciesName,
          )

      return MonitoringSpeciesSubjectPayload(
          fullText = context.subjectFullText<MonitoringSpeciesSubjectPayload>(displayName),
          monitoringPlotId = event.monitoringPlotId,
          observationId = event.observationId,
          plantingSiteId = event.plantingSiteId,
          shortText = context.subjectShortText<MonitoringSpeciesSubjectPayload>(),
          speciesId = event.speciesId,
          scientificName = event.speciesName,
      )
    }
  }
}

@JsonTypeName("ObservationPlot")
data class ObservationPlotSubjectPayload(
    override val fullText: String,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: ObservationPlotPersistentEvent,
        context: EventLogPayloadContext,
    ): ObservationPlotSubjectPayload {
      val createEvent =
          context.first<ObservationPlotCreatedEvent> {
            it.observationId == event.observationId && it.monitoringPlotId == event.monitoringPlotId
          }

      return ObservationPlotSubjectPayload(
          context.subjectFullText<ObservationPlotSubjectPayload>(createEvent.plotNumber),
          event.monitoringPlotId,
          event.observationId,
          event.plantingSiteId,
          context.subjectShortText<ObservationPlotSubjectPayload>(),
      )
    }
  }
}

@JsonTypeName("ObservationPlotMedia")
data class ObservationPlotMediaSubjectPayload(
    override val deleted: Boolean?,
    val fileId: FileId,
    override val fullText: String,
    @Schema(
        description =
            "True if this file was uploaded as part of the original submission of observation " +
                "data; false if it was uploaded later."
    )
    val isOriginal: Boolean,
    val mediaKind: MediaKind,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: ObservationMediaFilePersistentEvent,
        context: EventLogPayloadContext,
    ): ObservationPlotMediaSubjectPayload {
      val createEvent =
          context.first<ObservationMediaFileUploadedEvent> { it.fileId == event.fileId }
      val deleteEvent =
          context.firstOrNull<ObservationMediaFileDeletedEvent> { it.fileId == event.fileId }

      val mediaKind = MediaKind.forMimeType(createEvent.contentType)
      val mediaKindName = mediaKind.getDisplayName(currentUser().locale)
      val fullText =
          context.subjectFullText<ObservationPlotMediaSubjectPayload>(mediaKindName, event.fileId)

      return ObservationPlotMediaSubjectPayload(
          deleted = if (deleteEvent != null) true else null,
          fileId = event.fileId,
          fullText = fullText,
          isOriginal = createEvent.isOriginal,
          mediaKind = mediaKind,
          monitoringPlotId = event.monitoringPlotId,
          observationId = event.observationId,
          plantingSiteId = event.plantingSiteId,
          shortText = mediaKindName,
      )
    }
  }
}

@JsonTypeName("Organization")
data class OrganizationSubjectPayload(
    override val fullText: String,
    val organizationId: OrganizationId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: OrganizationPersistentEvent,
        context: EventLogPayloadContext,
    ): OrganizationSubjectPayload {
      val name = getPreviousName(event, context)

      return OrganizationSubjectPayload(
          fullText = context.subjectFullText<OrganizationSubjectPayload>(name),
          organizationId = event.organizationId,
          shortText = context.subjectShortText<OrganizationSubjectPayload>(),
      )
    }

    fun getPreviousName(
        event: OrganizationPersistentEvent,
        context: EventLogPayloadContext,
    ): String {
      val lastRename =
          context.lastEventBefore<OrganizationRenamedEvent>(event) {
            it.organizationId == event.organizationId
          }
      return lastRename?.changedTo?.name
          ?: context
              .first<OrganizationCreatedEvent> { it.organizationId == event.organizationId }
              .name
    }
  }
}

@JsonTypeName("PlantingDateRequest")
data class PlantingDateRequestSubjectPayload(
    override val fullText: String,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
    val scheduledPlantingDateId: ScheduledPlantingDateId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: PlantingDateRequestPersistentEvent,
        context: EventLogPayloadContext,
    ): PlantingDateRequestSubjectPayload {
      val date =
          when (event) {
            is PlantingDateRequestCreatedEvent -> event.date

            is PlantingDateRequestUpdatedEvent if event.changedFrom.date != null -> {
              event.changedFrom.date
            }

            else -> {
              val activeDate =
                  context
                      .lastEventBefore<PlantingDateRequestUpdatedEvent>(event) {
                        it.scheduledPlantingDateId == event.scheduledPlantingDateId &&
                            it.changedTo.date != null
                      }
                      ?.changedTo
                      ?.date
              activeDate
                  ?: context
                      .lastEventBefore<PlantingDateRequestCreatedEvent>(event) {
                        it.scheduledPlantingDateId == event.scheduledPlantingDateId
                      }
                      ?.date
            }
          }
      val dateText = date?.toString() ?: event.scheduledPlantingDateId.toString()

      return PlantingDateRequestSubjectPayload(
          fullText = context.subjectFullText<PlantingDateRequestSubjectPayload>(dateText),
          plantingSeasonId = event.plantingSeasonId,
          plantingSiteId = event.plantingSiteId,
          scheduledPlantingDateId = event.scheduledPlantingDateId,
          shortText = context.subjectShortText<PlantingDateRequestSubjectPayload>(),
      )
    }
  }
}

@JsonTypeName("PlantingDateRequestSpecies")
data class PlantingDateRequestSpeciesSubjectPayload(
    override val fullText: String,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
    val scheduledPlantingDateId: ScheduledPlantingDateId,
    val scientificName: String?,
    override val shortText: String,
    val speciesId: SpeciesId,
    val stratumName: String,
    val substratumHistoryId: SubstratumHistoryId,
    val substratumId: SubstratumId,
    val substratumName: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: PlantingDateRequestSpeciesPersistentEvent,
        context: EventLogPayloadContext,
    ): PlantingDateRequestSpeciesSubjectPayload {
      val scientificName = context.getSpeciesScientificName(event.speciesId)
      val activeDate =
          context
              .lastEventBefore<PlantingDateRequestUpdatedEvent>(event) {
                it.scheduledPlantingDateId == event.scheduledPlantingDateId &&
                    it.changedTo.date != null
              }
              ?.changedTo
              ?.date
              ?: context
                  .lastEventBefore<PlantingDateRequestCreatedEvent>(event) {
                    it.scheduledPlantingDateId == event.scheduledPlantingDateId
                  }
                  ?.date
      val dateText = activeDate?.toString() ?: event.scheduledPlantingDateId.toString()

      return PlantingDateRequestSpeciesSubjectPayload(
          fullText =
              context.subjectFullText<PlantingDateRequestSpeciesSubjectPayload>(
                  scientificName,
                  event.stratumName,
                  event.substratumName,
                  dateText,
              ),
          plantingSeasonId = event.plantingSeasonId,
          plantingSiteId = event.plantingSiteId,
          scheduledPlantingDateId = event.scheduledPlantingDateId,
          scientificName = scientificName,
          shortText = context.subjectShortText<PlantingDateRequestSpeciesSubjectPayload>(),
          speciesId = event.speciesId,
          stratumName = event.stratumName,
          substratumHistoryId = event.substratumHistoryId,
          substratumId = event.substratumId,
          substratumName = event.substratumName,
      )
    }
  }
}

@JsonTypeName("PlantingSeason")
data class PlantingSeasonSubjectPayload(
    override val fullText: String,
    val plantingSeasonId: PlantingSeasonId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: PlantingSeasonPersistentEvent,
        context: EventLogPayloadContext,
    ): PlantingSeasonSubjectPayload {
      val name = getPreviousName(event, context)

      return PlantingSeasonSubjectPayload(
          fullText = context.subjectFullText<PlantingSeasonSubjectPayload>(name),
          plantingSeasonId = event.plantingSeasonId,
          shortText = context.subjectShortText<PlantingSeasonSubjectPayload>(),
      )
    }

    fun getPreviousName(
        event: PlantingSeasonPersistentEvent,
        context: EventLogPayloadContext,
    ): String {
      val lastRename =
          context.lastEventBefore<PlantingSeasonUpdatedEvent>(event) {
            it.plantingSeasonId == event.plantingSeasonId && it.changedTo.name != null
          }
      return lastRename?.changedTo?.name
          ?: context
              .first<PlantingSeasonCreatedEvent> { it.plantingSeasonId == event.plantingSeasonId }
              .name
    }
  }
}

@JsonTypeName("PlantingSeasonAllocatedSpecies")
data class PlantingSeasonAllocatedSpeciesSubjectPayload(
    override val fullText: String,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
    val scientificName: String?,
    override val shortText: String,
    val speciesId: SpeciesId,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: PlantingSeasonAllocatedSpeciesPersistentEvent,
        context: EventLogPayloadContext,
    ): PlantingSeasonAllocatedSpeciesSubjectPayload {
      val scientificName = context.getSpeciesScientificName(event.speciesId)

      return PlantingSeasonAllocatedSpeciesSubjectPayload(
          fullText =
              context.subjectFullText<PlantingSeasonAllocatedSpeciesSubjectPayload>(scientificName),
          plantingSeasonId = event.plantingSeasonId,
          plantingSiteId = event.plantingSiteId,
          scientificName = scientificName,
          shortText = context.subjectShortText<PlantingSeasonAllocatedSpeciesSubjectPayload>(),
          speciesId = event.speciesId,
      )
    }
  }
}

@JsonTypeName("PlantingSeasonScheduledDate")
data class PlantingSeasonScheduledDateSubjectPayload(
    override val fullText: String,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
    val scheduledPlantingDateId: ScheduledPlantingDateId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: PlantingSeasonScheduledDatePersistentEvent,
        context: EventLogPayloadContext,
    ): PlantingSeasonScheduledDateSubjectPayload {
      // Since the IDs are meaningless to users, we want the full subject to include the scheduled
      // date. For events that change the date of a scheduled planting date, we want the subject to
      // list the "changed from" date (there is no "date" field on update events) so it shows up
      // like "Scheduled date 2026-01-01 date changed to 2026-01-05." But for other events, we want
      // whichever date was the active one before that event.
      val date =
          when (event) {
            is PlantingSeasonScheduledDateCreatedEvent -> event.date

            is PlantingSeasonScheduledDateUpdatedEvent if event.changedFrom.date != null -> {
              event.changedFrom.date
            }

            else -> {
              val activeDate =
                  context
                      .lastEventBefore<PlantingSeasonScheduledDateUpdatedEvent>(event) {
                        it.scheduledPlantingDateId == event.scheduledPlantingDateId &&
                            it.changedTo.date != null
                      }
                      ?.changedTo
                      ?.date

              activeDate
                  ?: context
                      .lastEventBefore<PlantingSeasonScheduledDateCreatedEvent>(event) {
                        it.scheduledPlantingDateId == event.scheduledPlantingDateId
                      }
                      ?.date
            }
          }
      val name = date?.toString() ?: event.scheduledPlantingDateId.toString()

      return PlantingSeasonScheduledDateSubjectPayload(
          fullText = context.subjectFullText<PlantingSeasonScheduledDateSubjectPayload>(name),
          plantingSeasonId = event.plantingSeasonId,
          plantingSiteId = event.plantingSiteId,
          scheduledPlantingDateId = event.scheduledPlantingDateId,
          shortText = context.subjectShortText<PlantingSeasonScheduledDateSubjectPayload>(),
      )
    }
  }
}

@JsonTypeName("PlantingSeasonScheduledDateSpecies")
data class PlantingSeasonScheduledDateSpeciesSubjectPayload(
    val activeDate: LocalDate,
    override val fullText: String,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
    val scheduledPlantingDateId: ScheduledPlantingDateId,
    val scientificName: String?,
    override val shortText: String,
    val speciesId: SpeciesId,
    val stratumName: String,
    val substratumHistoryId: SubstratumHistoryId,
    val substratumId: SubstratumId,
    val substratumName: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: PlantingSeasonScheduledDateSpeciesPersistentEvent,
        context: EventLogPayloadContext,
    ): PlantingSeasonScheduledDateSpeciesSubjectPayload {
      val scientificName = context.getSpeciesScientificName(event.speciesId)
      val activeDate =
          context
              .lastEventBefore<PlantingSeasonScheduledDateUpdatedEvent>(event) {
                it.scheduledPlantingDateId == event.scheduledPlantingDateId &&
                    it.changedTo.date != null
              }
              ?.changedTo
              ?.date
              ?: context
                  .first<PlantingSeasonScheduledDateCreatedEvent> {
                    it.scheduledPlantingDateId == event.scheduledPlantingDateId
                  }
                  .date
      val dateText = activeDate.toString()

      return PlantingSeasonScheduledDateSpeciesSubjectPayload(
          activeDate = activeDate,
          fullText =
              context.subjectFullText<PlantingSeasonScheduledDateSpeciesSubjectPayload>(
                  scientificName,
                  event.stratumName,
                  event.substratumName,
                  dateText,
              ),
          plantingSeasonId = event.plantingSeasonId,
          plantingSiteId = event.plantingSiteId,
          scheduledPlantingDateId = event.scheduledPlantingDateId,
          scientificName = scientificName,
          shortText = context.subjectShortText<PlantingSeasonScheduledDateSpeciesSubjectPayload>(),
          speciesId = event.speciesId,
          stratumName = event.stratumName,
          substratumHistoryId = event.substratumHistoryId,
          substratumId = event.substratumId,
          substratumName = event.substratumName,
      )
    }
  }
}

@JsonTypeName("PlantingSeasonSpeciesTarget")
data class PlantingSeasonSpeciesTargetSubjectPayload(
    override val fullText: String,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
    val scientificName: String?,
    override val shortText: String,
    val speciesId: SpeciesId,
    val stratumName: String,
    val substratumHistoryId: SubstratumHistoryId,
    val substratumId: SubstratumId,
    val substratumName: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: PlantingSeasonSpeciesTargetPersistentEvent,
        context: EventLogPayloadContext,
    ): PlantingSeasonSpeciesTargetSubjectPayload {
      val scientificName = context.getSpeciesScientificName(event.speciesId)

      return PlantingSeasonSpeciesTargetSubjectPayload(
          fullText =
              context.subjectFullText<PlantingSeasonSpeciesTargetSubjectPayload>(
                  scientificName,
                  event.stratumName,
                  event.substratumName,
              ),
          plantingSeasonId = event.plantingSeasonId,
          plantingSiteId = event.plantingSiteId,
          scientificName = scientificName,
          shortText = context.subjectShortText<PlantingSeasonSpeciesTargetSubjectPayload>(),
          speciesId = event.speciesId,
          stratumName = event.stratumName,
          substratumHistoryId = event.substratumHistoryId,
          substratumId = event.substratumId,
          substratumName = event.substratumName,
      )
    }
  }
}

@JsonTypeName("PlantingSeasonWithdrawal")
data class PlantingSeasonWithdrawalSubjectPayload(
    val facilityId: FacilityId,
    override val fullText: String,
    val plantingSeasonId: PlantingSeasonId,
    val plantingSiteId: PlantingSiteId,
    override val shortText: String,
    val withdrawalDate: LocalDate,
    val withdrawalId: WithdrawalId,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: PlantingSeasonWithdrawalCreatedEvent,
        context: EventLogPayloadContext,
    ): PlantingSeasonWithdrawalSubjectPayload {
      return PlantingSeasonWithdrawalSubjectPayload(
          facilityId = event.facilityId,
          fullText =
              context.subjectFullText<PlantingSeasonWithdrawalSubjectPayload>(event.withdrawalDate),
          plantingSeasonId = event.plantingSeasonId,
          plantingSiteId = event.plantingSiteId,
          shortText = context.subjectShortText<PlantingSeasonWithdrawalSubjectPayload>(),
          withdrawalDate = event.withdrawalDate,
          withdrawalId = event.withdrawalId,
      )
    }
  }
}

@JsonTypeName("Project")
data class ProjectSubjectPayload(
    override val fullText: String,
    val projectId: ProjectId,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: ProjectPersistentEvent,
        context: EventLogPayloadContext,
    ): ProjectSubjectPayload {
      val name = getPreviousName(event, context)

      return ProjectSubjectPayload(
          fullText = context.subjectFullText<ProjectSubjectPayload>(name),
          projectId = event.projectId,
          shortText = context.subjectShortText<ProjectSubjectPayload>(),
      )
    }

    fun getPreviousName(event: ProjectPersistentEvent, context: EventLogPayloadContext): String {
      val lastRename =
          context.lastEventBefore<ProjectRenamedEvent>(event) { it.projectId == event.projectId }
      return lastRename?.changedTo?.name
          ?: context.first<ProjectCreatedEvent> { it.projectId == event.projectId }.name
    }
  }
}

@JsonTypeName("RecordedTree")
data class RecordedTreeSubjectPayload(
    override val fullText: String,
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId,
    val plantingSiteId: PlantingSiteId,
    val recordedTreeId: RecordedTreeId,
    val treeGrowthForm: TreeGrowthForm,
    val treeNumber: Int,
    val trunkNumber: Int,
    override val shortText: String,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: RecordedTreePersistentEvent,
        context: EventLogPayloadContext,
    ): RecordedTreeSubjectPayload {
      val createEvent =
          context.first<RecordedTreeCreatedEvent> { it.recordedTreeId == event.recordedTreeId }
      val growthFormName: String
      val treeIdentifier: String

      if (createEvent.treeGrowthForm == TreeGrowthForm.Trunk) {
        growthFormName = TreeGrowthForm.Tree.getDisplayName(currentUser().locale)
        treeIdentifier = "${createEvent.treeNumber}_${createEvent.trunkNumber}"
      } else {
        growthFormName = createEvent.treeGrowthForm.getDisplayName(currentUser().locale)
        treeIdentifier = "${createEvent.treeNumber}"
      }

      return RecordedTreeSubjectPayload(
          fullText =
              context.subjectFullText<RecordedTreeSubjectPayload>(growthFormName, treeIdentifier),
          monitoringPlotId = event.monitoringPlotId,
          observationId = event.observationId,
          plantingSiteId = event.plantingSiteId,
          recordedTreeId = event.recordedTreeId,
          treeGrowthForm = createEvent.treeGrowthForm,
          treeNumber = createEvent.treeNumber,
          trunkNumber = createEvent.trunkNumber,
          shortText = context.subjectShortText<RecordedTreeSubjectPayload>(growthFormName),
      )
    }
  }
}

@JsonTypeName("ViabilityTest")
data class ViabilityTestSubjectPayload(
    val accessionId: AccessionId,
    override val deleted: Boolean?,
    val facilityId: FacilityId,
    override val fullText: String,
    override val shortText: String,
    val viabilityTestId: ViabilityTestId,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: ViabilityTestPersistentEvent,
        context: EventLogPayloadContext,
    ): ViabilityTestSubjectPayload {
      val deleteEvent =
          context.firstOrNull<ViabilityTestDeletedEvent> {
            it.viabilityTestId == event.viabilityTestId
          }

      return ViabilityTestSubjectPayload(
          accessionId = event.accessionId,
          deleted = if (deleteEvent != null) true else null,
          facilityId = event.facilityId,
          fullText = context.subjectFullText<ViabilityTestSubjectPayload>(event.viabilityTestId),
          shortText = context.subjectShortText<ViabilityTestSubjectPayload>(),
          viabilityTestId = event.viabilityTestId,
      )
    }
  }
}

@JsonTypeName("Withdrawal")
data class WithdrawalSubjectPayload(
    val accessionId: AccessionId,
    override val deleted: Boolean?,
    val facilityId: FacilityId,
    override val fullText: String,
    override val shortText: String,
    val withdrawalId: com.terraformation.backend.db.seedbank.WithdrawalId,
) : EventSubjectPayload {
  companion object {
    fun forEvent(
        event: WithdrawalPersistentEvent,
        context: EventLogPayloadContext,
    ): WithdrawalSubjectPayload {
      val deleteEvent =
          context.firstOrNull<WithdrawalDeletedEvent> { it.withdrawalId == event.withdrawalId }

      return WithdrawalSubjectPayload(
          accessionId = event.accessionId,
          deleted = if (deleteEvent != null) true else null,
          facilityId = event.facilityId,
          fullText = context.subjectFullText<WithdrawalSubjectPayload>(event.withdrawalId),
          shortText = context.subjectShortText<WithdrawalSubjectPayload>(),
          withdrawalId = event.withdrawalId,
      )
    }
  }
}

/**
 * Types of subjects that can be returned by the event log query API. Each subject maps to an
 * interface that's implemented by events related to that subject.
 *
 * The entries in this enum must match the [JsonTypeName] annotations on the subject payload
 * classes.
 */
enum class EventSubjectName(val eventInterface: KClass<out PersistentEvent>) {
  Accession(AccessionPersistentEvent::class),
  AccessionPhoto(AccessionPhotoPersistentEvent::class),
  BiomassDetails(BiomassDetailsPersistentEvent::class),
  BiomassQuadrat(BiomassQuadratPersistentEvent::class),
  BiomassQuadratSpecies(BiomassQuadratSpeciesPersistentEvent::class),
  BiomassSpecies(BiomassSpeciesPersistentEvent::class),
  MonitoringSpecies(MonitoringSpeciesPersistentEvent::class),
  ObservationPlot(ObservationPlotPersistentEvent::class),
  ObservationPlotMedia(ObservationMediaFilePersistentEvent::class),
  Organization(OrganizationPersistentEvent::class),
  PlantingDateRequest(PlantingDateRequestPersistentEvent::class),
  PlantingDateRequestSpecies(PlantingDateRequestSpeciesPersistentEvent::class),
  PlantingSeason(PlantingSeasonPersistentEvent::class),
  PlantingSeasonAllocatedSpecies(PlantingSeasonAllocatedSpeciesPersistentEvent::class),
  PlantingSeasonScheduledDate(PlantingSeasonScheduledDatePersistentEvent::class),
  PlantingSeasonScheduledDateSpecies(PlantingSeasonScheduledDateSpeciesPersistentEvent::class),
  PlantingSeasonSpeciesTarget(PlantingSeasonSpeciesTargetPersistentEvent::class),
  PlantingSeasonWithdrawal(PlantingSeasonWithdrawalCreatedEvent::class),
  Project(ProjectPersistentEvent::class),
  RecordedTree(RecordedTreePersistentEvent::class),
  ViabilityTest(ViabilityTestPersistentEvent::class),
  Withdrawal(WithdrawalPersistentEvent::class),
}
