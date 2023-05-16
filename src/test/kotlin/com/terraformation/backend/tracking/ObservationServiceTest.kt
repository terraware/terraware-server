package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.ObservationAlreadyStartedException
import com.terraformation.backend.tracking.db.ObservationHasNoPlotsException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val clock = TestClock()
  private val plantingSiteStore: PlantingSiteStore by lazy {
    PlantingSiteStore(clock, dslContext, plantingSitesDao, plantingZonesDao)
  }
  private val service: ObservationService by lazy {
    ObservationService(
        ObservationStore(clock, dslContext, observationsDao, observationPlotsDao),
        plantingSiteStore)
  }

  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    plantingSiteId = insertPlantingSite()

    every { user.canManageObservation(any()) } returns true
    every { user.canReadObservation(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canUpdateObservation(any()) } returns true
  }

  @Nested
  inner class StartObservation {
    @Test
    fun `assigns correct plots to planting zones`() {
      // Given a planting site with this structure:
      //
      // Zone 1 (2 permanent, 3 temporary)
      //   Subzone 1 (has plants)
      //     Plot A - permanent cluster 1
      //     Plot B - permanent cluster 1
      //     Plot C - permanent cluster 1
      //     Plot D - permanent cluster 1
      //     Plot E - permanent cluster 3
      //     Plot F - permanent cluster 3
      //     Plot G - permanent cluster 3
      //     Plot H - permanent cluster 3
      //     Plot I
      //     Plot J
      //   Subzone 2 (no plants)
      //     Plot K - permanent cluster 2
      //     Plot L - permanent cluster 2
      //     Plot M - permanent cluster 2
      //     Plot N - permanent cluster 2
      //     Plot O
      //     Plot P
      // Zone 2 (2 permanent, 2 temporary)
      //   Subzone 3 (no plants)
      //     Plot Q - permanent 1
      //
      // We should get:
      // - Plots A-D because they are the first permanent cluster in the zone and they lie in a
      //   planted subzone. They should be selected as permanent plots.
      // - Exactly one of plots E-J as a temporary plot. The zone is configured for 3 temporary
      //   plots. 2 of them are spread evenly across the 2 subzones, and the remaining one is placed
      //   in the subzone with the fewest permanent plots, which is subzone 2, but subzone 2's plots
      //   are excluded because it has no plants.
      // - Nothing from zone 2 because it has no plants.

      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      insertWithdrawal()
      insertDelivery()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 3)
      insertPlantingSubzone()
      insertPlanting()
      val zone1PermanentCluster1 =
          setOf(
              insertMonitoringPlot(permanentCluster = 1),
              insertMonitoringPlot(permanentCluster = 1),
              insertMonitoringPlot(permanentCluster = 1),
              insertMonitoringPlot(permanentCluster = 1),
          )
      val zone1PermanentCluster3 =
          setOf(
              insertMonitoringPlot(permanentCluster = 3),
              insertMonitoringPlot(permanentCluster = 3),
              insertMonitoringPlot(permanentCluster = 3),
              insertMonitoringPlot(permanentCluster = 3),
          )
      val zone1NonPermanent =
          setOf(
              insertMonitoringPlot(),
              insertMonitoringPlot(),
          )

      insertPlantingSubzone()
      insertMonitoringPlot(permanentCluster = 2)
      insertMonitoringPlot()
      insertMonitoringPlot()

      insertPlantingZone(numPermanentClusters = 2, numTemporaryPlots = 2)
      insertPlantingSubzone()
      insertMonitoringPlot(permanentCluster = 1)

      val observationId = insertObservation(state = ObservationState.Upcoming)

      service.startObservation(observationId)

      val observationPlots = observationPlotsDao.findAll()

      assertEquals(5, observationPlots.size, "Should have selected 2 plots")
      assertEquals(
          zone1PermanentCluster1,
          observationPlots.filter { it.isPermanent!! }.map { it.monitoringPlotId }.toSet(),
          "Permanent plot IDs")
      assertEquals(
          1,
          observationPlots
              .filter { !it.isPermanent!! }
              .map { it.monitoringPlotId }
              .count { it in (zone1NonPermanent + zone1PermanentCluster3) },
          "Should have selected one temporary plot")
      assertEquals(
          ObservationState.InProgress,
          observationsDao.fetchOneById(observationId)!!.stateId,
          "Observation state")
    }

    @Test
    fun `throws exception if observation already started`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val observationId = insertObservation(state = ObservationState.InProgress)

      assertThrows<ObservationAlreadyStartedException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if observation already has plots assigned`() {
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      val observationId = insertObservation(state = ObservationState.Upcoming)

      insertObservationPlot()

      assertThrows<ObservationAlreadyStartedException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if planting site has no planted subzones`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      assertThrows<ObservationHasNoPlotsException> { service.startObservation(observationId) }
    }

    @Test
    fun `throws exception if no permission to manage observation`() {
      val observationId = insertObservation(state = ObservationState.Upcoming)

      every { user.canManageObservation(observationId) } returns false

      assertThrows<AccessDeniedException> { service.startObservation(observationId) }
    }
  }
}
