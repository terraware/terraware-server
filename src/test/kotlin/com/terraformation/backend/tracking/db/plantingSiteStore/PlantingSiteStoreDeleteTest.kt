package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import io.mockk.every
import java.time.Instant
import org.jooq.DAO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreDeleteTest : PlantingSiteStoreTest() {
  @Nested
  inner class DeletePlantingSite {
    @Test
    fun `deletes detailed map data and observations`() {
      every { user.canDeletePlantingSite(any()) } returns true

      val plantingSiteId = insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertWithdrawal()
      insertDelivery()
      insertPlanting()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot()
      insertObservedCoordinates()
      insertRecordedPlant()

      store.deletePlantingSite(plantingSiteId)

      fun assertAllDeleted(dao: DAO<*, *, *>) {
        assertEquals(emptyList<Any>(), dao.findAll())
      }

      assertAllDeleted(plantingSitesDao)
      assertAllDeleted(plantingZonesDao)
      assertAllDeleted(plantingSubzonesDao)
      assertAllDeleted(monitoringPlotsDao)
      assertAllDeleted(deliveriesDao)
      assertAllDeleted(plantingsDao)
      assertAllDeleted(observationsDao)
      assertAllDeleted(observationPlotsDao)
      assertAllDeleted(observedPlotCoordinatesDao)
      assertAllDeleted(recordedPlantsDao)

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
