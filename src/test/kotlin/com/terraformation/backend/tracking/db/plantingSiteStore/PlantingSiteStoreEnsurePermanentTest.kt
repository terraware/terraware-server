package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PlantingSiteStoreEnsurePermanentTest : PlantingSiteStoreTest() {
  @Nested
  inner class EnsurePermanentClustersExist {
    @Test
    fun `creates all clusters in empty planting site`() {
      val siteBoundary = Turtle(point(0)).makeMultiPolygon { square(101) }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = point(0))
      insertPlantingZone(boundary = siteBoundary, numPermanentClusters = 4)
      insertPlantingSubzone(boundary = siteBoundary)

      store.ensurePermanentClustersExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()

      assertEquals(16, plots.size, "Number of monitoring plots created")
      assertEquals(
          setOf(1, 2, 3, 4), plots.map { it.permanentCluster }.toSet(), "Permanent cluster numbers")
      assertEquals(1, plots.minOf { it.name!!.toInt() }, "Smallest plot number")
      assertEquals(16, plots.maxOf { it.name!!.toInt() }, "Largest plot number")
    }

    @Test
    fun `creates as many clusters as there is room for`() {
      val siteBoundary = Turtle(point(0)).makeMultiPolygon { rectangle(101, 51) }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = point(0))
      insertPlantingZone(boundary = siteBoundary, numPermanentClusters = 4)
      insertPlantingSubzone(boundary = siteBoundary)

      // Zone is configured for 4 clusters, but there's only room for 2.
      store.ensurePermanentClustersExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()

      assertEquals(8, plots.size, "Number of monitoring plots created")
      assertEquals(
          setOf(1, 2), plots.map { it.permanentCluster }.toSet(), "Permanent cluster numbers")
      assertEquals(1, plots.minOf { it.name!!.toInt() }, "Smallest plot number")
      assertEquals(8, plots.maxOf { it.name!!.toInt() }, "Largest plot number")
    }

    @Test
    fun `only creates nonexistent permanent clusters`() {
      val gridOrigin = point(0)
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { square(201) }
      val existingPlotBoundary = Turtle(gridOrigin).makePolygon { square(25) }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
      insertPlantingZone(boundary = siteBoundary, numPermanentClusters = 3)
      insertPlantingSubzone(boundary = siteBoundary)
      insertMonitoringPlot(
          boundary = existingPlotBoundary, permanentCluster = 2, permanentClusterSubplot = 1)

      store.ensurePermanentClustersExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()
      val plotsPerCluster = plots.groupBy { it.permanentCluster }.mapValues { it.value.size }

      assertEquals(9, plots.size, "Number of monitoring plots including existing one")
      assertEquals(
          mapOf(1 to 4, 2 to 1, 3 to 4), plotsPerCluster, "Number of plots in each cluster")
    }

    @Test
    fun `can use temporary plot from previous observation as part of cluster`() {
      val gridOrigin = point(0)
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { square(51) }

      // Temporary plot is in the southeast corner of the cluster.
      val existingPlotBoundary =
          Turtle(gridOrigin).makePolygon {
            east(25)
            square(25)
          }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
      insertPlantingZone(boundary = siteBoundary, numPermanentClusters = 1)
      insertPlantingSubzone(boundary = siteBoundary)
      val existingPlotId = insertMonitoringPlot(boundary = existingPlotBoundary)

      store.ensurePermanentClustersExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()
      val existingPlot = plots.first { it.id == existingPlotId }

      // Subplots are numbered counterclockwise starting from the southwest.
      val southeastSubplot = 2

      assertEquals(4, plots.size, "Number of monitoring plots including existing one")
      assertEquals(1, existingPlot.permanentCluster, "Permanent cluster of existing plot")
      assertEquals(
          southeastSubplot, existingPlot.permanentClusterSubplot, "Subplot number of existing plot")
    }

    @Test
    fun `does nothing if required clusters already exist`() {
      val gridOrigin = point(0)
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { square(201) }
      val plotBoundary = Turtle(gridOrigin).makePolygon { square(25) }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
      insertPlantingZone(boundary = siteBoundary, numPermanentClusters = 2)
      insertPlantingSubzone(boundary = siteBoundary)
      insertMonitoringPlot(
          boundary = plotBoundary, permanentCluster = 1, permanentClusterSubplot = 1)
      insertMonitoringPlot(
          boundary = plotBoundary, permanentCluster = 2, permanentClusterSubplot = 1)

      val before = monitoringPlotsDao.findAll().toSet()

      store.ensurePermanentClustersExist(plantingSiteId)

      val after = monitoringPlotsDao.findAll().toSet()

      assertEquals(before, after, "Should not have created or modified any plots")
    }

    @Test
    fun `uses plot number after existing highest number even if lower numbers are unused`() {
      val gridOrigin = point(0)
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { rectangle(101, 51) }
      val existingPlotBoundary = Turtle(gridOrigin).makePolygon { square(25) }

      val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)
      insertPlantingZone(boundary = siteBoundary, numPermanentClusters = 2)
      insertPlantingSubzone(boundary = siteBoundary)
      insertMonitoringPlot(boundary = existingPlotBoundary, name = "123", permanentCluster = 1)

      store.ensurePermanentClustersExist(plantingSiteId)

      val plots = monitoringPlotsDao.findAll()
      assertEquals(
          listOf(123, 124, 125, 126, 127),
          plots.map { it.name!!.toInt() }.sorted(),
          "Plot numbers including existing plot")
    }
  }
}
