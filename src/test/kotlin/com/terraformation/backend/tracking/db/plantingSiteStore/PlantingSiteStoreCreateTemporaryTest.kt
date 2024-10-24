package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.util.Turtle
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreCreateTemporaryTest : PlantingSiteStoreTest() {
  @Nested
  inner class CreateTemporaryPlot {
    @Test
    fun `creates new plot with correct details`() {
      val siteBoundary = Turtle(point(0)).makeMultiPolygon { square(51) }
      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = point(0))
      val plantingZoneId = insertPlantingZone(boundary = siteBoundary)
      val plantingSubzoneId = insertPlantingSubzone(boundary = siteBoundary)

      insertMonitoringPlot(
          boundary =
              Turtle(point(0)).makePolygon {
                north(25)
                square(25)
              },
          name = "17")

      val plotBoundary = Turtle(point(0)).makePolygon { square(25) }
      val newPlotId = store.createTemporaryPlot(plantingSiteId, plantingZoneId, plotBoundary)

      val expected =
          MonitoringPlotsRow(
              createdBy = user.userId,
              createdTime = clock.instant,
              fullName = "Z1-1-18",
              id = newPlotId,
              isAvailable = true,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "18",
              plantingSubzoneId = plantingSubzoneId,
              sizeMeters = MONITORING_PLOT_SIZE_INT,
          )

      val actual = monitoringPlotsDao.fetchOneById(newPlotId)!!

      assertEquals(expected, actual.copy(boundary = null))

      // PostGIS geometry representation isn't identical to GeoTools in-memory representation; do a
      // fuzzy comparison with a very small tolerance.
      if (!plotBoundary.equalsExact(actual.boundary!!, 0.00000000001)) {
        assertEquals(plotBoundary, actual.boundary!!, "Plot boundary")
      }
    }

    @Test
    fun `returns existing ID if plot boundary already exists`() {
      val siteBoundary = Turtle(point(0)).makeMultiPolygon { square(51) }
      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = point(0))
      val plantingZoneId = insertPlantingZone(boundary = siteBoundary)
      insertPlantingSubzone(boundary = siteBoundary)

      val existingPlotBoundary = Turtle(point(0)).makePolygon { square(25) }
      val existingPlotId = insertMonitoringPlot(boundary = existingPlotBoundary)

      val newPlotBoundary =
          Turtle(point(0)).makePolygon {
            east(1)
            north(1)
            square(25)
          }
      val newPlotId = store.createTemporaryPlot(plantingSiteId, plantingZoneId, newPlotBoundary)

      assertEquals(existingPlotId, newPlotId, "Should have returned existing plot ID")
    }

    @Test
    fun `throws exception if no permission to update planting site`() {
      every { user.canUpdatePlantingSite(any()) } returns false

      val siteBoundary = Turtle(point(0)).makeMultiPolygon { square(101) }
      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = point(0))
      val plantingZoneId = insertPlantingZone(boundary = siteBoundary)
      insertPlantingSubzone(boundary = siteBoundary)

      assertThrows<AccessDeniedException> {
        store.createTemporaryPlot(
            plantingSiteId, plantingZoneId, Turtle(point(0)).makePolygon { square(25) })
      }
    }
  }
}
