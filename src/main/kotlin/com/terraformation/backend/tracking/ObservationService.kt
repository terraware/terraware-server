package com.terraformation.backend.tracking

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.MonitoringPlotsDao
import com.terraformation.backend.db.tracking.tables.daos.ObservationPhotosDao
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPhotosRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PHOTOS
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.tracking.db.InvalidObservationEndDateException
import com.terraformation.backend.tracking.db.InvalidObservationStartDateException
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoPlotsException
import com.terraformation.backend.tracking.db.ObservationRescheduleStateException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotFoundException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import com.terraformation.backend.tracking.db.PlotSizeNotReplaceableException
import com.terraformation.backend.tracking.db.ScheduleObservationWithoutPlantsException
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteMapEditedEvent
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NotificationCriteria
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import jakarta.inject.Named
import java.io.InputStream
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.jooq.Condition
import org.jooq.DSLContext
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class ObservationService(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val monitoringPlotsDao: MonitoringPlotsDao,
    private val observationPhotosDao: ObservationPhotosDao,
    private val observationStore: ObservationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val parentStore: ParentStore,
) {
  private val log = perClassLogger()

  fun completeAdHocPlotMonitoringObservation(
      boundary: Polygon,
      conditions: Set<ObservableCondition>,
      name: String,
      notes: String?,
      observedTime: Instant,
      plantingSiteId: PlantingSiteId,
      plants: Collection<RecordedPlantsRow>,
  ): Pair<MonitoringPlotId, ObservationId> {
    return dslContext.transactionResult { _ ->
      val (monitoringPlotId, observationId) =
          createAdHocPlotObservation(
              boundary, name, ObservationType.Monitoring, observedTime, plantingSiteId)

      observationStore.completePlot(
          observationId, monitoringPlotId, conditions, notes, observedTime, plants)

      monitoringPlotId to observationId
    }
  }

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

        plantingSiteStore.ensurePermanentClustersExist(observation.plantingSiteId)
        plantingSiteStore.convert25MeterClusters(observation.plantingSiteId)

        val plantingSite =
            plantingSiteStore.fetchSiteById(observation.plantingSiteId, PlantingSiteDepth.Plot)
        val plantedSubzoneIds =
            plantingSiteStore.countReportedPlantsInSubzones(plantingSite.id).keys
        val gridOrigin =
            plantingSite.gridOrigin
                ?: throw IllegalStateException("Planting site has no grid origin")

        if (plantedSubzoneIds.isEmpty()) {
          throw ObservationHasNoPlotsException(observationId)
        }

        log.info("Starting observation")

        plantingSite.plantingZones.forEach { plantingZone ->
          log.withMDC("plantingZoneId" to plantingZone.id) {
            if (plantingZone.plantingSubzones.any { it.id in plantedSubzoneIds }) {
              val permanentPlotIds =
                  plantingZone.choosePermanentPlots(
                      plantedSubzoneIds, observation.requestedSubzoneIds)
              val temporaryPlotIds =
                  plantingZone
                      .chooseTemporaryPlots(
                          plantedSubzoneIds,
                          gridOrigin,
                          plantingSite.exclusion,
                          observation.requestedSubzoneIds)
                      .map { plotBoundary ->
                        plantingSiteStore.createTemporaryPlot(
                            plantingSite.id, plantingZone.id, plotBoundary)
                      }

              observationStore.addPlotsToObservation(
                  observationId, permanentPlotIds, isPermanent = true)
              observationStore.addPlotsToObservation(
                  observationId, temporaryPlotIds, isPermanent = false)

              log.info(
                  "Added ${permanentPlotIds.size} permanent and ${temporaryPlotIds.size} " +
                      "temporary plots")
            } else {
              log.info("Skipping zone because it has no reported plants")
            }
          }
        }

        observationStore.populateCumulativeDead(observationId)
        observationStore.updateObservationState(observationId, ObservationState.InProgress)

        eventPublisher.publishEvent(
            ObservationStartedEvent(observation.copy(state = ObservationState.InProgress)))
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

    val photosRow =
        observationPhotosDao.fetchOneByFileId(fileId) ?: throw FileNotFoundException(fileId)
    if (photosRow.observationId != observationId ||
        photosRow.monitoringPlotId != monitoringPlotId) {
      throw FileNotFoundException(fileId)
    }

    return fileService.readFile(fileId, maxWidth, maxHeight)
  }

  fun storePhoto(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      gpsCoordinates: Point,
      position: ObservationPlotPosition,
      data: InputStream,
      metadata: NewFileMetadata
  ): FileId {
    requirePermissions { updateObservation(observationId) }

    val fileId =
        fileService.storeFile("observation", data, metadata) { fileId ->
          observationPhotosDao.insert(
              ObservationPhotosRow(
                  fileId = fileId,
                  gpsCoordinates = gpsCoordinates,
                  monitoringPlotId = monitoringPlotId,
                  observationId = observationId,
                  positionId = position,
              ))
        }

    log.info("Stored photo $fileId for observation $observationId of plot $monitoringPlotId")

    return fileId
  }

  fun scheduleObservation(observation: NewObservationModel): ObservationId {
    requirePermissions { scheduleObservation(observation.plantingSiteId) }

    validateSchedule(observation.plantingSiteId, observation.startDate, observation.endDate)

    if (!plantingSiteStore.hasSubzonePlantings(observation.plantingSiteId)) {
      throw ScheduleObservationWithoutPlantsException(observation.plantingSiteId)
    }

    val observationId = observationStore.createObservation(observation)
    eventPublisher.publishEvent(
        ObservationScheduledEvent(observationStore.fetchObservationById(observationId)))
    return observationId
  }

  fun rescheduleObservation(
      observationId: ObservationId,
      startDate: LocalDate,
      endDate: LocalDate
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
            observation, observationStore.fetchObservationById(observation.id)))
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
            criteria.notificationNotCompletedCondition(requirePrevious = true)))
  }

  /** Mark notification to schedule observations as complete */
  fun markSchedulingObservationsNotificationComplete(
      plantingSiteId: PlantingSiteId,
      criteria: NotificationCriteria.ObservationScheduling
  ) {
    requirePermissions {
      readPlantingSite(plantingSiteId)
      manageNotifications()
    }

    plantingSiteStore.markNotificationComplete(
        plantingSiteId, criteria.notificationType, criteria.notificationNumber)
  }

  /**
   * Replaces a monitoring plot in an observation with a different one if possible. May result in
   * additional monitoring plots being replaced if the requested one is part of a permanent plot
   * cluster in the observation.
   */
  fun replaceMonitoringPlot(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      justification: String,
      duration: ReplacementDuration,
      allowCompleted: Boolean = false,
  ): ReplacementResult {
    requirePermissions { replaceObservationPlot(observationId) }

    return observationStore.withLockedObservation(observationId) { observation ->
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
      val plantedSubzoneIds =
          plantingSiteStore.countReportedPlantsInSubzones(observation.plantingSiteId).keys
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
          val replacementResult = plantingSiteStore.replacePermanentCluster(monitoringPlotId)

          if (duration == ReplacementDuration.LongTerm) {
            plantingSiteStore.makePlotUnavailable(monitoringPlotId)
          }

          // At this point we will usually have replaced the cluster containing the plot we're
          // replacing with a new cluster in a random place in the zone that hasn't been used in an
          // observation yet.
          //
          // If this is the first observation, there's a good chance that there are still subzones
          // that haven't completed planting, and a chance that the replacement cluster will have
          // plots in one of the incomplete subzones. In that case, we want the same behavior as
          // during initial permanent cluster selection: the cluster is excluded from the
          // observation even if that means the observation has fewer than the configured number of
          // permanent clusters.
          val addedPlotsInPlantedSubzones =
              if (replacementResult.addedMonitoringPlotIds.isNotEmpty()) {
                val subzones =
                    plantingZone.plantingSubzones.filter { subzone ->
                      subzone.monitoringPlots.any {
                        it.id in replacementResult.addedMonitoringPlotIds
                      }
                    }
                if (subzones.all { it.id in plantedSubzoneIds }) {
                  log.info(
                      "Replacement permanent cluster's plots are all in subzones that have " +
                          "completed planting; including the cluster in this observation.")
                  replacementResult.addedMonitoringPlotIds
                } else {
                  log.info(
                      "Replacement permanent cluster has plots in subzones that have not " +
                          "completed planting; not including it in this observation.")
                  emptySet()
                }
              } else {
                emptySet()
              }

          observationStore.removePlotsFromObservation(
              observationId, replacementResult.removedMonitoringPlotIds)
          removedPlotIds.addAll(replacementResult.removedMonitoringPlotIds)

          if (addedPlotsInPlantedSubzones.isNotEmpty()) {
            observationStore.addPlotsToObservation(
                observationId, addedPlotsInPlantedSubzones, isPermanent = true)
            addedPlotIds.addAll(addedPlotsInPlantedSubzones)
          }
        } else {
          log.info(
              "Permanent plot at a site that already has observation data; removing it from this " +
                  "observation but it may be included in future ones.")
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
                  plantingSite.id, plantingZone.id, replacementPlotBoundary)
          log.info("Adding replacement plot $replacementPlotId")
          addedPlotIds.add(replacementPlotId)
          observationStore.addPlotsToObservation(
              observationId, listOf(replacementPlotId), isPermanent = false)
        } else {
          log.info("No other temporary plots available in subzone")
        }
      }

      eventPublisher.publishEvent(
          ObservationPlotReplacedEvent(duration, justification, observation, monitoringPlotId))

      ReplacementResult(addedPlotIds, removedPlotIds)
    }
  }

  @EventListener
  fun on(event: PlantingSiteDeletionStartedEvent) {
    deletePhotosWhere(
        OBSERVATION_PHOTOS.monitoringPlots.plantingSubzones.PLANTING_SITE_ID.eq(
            event.plantingSiteId))
  }

  /** Updates observations, if any, when a site's map is edited. */
  @EventListener
  fun on(event: PlantingSiteMapEditedEvent) {
    dslContext.transaction { _ ->
      // We no longer want plants from past observations of the removed plots to show up in the
      // per-species totals.
      event.monitoringPlotReplacements.removedMonitoringPlotIds.forEach {
        observationStore.removePlotFromTotals(it)
      }

      // If there's an observation in progress, try to replace any removed plots with new ones.
      val plantingSiteId = event.plantingSiteEdit.existingModel.id
      val observation =
          observationStore.fetchInProgressObservation(plantingSiteId) ?: return@transaction
      val observationId = observation.id
      val observationPlots = observationStore.fetchObservationPlotDetails(observationId)
      val plantedSubzoneIds = plantingSiteStore.countReportedPlantsInSubzones(plantingSiteId).keys

      val existingPermanentPlotIds =
          observationPlots.filter { it.model.isPermanent }.map { it.model.monitoringPlotId }
      val removedObservationPlots =
          observationPlots.filter {
            it.model.monitoringPlotId in event.monitoringPlotReplacements.removedMonitoringPlotIds
          }
      val removedPermanentPlots = removedObservationPlots.filter { it.model.isPermanent }
      val removedTemporaryPlots = removedObservationPlots.filterNot { it.model.isPermanent }

      observationStore.removePlotsFromObservation(
          observationId, removedPermanentPlots.map { it.model.monitoringPlotId })

      plantingSiteStore.ensurePermanentClustersExist(plantingSiteId)

      removedTemporaryPlots.forEach { plot ->
        replaceMonitoringPlot(
            observationId,
            plot.model.monitoringPlotId,
            "Planting site map edited",
            ReplacementDuration.LongTerm,
            true)
      }

      val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

      plantingSite.plantingZones.forEach { zone ->
        val clusterNumbersWithPlotsInUnplantedSubzones =
            zone.plantingSubzones
                .filterNot { it.id in plantedSubzoneIds }
                .flatMap { subzone -> subzone.monitoringPlots.mapNotNull { it.permanentCluster } }
                .toSet()

        val newPermanentPlotsInClustersWithoutPlotsInUnplantedSubzones =
            zone.plantingSubzones
                .flatMap { subzone ->
                  subzone.monitoringPlots.filter { plot ->
                    val plotNotAlreadyInObservation = plot.id !in existingPermanentPlotIds
                    val clusterNumberIsCandidateForObservation =
                        plot.permanentCluster != null &&
                            plot.permanentCluster <= zone.numPermanentClusters
                    val allPlotsInClusterAreInPlantedSubzones =
                        plot.permanentCluster !in clusterNumbersWithPlotsInUnplantedSubzones

                    plot.isAvailable &&
                        plotNotAlreadyInObservation &&
                        clusterNumberIsCandidateForObservation &&
                        allPlotsInClusterAreInPlantedSubzones
                  }
                }
                .map { it.id }

        observationStore.addPlotsToObservation(
            observationId, newPermanentPlotsInClustersWithoutPlotsInUnplantedSubzones, true)
      }
    }
  }

  /** Create monitoring plot, observation, and a claimed observation plot for the current user */
  private fun createAdHocPlotObservation(
      boundary: Polygon,
      name: String,
      observationType: ObservationType,
      observedTime: Instant,
      plantingSiteId: PlantingSiteId,
  ): Pair<MonitoringPlotId, ObservationId> {
    val userId = currentUser().userId
    val now = clock.instant()
    val observedDate = LocalDate.ofInstant(observedTime, ZoneOffset.UTC)

    return dslContext.transactionResult { _ ->
      val observationId =
          observationStore.createObservation(
              NewObservationModel(
                  startDate = observedDate,
                  endDate = observedDate,
                  id = null,
                  isAdHoc = true,
                  observationType = observationType,
                  plantingSiteId = plantingSiteId,
                  state = ObservationState.InProgress,
              ))

      val monitoringPlotsRow =
          MonitoringPlotsRow(
              boundary = boundary,
              createdBy = userId,
              createdTime = now,
              // Do we even need full name?
              fullName = name,
              isAvailable = false,
              modifiedBy = userId,
              modifiedTime = now,
              name = name,
              plantingSiteId = plantingSiteId,
              sizeMeters = MONITORING_PLOT_SIZE_INT,
          )

      monitoringPlotsDao.insert(monitoringPlotsRow)

      val monitoringPlotId = monitoringPlotsRow.id!!

      observationStore.addPlotsToObservation(observationId, listOf(monitoringPlotId), false)
      observationStore.claimPlot(observationId, monitoringPlotId)

      monitoringPlotId to observationId
    }
  }

  /**
   * Validation rules:
   * 1. start date can be up to one year from today and not earlier than today
   * 2. end date should be after the start date but no more than 2 months from the start date
   */
  private fun validateSchedule(
      plantingSiteId: PlantingSiteId,
      startDate: LocalDate,
      endDate: LocalDate
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
      siteIds: List<PlantingSiteId>
  ): Collection<PlantingSiteId> {
    val observationCompletedTimes =
        siteIds.associate { it to observationStore.fetchLastCompletedObservationTime(it) }

    return siteIds.filter { plantingSiteId ->
      observationCompletedTimes[plantingSiteId]?.let { elapsedWeeks(it, completedTimeElapsedWeeks) }
          ?: earliestPlantingElapsedWeeks(plantingSiteId, firstPlantingElapsedWeeks)
    }
  }

  private fun deletePhotosWhere(condition: Condition) {
    with(OBSERVATION_PHOTOS) {
      dslContext
          .select(FILE_ID)
          .from(OBSERVATION_PHOTOS)
          .where(condition)
          .fetch(FILE_ID.asNonNullable())
          .forEach { fileId ->
            fileService.deleteFile(fileId) { observationPhotosDao.deleteById(fileId) }
          }
    }
  }
}
