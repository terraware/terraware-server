package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FileNotFoundException
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPhotoPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.tables.daos.ObservationPhotosDao
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPhotosRow
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoPlotsException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.ObservationStartedEvent
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import java.io.InputStream
import javax.inject.Named
import org.locationtech.jts.geom.Point
import org.springframework.context.ApplicationEventPublisher

@Named
class ObservationService(
    private val eventPublisher: ApplicationEventPublisher,
    private val fileService: FileService,
    private val observationPhotosDao: ObservationPhotosDao,
    private val observationStore: ObservationStore,
    private val plantingSiteStore: PlantingSiteStore,
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
}
