package com.terraformation.backend.tracking

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.log.withMDC
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoPlotsException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import javax.inject.Named

@Named
class ObservationService(
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
          log.warn("BUG! Observation has plots but state is still ${observation.state}")
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
      }
    }
  }
}
