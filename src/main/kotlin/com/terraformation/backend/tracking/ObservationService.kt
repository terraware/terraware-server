package com.terraformation.backend.tracking

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationMediaFilesDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationMediaFilesRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.event.FileReferenceDeletedEvent
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.file.mux.MuxStreamModel
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.tracking.db.InvalidObservationEndDateException
import com.terraformation.backend.tracking.db.InvalidObservationStartDateException
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoSubzonesException
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.ObservationPlotNotFoundException
import com.terraformation.backend.tracking.db.ObservationRescheduleStateException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteNotDetailedException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotCompletedException
import com.terraformation.backend.tracking.db.PlotNotFoundException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import com.terraformation.backend.tracking.db.PlotSizeNotReplaceableException
import com.terraformation.backend.tracking.db.ScheduleObservationWithoutPlantsException
import com.terraformation.backend.tracking.event.ObservationMediaFileDeletedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEvent
import com.terraformation.backend.tracking.event.ObservationMediaFileEditedEventValues
import com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEvent
import com.terraformation.backend.tracking.event.ObservationNotStartedEvent
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.NewBiomassDetailsModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NotificationCriteria
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSubzoneFullException
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import jakarta.inject.Named
import java.io.InputStream
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.jooq.Condition
import org.jooq.DSLContext
import org.locationtech.jts.geom.Point
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.security.access.AccessDeniedException

/** Number of seconds of tolerance when checking if observation time is before server clock time */
const val CLOCK_TOLERANCE_SECONDS: Long = 3600

@Named
class ObservationService(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val monitoringPlotsDao: MonitoringPlotsDao,
    private val muxService: MuxService,
    private val observationMediaFilesDao: ObservationMediaFilesDao,
    private val observationStore: ObservationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val parentStore: ParentStore,
    private val systemUser: SystemUser,
    private val thumbnailService: ThumbnailService,
) {
  private val log = perClassLogger()

  fun startObservation(observationId: ObservationId) {
    requirePermissions { manageObservation(observationId) }

    log.withMDC("observationId" to observationId) {
      observationStore.withLockedObservation(observationId) { observation ->
        if (observation.state != ObservationState.Upcoming) {
          throw ObservationAlreadyStartedException(observationId)
        }

        if (observationStore.hasPlots(observationId)) {
          log.error("BUG! Observation has plots but state is still ${observation.state}")
          throw ObservationAlreadyStartedException(observationId)
        }

        if (observation.requestedSubzoneIds.isEmpty()) {
          throw ObservationHasNoSubzonesException(observationId)
        }

        plantingSiteStore.ensurePermanentPlotsExist(observation.plantingSiteId)

        val plantingSite =
            plantingSiteStore.fetchSiteById(observation.plantingSiteId, PlantingSiteDepth.Plot)
        val gridOrigin =
            plantingSite.gridOrigin
                ?: throw IllegalStateException("Planting site has no grid origin")

        log.info("Starting observation")

        try {
          plantingSite.plantingZones.forEach { plantingZone ->
            log.withMDC("plantingZoneId" to plantingZone.id) {
              if (plantingZone.plantingSubzones.any { it.id in observation.requestedSubzoneIds }) {
                val permanentPlotIds =
                    plantingZone.choosePermanentPlots(observation.requestedSubzoneIds)
                val temporaryPlotIds =
                    plantingZone
                        .chooseTemporaryPlots(
                            observation.requestedSubzoneIds,
                            gridOrigin,
                            plantingSite.exclusion,
                        )
                        .map { plotBoundary ->
                          plantingSiteStore.createTemporaryPlot(
                              plantingSite.id,
                              plantingZone.id,
                              plotBoundary,
                          )
                        }

                observationStore.addPlotsToObservation(
                    observationId,
                    permanentPlotIds,
                    isPermanent = true,
                )
                observationStore.addPlotsToObservation(
                    observationId,
                    temporaryPlotIds,
                    isPermanent = false,
                )

                log.info(
                    "Added ${permanentPlotIds.size} permanent and ${temporaryPlotIds.size} " +
                        "temporary plots"
                )
              } else {
                log.info("Skipping zone because it has no reported plants")
              }
            }
          }

          observationStore.populateCumulativeDead(observationId)
          val startedObservation = observationStore.recordObservationStart(observationId)

          eventPublisher.publishEvent(ObservationStartedEvent(startedObservation))
        } catch (e: PlantingSubzoneFullException) {
          log.info("Unable to start observation $observationId", e)

          eventPublisher.publishEvent(
              ObservationNotStartedEvent(observationId, observation.plantingSiteId)
          )

          observationStore.abandonObservation(observationId)
        }
      }
    }
  }

  fun readPhoto(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      fileId: FileId,
      maxWidth: Int? = null,
      maxHeight: Int? = null,
  ): SizedInputStream {
    requirePermissions { readObservation(observationId) }

    ensureMediaFileExists(observationId, monitoringPlotId, fileId)

    return thumbnailService.readFile(fileId, maxWidth, maxHeight)
  }

  fun getMuxStreamInfo(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      fileId: FileId,
  ): MuxStreamModel {
    requirePermissions { readObservation(observationId) }

    ensureMediaFileExists(observationId, monitoringPlotId, fileId)

    return muxService.getMuxStream(fileId)
  }

  fun storeMediaFile(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      position: ObservationPlotPosition?,
      data: InputStream,
      metadata: NewFileMetadata,
      caption: String?,
      isOriginal: Boolean,
      type: ObservationMediaType = ObservationMediaType.Plot,
  ): FileId {
    requirePermissions { updateObservation(observationId) }

    val plantingSiteId =
        parentStore.getPlantingSiteId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val organizationId =
        parentStore.getOrganizationId(plantingSiteId)
            ?: throw ObservationNotFoundException(observationId)

    if (metadata.geolocation == null && isOriginal) {
      throw IllegalArgumentException("Geolocation is required for original observation photos")
    }
    if (type == ObservationMediaType.Quadrat && position == null) {
      throw IllegalArgumentException("Position is required for quadrat media file")
    }

    if (type == ObservationMediaType.Soil && position != null) {
      throw IllegalArgumentException("Position must be null for a soil media file")
    }

    val fileId =
        fileService.storeFile("observation", data, metadata) { (fileId, fullMetadata) ->
          observationMediaFilesDao.insert(
              ObservationMediaFilesRow(
                  caption = caption,
                  fileId = fileId,
                  isOriginal = isOriginal,
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
                  positionId = position,
                  typeId = type,
              )
          )

          eventPublisher.publishEvent(
              ObservationMediaFileUploadedEvent(
                  caption = caption,
                  contentType = fullMetadata.contentType,
                  geolocation = fullMetadata.geolocation,
                  fileId = fileId,
                  isOriginal = isOriginal,
                  observationId = observationId,
                  organizationId = organizationId,
                  plantingSiteId = plantingSiteId,
                  position = position,
                  monitoringPlotId = monitoringPlotId,
                  type = type,
              )
          )
        }

    log.info(
        "Stored media file $fileId of type $type for observation $observationId of plot $monitoringPlotId"
    )

    return fileId
  }

  fun updateMediaFile(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      fileId: FileId,
      updateFunc: (ObservationMediaFilesRow) -> ObservationMediaFilesRow,
  ) {
    requirePermissions { updateObservation(observationId) }

    val plantingSiteId =
        parentStore.getPlantingSiteId(observationId)
            ?: throw ObservationNotFoundException(observationId)
    val organizationId =
        parentStore.getOrganizationId(plantingSiteId)
            ?: throw ObservationNotFoundException(observationId)

    val initialRow = fetchMediaFilesRow(observationId, monitoringPlotId, fileId)
    val updatedRow = updateFunc(initialRow)

    if (initialRow != updatedRow) {
      observationMediaFilesDao.update(initialRow.copy(caption = updatedRow.caption))
      fileService.touchFile(fileId)
      eventPublisher.publishEvent(
          ObservationMediaFileEditedEvent(
              changedFrom = ObservationMediaFileEditedEventValues(initialRow.caption),
              changedTo = ObservationMediaFileEditedEventValues(updatedRow.caption),
              fileId = fileId,
              monitoringPlotId = monitoringPlotId,
              observationId = observationId,
              organizationId = organizationId,
              plantingSiteId = plantingSiteId,
          )
      )
    }
  }

  fun deleteMediaFile(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      fileId: FileId,
  ) {
    requirePermissions { updateObservation(observationId) }

    val row = fetchMediaFilesRow(observationId, monitoringPlotId, fileId)

    if (row.isOriginal == true) {
      throw AccessDeniedException("Cannot delete media from the original observation")
    }

    deleteMediaWhere(OBSERVATION_MEDIA_FILES.FILE_ID.eq(fileId))
  }

  private fun ensureMediaFileExists(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      fileId: FileId,
  ) {
    fetchMediaFilesRow(observationId, monitoringPlotId, fileId)
  }

  private fun fetchMediaFilesRow(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      fileId: FileId,
  ): ObservationMediaFilesRow {
    val row =
        observationMediaFilesDao.fetchOneByFileId(fileId) ?: throw FileNotFoundException(fileId)
    if (row.observationId != observationId || row.monitoringPlotId != monitoringPlotId) {
      throw FileNotFoundException(fileId)
    }

    return row
  }

  fun scheduleObservation(observation: NewObservationModel): ObservationId {
    requirePermissions { scheduleObservation(observation.plantingSiteId) }

    validateSchedule(observation.plantingSiteId, observation.startDate, observation.endDate)

    if (!plantingSiteStore.hasSubzonePlantings(observation.plantingSiteId)) {
      throw ScheduleObservationWithoutPlantsException(observation.plantingSiteId)
    }

    val observationId = observationStore.createObservation(observation)
    eventPublisher.publishEvent(
        ObservationScheduledEvent(observationStore.fetchObservationById(observationId))
    )
    return observationId
  }

  fun rescheduleObservation(
      observationId: ObservationId,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    requirePermissions { rescheduleObservation(observationId) }

    val observation = observationStore.fetchObservationById(observationId)

    validateSchedule(observation.plantingSiteId, startDate, endDate)

    if (observation.state == ObservationState.Completed) {
      throw ObservationRescheduleStateException(observationId)
    }

    if (observation.state != ObservationState.Upcoming) {
      val plotCounts = observationStore.countPlots(observationId)
      // check for no observed plots
      if (plotCounts.totalPlots != plotCounts.totalIncomplete) {
        throw ObservationRescheduleStateException(observationId)
      }
    }

    observationStore.rescheduleObservation(observationId, startDate, endDate)
    eventPublisher.publishEvent(
        ObservationRescheduledEvent(
            observation,
            observationStore.fetchObservationById(observation.id),
        )
    )
  }

  /** Fetch sites satisfying the input criteria */
  fun fetchNonNotifiedSitesToNotifySchedulingObservations(
      criteria: NotificationCriteria.ObservationScheduling
  ): Collection<PlantingSiteId> {
    requirePermissions { manageNotifications() }

    return fetchNonNotifiedSitesForThresholds(
        criteria.completedTimeElapsedWeeks,
        criteria.firstPlantingElapsedWeeks,
        plantingSiteStore.fetchSitesWithSubzonePlantings(
            criteria.notificationNotCompletedCondition(requirePrevious = true)
        ),
    )
  }

  /** Mark notification to schedule observations as complete */
  fun markSchedulingObservationsNotificationComplete(
      plantingSiteId: PlantingSiteId,
      criteria: NotificationCriteria.ObservationScheduling,
  ) {
    requirePermissions {
      readPlantingSite(plantingSiteId)
      manageNotifications()
    }

    plantingSiteStore.markNotificationComplete(
        plantingSiteId,
        criteria.notificationType,
        criteria.notificationNumber,
    )
  }

  /** Replaces a monitoring plot in an observation with a different one if possible. */
  fun replaceMonitoringPlot(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      justification: String,
      duration: ReplacementDuration,
      allowCompleted: Boolean = false,
  ): ReplacementResult {
    requirePermissions { replaceObservationPlot(observationId) }

    return observationStore.withLockedObservation(observationId) { observation ->
      if (observation.isAdHoc) {
        throw IllegalStateException("Ad-hoc observation plot cannot be replaced.")
      }
      val addedPlotIds = mutableSetOf<MonitoringPlotId>()
      val removedPlotIds = mutableSetOf(monitoringPlotId)
      val plantingSite =
          plantingSiteStore.fetchSiteById(observation.plantingSiteId, PlantingSiteDepth.Plot)
      val gridOrigin =
          plantingSite.gridOrigin
              ?: throw IllegalStateException("Planting site ${plantingSite.id} has no grid origin")
      val plantingZone = plantingSite.findZoneWithMonitoringPlot(monitoringPlotId)
      val plantingSubzone =
          plantingZone?.findSubzoneWithMonitoringPlot(monitoringPlotId)
              ?: throw PlotNotFoundException(monitoringPlotId)
      val observationPlots = observationStore.fetchObservationPlotDetails(observationId)
      val observationPlotIds = observationPlots.map { it.model.monitoringPlotId }.toSet()
      val observationPlot =
          observationPlots.firstOrNull { it.model.monitoringPlotId == monitoringPlotId }
              ?: throw PlotNotInObservationException(observationId, monitoringPlotId)
      val monitoringPlotsRow =
          monitoringPlotsDao.fetchOneById(observationPlot.model.monitoringPlotId)

      if (monitoringPlotsRow?.sizeMeters != MONITORING_PLOT_SIZE_INT) {
        throw PlotSizeNotReplaceableException(observationId, monitoringPlotId)
      }

      if (!allowCompleted && observationPlot.model.completedTime != null) {
        throw PlotAlreadyCompletedException(monitoringPlotId)
      }

      log.info("Replacing monitoring plot $monitoringPlotId in observation $observationId")

      if (observationPlot.model.isPermanent) {
        val isFirstObservation =
            observationStore.fetchObservationsByPlantingSite(observation.plantingSiteId).none {
              it.state == ObservationState.Completed
            }
        val observationHasResults = observationPlots.any { it.model.completedTime != null }

        if (isFirstObservation && !observationHasResults) {
          val replacementResult = plantingSiteStore.replacePermanentIndex(monitoringPlotId)

          if (duration == ReplacementDuration.LongTerm) {
            plantingSiteStore.makePlotUnavailable(monitoringPlotId)
          }

          // At this point we will usually have replaced the plot with a new one in a random place
          // in the zone that hasn't been used in an observation yet.
          //
          // If this is the first observation, there's a good chance that there are still subzones
          // that haven't completed planting, and a chance that the replacement plot be in one of
          // the incomplete subzones. In that case, we want the same behavior as during initial
          // permanent plot selection: the plot is excluded from the observation even if that means
          // the observation has fewer than the configured number of permanent plots.
          val addedPlotsInRequestedSubzones =
              if (replacementResult.addedMonitoringPlotIds.isNotEmpty()) {
                val subzones =
                    plantingZone.plantingSubzones.filter { subzone ->
                      subzone.monitoringPlots.any {
                        it.id in replacementResult.addedMonitoringPlotIds
                      }
                    }
                if (subzones.all { it.id in observation.requestedSubzoneIds }) {
                  log.info(
                      "Replacement permanent plot is in a subzone that has completed planting; " +
                          "including the plot in this observation."
                  )
                  replacementResult.addedMonitoringPlotIds
                } else {
                  log.info(
                      "Replacement permanent plot is in a subzone that has not completed " +
                          "planting; not including it in this observation."
                  )
                  emptySet()
                }
              } else {
                emptySet()
              }

          observationStore.removePlotsFromObservation(
              observationId,
              replacementResult.removedMonitoringPlotIds,
          )
          removedPlotIds.addAll(replacementResult.removedMonitoringPlotIds)

          if (addedPlotsInRequestedSubzones.isNotEmpty()) {
            observationStore.addPlotsToObservation(
                observationId,
                addedPlotsInRequestedSubzones,
                isPermanent = true,
            )
            addedPlotIds.addAll(addedPlotsInRequestedSubzones)
          }
        } else {
          log.info(
              "Permanent plot at a site that already has observation data; removing it from this " +
                  "observation but it may be included in future ones."
          )
          observationStore.removePlotsFromObservation(observationId, listOf(monitoringPlotId))
        }
      } else {
        observationStore.removePlotsFromObservation(observationId, listOf(monitoringPlotId))

        if (duration == ReplacementDuration.LongTerm) {
          plantingSiteStore.makePlotUnavailable(monitoringPlotId)
        }

        val replacementPlotBoundary =
            plantingZone
                .findUnusedSquares(
                    count = 1,
                    excludePlotIds = observationPlotIds,
                    exclusion = plantingSite.exclusion,
                    gridOrigin = gridOrigin,
                    searchBoundary = plantingSubzone.boundary,
                )
                .firstOrNull()

        if (replacementPlotBoundary != null) {
          val replacementPlotId =
              plantingSiteStore.createTemporaryPlot(
                  plantingSite.id,
                  plantingZone.id,
                  replacementPlotBoundary,
              )
          log.info("Adding replacement plot $replacementPlotId")
          addedPlotIds.add(replacementPlotId)
          observationStore.addPlotsToObservation(
              observationId,
              listOf(replacementPlotId),
              isPermanent = false,
          )
        } else {
          log.info("No other temporary plots available in subzone")
        }
      }

      eventPublisher.publishEvent(
          ObservationPlotReplacedEvent(duration, justification, observation, monitoringPlotId)
      )

      ReplacementResult(addedPlotIds, removedPlotIds)
    }
  }

  /**
   * Records an ad-hoc observation. This creates an ad-hoc observation, creates an ad-hoc monitoring
   * plot, adds the plot to the observation, and completes the observation.
   */
  fun completeAdHocObservation(
      biomassDetails: NewBiomassDetailsModel? = null,
      conditions: Set<ObservableCondition> = emptySet(),
      notes: String? = null,
      observedTime: Instant,
      observationType: ObservationType,
      plantingSiteId: PlantingSiteId,
      plants: Collection<RecordedPlantsRow> = emptySet(),
      swCorner: Point,
  ): Pair<ObservationId, MonitoringPlotId> {
    requirePermissions { scheduleAdHocObservation(plantingSiteId) }

    if (observedTime.isAfter(clock.instant().plusSeconds(CLOCK_TOLERANCE_SECONDS))) {
      throw IllegalArgumentException("Observed time is in the future")
    }

    if (!plantingSiteStore.isDetailed(plantingSiteId)) {
      throw PlantingSiteNotDetailedException(plantingSiteId)
    }

    when (observationType) {
      ObservationType.BiomassMeasurements -> {
        if (biomassDetails == null) {
          throw IllegalArgumentException("Biomass observations must contain biomass details")
        }
        if (plants.isNotEmpty()) {
          throw IllegalArgumentException("Biomass observations must not contain plants")
        }
      }
      ObservationType.Monitoring -> {
        if (biomassDetails != null) {
          throw IllegalArgumentException("Monitoring observations must not contain biomass details")
        }
      }
    }

    val effectiveTimeZone = parentStore.getEffectiveTimeZone(plantingSiteId)
    val date = LocalDate.ofInstant(observedTime, effectiveTimeZone)

    return dslContext.transactionResult { _ ->
      val observationId =
          observationStore.createObservation(
              NewObservationModel(
                  endDate = date,
                  id = null,
                  isAdHoc = true,
                  observationType = observationType,
                  plantingSiteId = plantingSiteId,
                  requestedSubzoneIds = emptySet(),
                  startDate = date,
                  state = ObservationState.Upcoming,
              )
          )

      val plotId = plantingSiteStore.createAdHocMonitoringPlot(plantingSiteId, swCorner)
      observationStore.addAdHocPlotToObservation(observationId, plotId)

      systemUser.run { observationStore.recordObservationStart(observationId) }

      observationStore.claimPlot(observationId, plotId)

      biomassDetails?.let { observationStore.insertBiomassDetails(observationId, plotId, it) }

      observationStore.completePlot(
          observationId,
          plotId,
          conditions,
          notes,
          observedTime,
          plants,
      )

      observationId to plotId
    }
  }

  /**
   * Performs update operations on a completed monitoring plot in an observation. The actual updates
   * are done by [func]; this is a wrapper that checks permissions and observation status and runs
   * the updates in a transaction with the observation locked to avoid problems with concurrent
   * updates.
   */
  fun <T> updateCompletedPlot(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      func: () -> T,
  ): T {
    requirePermissions { updateObservation(observationId) }

    val plotStatus =
        dslContext.fetchValue(
            OBSERVATION_PLOTS.STATUS_ID,
            OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotId)
                .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)),
        ) ?: throw ObservationPlotNotFoundException(observationId, monitoringPlotId)

    if (plotStatus != ObservationPlotStatus.Completed) {
      throw PlotNotCompletedException(monitoringPlotId)
    }

    return observationStore.withLockedObservation(observationId) { _ -> func() }
  }

  @EventListener
  fun on(event: PlantingSiteDeletionStartedEvent) {
    deleteMediaWhere(
        OBSERVATION_MEDIA_FILES.monitoringPlots.plantingSubzones.PLANTING_SITE_ID.eq(
            event.plantingSiteId
        )
    )
  }

  /** Updates observations, if any, when a site's map is edited. */
  @EventListener
  fun on(event: PlantingSiteMapEditedEvent) {
    dslContext.transaction { _ ->
      // Abandon any active observations with incomplete plots in planting zones that are affected
      // by the edit.
      val affectedPlantingZoneIds =
          event.plantingSiteEdit.plantingZoneEdits.mapNotNull { it.existingModel?.id }
      val affectedObservationIds =
          observationStore.fetchActiveObservationIds(event.edited.id, affectedPlantingZoneIds)

      affectedObservationIds.forEach { observationId ->
        observationStore.abandonObservation(observationId)
      }
    }
  }

  /**
   * Validation rules:
   * 1. start date can be up to one year from today and not earlier than today.
   * 2. end date should be after the start date but no more than 2 months from the start date.
   */
  private fun validateSchedule(
      plantingSiteId: PlantingSiteId,
      startDate: LocalDate,
      endDate: LocalDate,
  ) {
    val today =
        LocalDate.ofInstant(clock.instant(), parentStore.getEffectiveTimeZone(plantingSiteId))

    if (startDate.isBefore(today) || startDate.isAfter(today.plusYears(1))) {
      throw InvalidObservationStartDateException(startDate)
    }

    if (endDate.isBefore(startDate.plusDays(1)) || endDate.isAfter(startDate.plusMonths(2))) {
      throw InvalidObservationEndDateException(startDate, endDate)
    }
  }

  private fun elapsedWeeks(source: Instant, weeks: Long): Boolean =
      source.isBefore(clock.instant().minus(weeks * 7, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS))

  private fun earliestPlantingElapsedWeeks(plantingSiteId: PlantingSiteId, weeks: Long): Boolean =
      !observationStore.hasObservations(plantingSiteId) &&
          (plantingSiteStore.fetchOldestPlantingTime(plantingSiteId)?.let {
            elapsedWeeks(it, weeks)
          } ?: false)

  private fun fetchNonNotifiedSitesForThresholds(
      completedTimeElapsedWeeks: Long,
      firstPlantingElapsedWeeks: Long,
      siteIds: List<PlantingSiteId>,
  ): Collection<PlantingSiteId> {
    val observationCompletedTimes =
        siteIds.associateWith { observationStore.fetchLastCompletedObservationTime(it) }

    return siteIds.filter { plantingSiteId ->
      observationCompletedTimes[plantingSiteId]?.let { elapsedWeeks(it, completedTimeElapsedWeeks) }
          ?: earliestPlantingElapsedWeeks(plantingSiteId, firstPlantingElapsedWeeks)
    }
  }

  private fun deleteMediaWhere(condition: Condition) {
    with(OBSERVATION_MEDIA_FILES) {
      dslContext
          .select(
              FILE_ID.asNonNullable(),
              MONITORING_PLOT_ID.asNonNullable(),
              OBSERVATION_ID.asNonNullable(),
              observations.plantingSites.ORGANIZATION_ID.asNonNullable(),
              observations.plantingSites.ID.asNonNullable(),
          )
          .from(OBSERVATION_MEDIA_FILES)
          .where(condition)
          .fetch()
          .forEach { (fileId, monitoringPlotId, observationId, organizationId, plantingSiteId) ->
            observationMediaFilesDao.deleteById(fileId)
            eventPublisher.publishEvent(
                ObservationMediaFileDeletedEvent(
                    fileId,
                    monitoringPlotId,
                    observationId,
                    organizationId,
                    plantingSiteId,
                )
            )
            eventPublisher.publishEvent(FileReferenceDeletedEvent(fileId))
          }
    }
  }
}
