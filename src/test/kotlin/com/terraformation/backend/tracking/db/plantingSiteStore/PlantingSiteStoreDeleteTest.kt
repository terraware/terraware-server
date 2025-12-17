package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_COORDINATES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreDeleteTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class DeletePlantingSite {
    @Test
    fun `deletes detailed map data and observations`() {
      every { user.canDeletePlantingSite(any()) } returns true

      val plantingSiteId = insertPlantingSite(x = 0)
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertNurseryWithdrawal()
      insertDelivery()
      insertPlanting()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot()
      insertObservedCoordinates()
      insertRecordedPlant()

      store.deletePlantingSite(plantingSiteId)

      assertTableEmpty(PLANTING_SITES)
      assertTableEmpty(STRATA)
      assertTableEmpty(SUBSTRATA)
      assertTableEmpty(MONITORING_PLOTS)
      assertTableEmpty(DELIVERIES)
      assertTableEmpty(PLANTINGS)
      assertTableEmpty(OBSERVATIONS)
      assertTableEmpty(OBSERVATION_PLOTS)
      assertTableEmpty(OBSERVED_PLOT_COORDINATES)
      assertTableEmpty(RECORDED_PLANTS)

      eventPublisher.assertEventPublished(PlantingSiteDeletionStartedEvent(plantingSiteId))
    }

    @Test
    fun `throws exception if no permission to delete planting site`() {
      every { user.canDeletePlantingSite(any()) } returns false

      val plantingSiteId = insertPlantingSite()

      assertThrows<AccessDeniedException> { store.deletePlantingSite(plantingSiteId) }
    }
  }
}
