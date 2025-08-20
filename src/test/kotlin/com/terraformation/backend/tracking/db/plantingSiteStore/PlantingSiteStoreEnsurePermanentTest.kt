package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.NumericIdentifierType
import com.terraformation.backend.db.tracking.tables.records.MonitoringPlotHistoriesRecord
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import com.terraformation.backend.util.Turtle
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PlantingSiteStoreEnsurePermanentTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class EnsurePermanentPlotsExist {
    @Test
    fun `creates all plots in empty planting site`() {
      val siteBoundary = Turtle(point(0)).makeMultiPolygon { square(101) }

      val plantingSiteId =
          insertPlantingSite(boundary = siteBoundary, gridOrigin = point(0), insertHistory = false)
      val plantingSiteHistoryId = insertPlantingSiteHistory()
      insertPlantingZone(boundary = siteBoundary, numPermanentPlots = 4)
      val plantingSubzoneId = insertPlantingSubzone(boundary = siteBoundary, insertHistory = false)
      val plantingSubzoneHistoryId = insertPlantingSubzoneHistory()

      store.ensurePermanentPlotsExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()

      assertEquals(4, plots.size, "Number of monitoring plots created")
      assertEquals(setOf(1, 2, 3, 4), plots.map { it.permanentIndex }.toSet(), "Permanent indexes")
      assertEquals(1L, plots.minOf { it.plotNumber!! }, "Smallest plot number")
      assertEquals(4L, plots.maxOf { it.plotNumber!! }, "Largest plot number")

      assertTableEquals(
          plots.map { plot ->
            MonitoringPlotHistoriesRecord(
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                monitoringPlotId = plot.id,
                plantingSiteHistoryId = plantingSiteHistoryId,
                plantingSiteId = plantingSiteId,
                plantingSubzoneHistoryId = plantingSubzoneHistoryId,
                plantingSubzoneId = plantingSubzoneId,
            )
          }
      )
    }

    @Test
    fun `creates as many plots as there is room for`() {
      val siteBoundary =
          Turtle(point(0)).makeMultiPolygon {
            rectangle(MONITORING_PLOT_SIZE * 2 + 1, MONITORING_PLOT_SIZE + 1)
          }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = point(0))
      insertPlantingZone(boundary = siteBoundary, numPermanentPlots = 4)
      insertPlantingSubzone(boundary = siteBoundary)

      // Zone is configured for 4 permanent plots, but there's only room for 2.
      store.ensurePermanentPlotsExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()

      assertEquals(2, plots.size, "Number of monitoring plots created")
      assertEquals(setOf(1, 2), plots.map { it.permanentIndex }.toSet(), "Permanent indexes")
      assertEquals(1L, plots.minOf { it.plotNumber!! }, "Smallest plot number")
      assertEquals(2L, plots.maxOf { it.plotNumber!! }, "Largest plot number")
    }

    @Test
    fun `only creates nonexistent permanent plots`() {
      val gridOrigin = point(0)
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { square(201) }
      val existingPlotBoundary = Turtle(gridOrigin).makePolygon { square(25) }

      val plantingSiteId =
          insertPlantingSite(
              boundary = siteBoundary,
              gridOrigin = gridOrigin,
              insertHistory = false,
          )
      val plantingSiteHistoryId = insertPlantingSiteHistory()
      insertPlantingZone(boundary = siteBoundary, numPermanentPlots = 3)
      val plantingSubzoneId = insertPlantingSubzone(boundary = siteBoundary, insertHistory = false)
      val plantingSubzoneHistoryId = insertPlantingSubzoneHistory()
      insertMonitoringPlot(
          boundary = existingPlotBoundary,
          permanentIndex = 2,
          insertHistory = false,
      )

      store.ensurePermanentPlotsExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()

      assertEquals(3, plots.size, "Number of monitoring plots including existing one")
      assertEquals(setOf(1, 2, 3), plots.map { it.permanentIndex }.toSet(), "Permanent indexes")

      assertTableEquals(
          plots
              .filter { it.permanentIndex != 2 }
              .map { plot ->
                MonitoringPlotHistoriesRecord(
                    createdBy = user.userId,
                    createdTime = Instant.EPOCH,
                    monitoringPlotId = plot.id,
                    plantingSiteHistoryId = plantingSiteHistoryId,
                    plantingSiteId = plantingSiteId,
                    plantingSubzoneHistoryId = plantingSubzoneHistoryId,
                    plantingSubzoneId = plantingSubzoneId,
                )
              }
      )
    }

    @Test
    fun `can use temporary plot from previous observation as permanent plot`() {
      val gridOrigin = point(0)
      val siteBoundary =
          Turtle(gridOrigin).makeMultiPolygon {
            rectangle(MONITORING_PLOT_SIZE * 2 + 1, MONITORING_PLOT_SIZE + 1)
          }

      // Temporary plot is in the east half of the site.
      val existingPlotBoundary =
          Turtle(gridOrigin).makePolygon {
            east(MONITORING_PLOT_SIZE)
            square(MONITORING_PLOT_SIZE)
          }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
      insertPlantingZone(boundary = siteBoundary, numPermanentPlots = 2)
      insertPlantingSubzone(boundary = siteBoundary)
      val existingPlotId = insertMonitoringPlot(boundary = existingPlotBoundary)

      store.ensurePermanentPlotsExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()
      val existingPlot = plots.first { it.id == existingPlotId }

      assertEquals(2, plots.size, "Number of monitoring plots including existing one")
      assertNotNull(existingPlot.permanentIndex, "Permanent index of existing plot")
    }

    @Test
    fun `does nothing if required permanent plots already exist`() {
      val gridOrigin = point(0)
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { square(201) }
      val plotBoundary = Turtle(gridOrigin).makePolygon { square(25) }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
      insertPlantingZone(boundary = siteBoundary, numPermanentPlots = 2)
      insertPlantingSubzone(boundary = siteBoundary)
      insertMonitoringPlot(boundary = plotBoundary, permanentIndex = 1)
      insertMonitoringPlot(boundary = plotBoundary, permanentIndex = 2)

      val before = monitoringPlotsDao.findAll().toSet()

      store.ensurePermanentPlotsExist(plantingSiteId)

      val after = monitoringPlotsDao.findAll().toSet()

      assertEquals(before, after, "Should not have created or modified any plots")
    }

    @Test
    fun `uses next plot number for organization`() {
      val gridOrigin = point(0)
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { rectangle(101, 51) }
      val existingPlotBoundary = Turtle(gridOrigin).makePolygon { square(25) }

      repeat(5) {
        identifierGenerator.generateNumericIdentifier(
            organizationId,
            NumericIdentifierType.PlotNumber,
        )
      }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
      insertPlantingZone(boundary = siteBoundary, numPermanentPlots = 2)
      insertPlantingSubzone(boundary = siteBoundary)
      insertMonitoringPlot(boundary = existingPlotBoundary, plotNumber = 1, permanentIndex = 1)

      store.ensurePermanentPlotsExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()
      assertEquals(
          setOf(1L, 6L),
          plots.map { it.plotNumber }.toSet(),
          "Plot numbers including existing plot",
      )
    }
  }
}
