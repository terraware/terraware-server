package com.terraformation.backend.tracking

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPhotoPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.daos.ObservationPhotosDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPhotosRow
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
import com.terraformation.backend.tracking.db.ScheduleObservationWithoutPlantsException
import com.terraformation.backend.tracking.event.ObservationPlotReplacedEvent
import com.terraformation.backend.tracking.event.ObservationRescheduledEvent
import com.terraformation.backend.tracking.event.ObservationScheduledEvent
import com.terraformation.backend.tracking.event.ObservationStartedEvent
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
import java.time.temporal.ChronoUnit
import org.locationtech.jts.geom.Point
import org.springframework.context.ApplicationEventPublisher

@Named
class ObservationService(
    private val clock: InstantSource,
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val observationPhotosDao: ObservationPhotosDao,
    private val observationStore: ObservationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val parentStore: ParentStore,
    private val terrawareServerConfig: TerrawareServerConfig,
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

        val plantingSite =
            plantingSiteStore.fetchSiteById(observation.plantingSiteId, PlantingSiteDepth.Plot)
        val plantedSubzoneIds =
            plantingSiteStore.countReportedPlantsInSubzones(plantingSite.id).keys

        if (plantedSubzoneIds.isEmpty()) {
          throw ObservationHasNoPlotsException(observationId)
        }

        log.info("Starting observation")

        plantingSite.plantingZones.forEach { plantingZone ->
          log.withMDC("plantingZoneId" to plantingZone.id) {
            if (plantingZone.plantingSubzones.any { it.id in plantedSubzoneIds }) {
              val permanentPlotIds =
                  plantingSiteStore.fetchPermanentPlotIds(
                      plantingZone.id, plantingZone.numPermanentClusters)
              val temporaryPlotIds =
                  plantingZone.chooseTemporaryPlots(permanentPlotIds, plantedSubzoneIds)

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
      position: ObservationPhotoPosition,
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

    if (observation.state != ObservationState.Overdue) {
      throw ObservationRescheduleStateException(observationId)
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
            criteria.notificationNotCompletedCondition))
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

    plantingSiteStore.markNotificationComplete(plantingSiteId, criteria.notificationCompletedField)
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
  ): ReplacementResult {
    requirePermissions { replaceObservationPlot(observationId) }

    return observationStore.withLockedObservation(observationId) { observation ->
      val addedPlotIds = mutableSetOf<MonitoringPlotId>()
      val removedPlotIds = mutableSetOf(monitoringPlotId)
      val plantingSite =
          plantingSiteStore.fetchSiteById(observation.plantingSiteId, PlantingSiteDepth.Plot)
      val plantingZone = plantingSite.findZoneWithMonitoringPlot(monitoringPlotId)
      val plantingSubzone =
          plantingZone?.findSubzoneWithMonitoringPlot(monitoringPlotId)
              ?: throw PlotNotFoundException(monitoringPlotId)
      val observationPlots = observationStore.fetchObservationPlotDetails(observationId)
      val observationPlotIds = observationPlots.map { it.model.monitoringPlotId }.toSet()
      val observationPlot =
          observationPlots.firstOrNull { it.model.monitoringPlotId == monitoringPlotId }
              ?: throw PlotNotInObservationException(observationId, monitoringPlotId)

      if (observationPlot.model.completedTime != null) {
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
          val swappedPlots =
              if (duration == ReplacementDuration.Temporary) {
                log.info(
                    "Temporary replacement and this is the first observation; attempting to " +
                        "swap this permanent cluster with a higher-numbered one.")
                plantingSiteStore.swapWithLastPermanentCluster(monitoringPlotId)
              } else {
                ReplacementResult(emptySet(), emptySet())
              }

          val removalResult =
              if (swappedPlots.removedMonitoringPlotIds.isEmpty()) {
                log.info(
                    "Long-term replacement or no other permanent cluster available; removing " +
                        "plot from this observation and making the other plots in its cluster " +
                        "only available as temporary plots in future observations.")
                plantingSiteStore.makePlotUnavailable(monitoringPlotId)
              } else {
                swappedPlots
              }

          // If there were higher-numbered permanent clusters in the planting zone, at this point
          // we will usually have replaced the cluster containing the plot we're replacing with the
          // highest-numbered cluster, which we use because it's in a random place in the zone
          // (picking random permanent clusters is important to maintain statistical validity) and
          // is the least likely cluster to have been selected for an observation.
          //
          // But if this is the first observation, there's a good chance that there are still
          // subzones that haven't completed planting, and a chance that the replacement cluster
          // will have plots in one of the incomplete subzones. In that case, we want the same
          // behavior as during initial permanent cluster selection: the cluster is excluded from
          // the observation even if that means the observation has fewer than the configured number
          // of permanent clusters.
          val addedPlotsInPlantedSubzones =
              if (removalResult.addedMonitoringPlotIds.isNotEmpty()) {
                val subzones =
                    plantingZone.plantingSubzones.filter { subzone ->
                      subzone.monitoringPlots.any { it.id in removalResult.addedMonitoringPlotIds }
                    }
                if (subzones.all { it.plantingCompletedTime != null }) {
                  log.info(
                      "Replacement permanent cluster's plots are all in subzones that have " +
                          "completed planting; including the cluster in this observation.")
                  removalResult.addedMonitoringPlotIds
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
              observationId, removalResult.removedMonitoringPlotIds)
          removedPlotIds.addAll(removalResult.removedMonitoringPlotIds)

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

        val replacementPlotIds = plantingSubzone.chooseTemporaryPlots(observationPlotIds, 1)
        if (replacementPlotIds.isNotEmpty()) {
          log.info("Adding replacement plot ${replacementPlotIds.first()}")
          addedPlotIds.addAll(replacementPlotIds)
          observationStore.addPlotsToObservation(
              observationId, replacementPlotIds, isPermanent = false)
        } else {
          log.info("No other temporary plots available in subzone")
        }
      }

      eventPublisher.publishEvent(
          ObservationPlotReplacedEvent(duration, justification, observation, monitoringPlotId))

      ReplacementResult(addedPlotIds, removedPlotIds)
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
      terrawareServerConfig.observations.notifyOnFirstPlanting &&
          !observationStore.hasObservations(plantingSiteId) &&
          (plantingSiteStore.fetchOldestPlantingTime(plantingSiteId)?.let {
            elapsedWeeks(it, weeks)
          }
              ?: false)

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
}
