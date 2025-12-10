package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.NumericIdentifierType
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.db.tracking.tables.records.MonitoringPlotHistoriesRecord
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.util.Turtle
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreCreateTemporaryTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class CreateTemporaryPlot {
    @Test
    fun `creates new plot with correct details`() {
      val siteBoundary = Turtle(point(0)).makeMultiPolygon { square(51) }
      val plantingSiteId =
          insertPlantingSite(boundary = siteBoundary, gridOrigin = point(0), insertHistory = false)
      val plantingSiteHistoryId = insertPlantingSiteHistory()
      val plantingZoneId = insertPlantingZone(boundary = siteBoundary)
      val plantingSubzoneId = insertPlantingSubzone(boundary = siteBoundary, insertHistory = false)
      val plantingSubzoneHistoryId = insertPlantingSubzoneHistory()

      identifierGenerator.generateNumericIdentifier(
          organizationId,
          NumericIdentifierType.PlotNumber,
      )

      val plotBoundary = Turtle(point(0)).makePolygon { square(25) }
      val newPlotId = store.createTemporaryPlot(plantingSiteId, plantingZoneId, plotBoundary)

      val expected =
          MonitoringPlotsRow(
              createdBy = user.userId,
              createdTime = clock.instant,
              id = newPlotId,
              isAdHoc = false,
              isAvailable = true,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              organizationId = organizationId,
              plantingSiteId = plantingSiteId,
              substratumId = plantingSubzoneId,
              plotNumber = 2,
              sizeMeters = MONITORING_PLOT_SIZE_INT,
          )

      val actual = monitoringPlotsDao.fetchOneById(newPlotId)!!

      assertEquals(expected, actual.copy(boundary = null))
      assertGeometryEquals(plotBoundary, actual.boundary, "Plot boundary")

      assertTableEquals(
          listOf(
              MonitoringPlotHistoriesRecord(
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  monitoringPlotId = newPlotId,
                  plantingSiteHistoryId = plantingSiteHistoryId,
                  plantingSiteId = plantingSiteId,
                  substratumHistoryId = plantingSubzoneHistoryId,
                  substratumId = plantingSubzoneId,
              )
          )
      )
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
            plantingSiteId,
            plantingZoneId,
            Turtle(point(0)).makePolygon { square(25) },
        )
      }
    }
  }
}
