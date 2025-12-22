package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.ObservationPlotCounts
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationStoreCountPlotsTest : BaseObservationStoreTest() {
  @BeforeEach
  fun insertDetailedSite() {
    insertStratum()
    insertSubstratum()
  }

  @Nested
  inner class ByPlantingSite {
    @Test
    fun `returns correct counts without counting ad-hoc plots`() {
      val plotId1 = insertMonitoringPlot()
      val plotId2 = insertMonitoringPlot()
      val plotId3 = insertMonitoringPlot()
      val plotId4 = insertMonitoringPlot()
      val plotId5 = insertMonitoringPlot()
      val plotId6 = insertMonitoringPlot()
      val adHocPlotId = insertMonitoringPlot(isAdHoc = true)

      val observationId1 = insertObservation()
      insertObservationPlot(monitoringPlotId = plotId1, claimedBy = user.userId)
      insertObservationPlot(monitoringPlotId = plotId2, claimedBy = user.userId)
      insertObservationPlot(monitoringPlotId = plotId3, completedBy = user.userId)
      insertObservationPlot(monitoringPlotId = plotId4)
      insertObservationPlot(monitoringPlotId = plotId5)

      val observationId2 = insertObservation()
      insertObservationPlot(monitoringPlotId = plotId1)
      insertObservationPlot(monitoringPlotId = plotId2)
      insertObservationPlot(monitoringPlotId = plotId6)

      val adHocObservationId = insertObservation(isAdHoc = true)
      insertObservationPlot(observationId = adHocObservationId, monitoringPlotId = adHocPlotId)

      // Make sure we're actually filtering by planting site
      insertPlantingSite()
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertObservation()
      insertObservationPlot()

      val expected =
          mapOf(
              observationId1 to
                  ObservationPlotCounts(
                      totalIncomplete = 4,
                      totalPlots = 5,
                      totalUnclaimed = 2,
                  ),
              observationId2 to
                  ObservationPlotCounts(
                      totalIncomplete = 3,
                      totalPlots = 3,
                      totalUnclaimed = 3,
                  ),
          )

      val actual = store.countPlots(plantingSiteId)

      assertEquals(expected, actual, "counting non-ad-hoc")

      assertEquals(
          mapOf(
              adHocObservationId to
                  ObservationPlotCounts(
                      totalIncomplete = 1,
                      totalPlots = 1,
                      totalUnclaimed = 1,
                  )
          ),
          store.countPlots(plantingSiteId, true),
          "counting ad-hoc",
      )
    }

    @Test
    fun `returns empty map if planting site has no observations`() {
      insertPlantingSite()

      assertEquals(
          emptyMap<ObservationId, ObservationPlotCounts>(),
          store.countPlots(inserted.plantingSiteId),
      )
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      every { user.canReadPlantingSite(any()) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.countPlots(insertPlantingSite()) }
    }
  }

  @Nested
  inner class ByObservation {
    @Test
    fun `returns correct counts`() {
      val plotId1 = insertMonitoringPlot()
      val plotId2 = insertMonitoringPlot()
      val plotId3 = insertMonitoringPlot()
      val plotId4 = insertMonitoringPlot()
      val plotId5 = insertMonitoringPlot()

      val observationId = insertObservation()
      insertObservationPlot(monitoringPlotId = plotId1, claimedBy = user.userId)
      insertObservationPlot(monitoringPlotId = plotId2, claimedBy = user.userId)
      insertObservationPlot(monitoringPlotId = plotId3, completedBy = user.userId)
      insertObservationPlot(monitoringPlotId = plotId4)
      insertObservationPlot(monitoringPlotId = plotId5)

      insertObservation()
      insertObservationPlot(monitoringPlotId = plotId1)

      val expected =
          ObservationPlotCounts(
              totalIncomplete = 4,
              totalPlots = 5,
              totalUnclaimed = 2,
          )

      val actual = store.countPlots(observationId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns plot counts of zero if no plots have been assigned to observation`() {
      val observationId = insertObservation()

      val expected = ObservationPlotCounts(0, 0, 0)

      val actual = store.countPlots(observationId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if observation does not exist`() {
      assertThrows<ObservationNotFoundException> { store.countPlots(ObservationId(-1)) }
    }

    @Test
    fun `throws exception if no permission to read observation`() {
      every { user.canReadObservation(any()) } returns false

      assertThrows<ObservationNotFoundException> { store.countPlots(insertObservation()) }
    }
  }

  @Nested
  inner class ByOrganization {
    @BeforeEach
    fun setUp() {
      every { user.canReadOrganization(any()) } returns true
    }

    @Test
    fun `returns correct counts`() {
      // Plot not included in an observation
      insertMonitoringPlot()

      val observationId1 = insertObservation()
      insertObservationPlot(monitoringPlotId = insertMonitoringPlot(), claimedBy = user.userId)
      insertObservationPlot(monitoringPlotId = insertMonitoringPlot(), claimedBy = user.userId)
      insertObservationPlot(monitoringPlotId = insertMonitoringPlot(), completedBy = user.userId)
      insertObservationPlot(monitoringPlotId = insertMonitoringPlot())
      insertObservationPlot(monitoringPlotId = insertMonitoringPlot())

      insertPlantingSite()
      insertStratum()
      insertSubstratum()

      val observationId2 = insertObservation()
      insertObservationPlot(monitoringPlotId = insertMonitoringPlot(), claimedBy = user.userId)
      insertObservationPlot(monitoringPlotId = insertMonitoringPlot())

      // Make sure we're actually filtering by planting site
      insertOrganization()
      insertPlantingSite()
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertObservation()
      insertObservationPlot()

      val expected =
          mapOf(
              observationId1 to
                  ObservationPlotCounts(
                      totalIncomplete = 4,
                      totalPlots = 5,
                      totalUnclaimed = 2,
                  ),
              observationId2 to
                  ObservationPlotCounts(
                      totalIncomplete = 2,
                      totalPlots = 2,
                      totalUnclaimed = 1,
                  ),
          )

      val actual = store.countPlots(organizationId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns empty map if organization has no observations`() {
      insertPlantingSite()

      assertEquals(
          emptyMap<ObservationId, ObservationPlotCounts>(),
          store.countPlots(organizationId),
      )
    }

    @Test
    fun `throws exception if no permission to read organization`() {
      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<OrganizationNotFoundException> { store.countPlots(organizationId) }
    }
  }
}
