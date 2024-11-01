package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotOverlapsRow
import com.terraformation.backend.point
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.equalsOrBothNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.locationtech.jts.geom.Point

internal class PlantingSiteStoreConvertClustersTest : PlantingSiteStoreTest() {
  @Test
  fun `converts 4-plot 25x25m clusters to 1-plot 30x30m`() {
    val gridOrigin = point(0)
    val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { rectangle(151, 151) }
    val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)

    insertPlantingZone(boundary = siteBoundary, numPermanentClusters = 2, numTemporaryPlots = 1)
    insertPlantingSubzone(boundary = siteBoundary)
    insertPermanentPlots(
        gridOrigin,
        listOf(
            // ((cluster, subplot), (east, north))
            (1 to 1) to (0 to 0),
            (1 to 2) to (25 to 0),
            (1 to 3) to (25 to 25),
            (1 to 4) to (0 to 25),
            (2 to 1) to (50 to 50),
            (2 to 2) to (75 to 50),
            (2 to 3) to (75 to 75),
            (2 to 4) to (50 to 75),
            (3 to 1) to (100 to 100),
            (3 to 2) to (125 to 100),
            (3 to 3) to (125 to 125),
            (3 to 4) to (100 to 125),
        ))

    val oldPlots = monitoringPlotsDao.findAll().sortedBy { it.id }

    store.convert25MeterClusters(plantingSiteId)

    val newPlots = monitoringPlotsDao.findAll().sortedBy { it.id }

    assertEquals(3, newPlots.size - oldPlots.size, "Number of new plots created")
    assertEquals(
        oldPlots.map { it.copy(permanentCluster = null, permanentClusterSubplot = null) },
        newPlots.filter { it.sizeMeters == 25 },
        "Should have cleared permanent cluster numbers of 25m plots")

    // Cluster 1's southwest corner is at the grid origin, which is a valid corner for both 25m
    // and 30m plots, so we expect the new plot to have the same southwest corner.
    val expectedCluster1Boundary = Turtle(gridOrigin).makePolygon { square(30) }

    // Cluster 2's southwest corner is at x=50, y=50, which isn't a point on the 30m grid.
    // The 30m grid square that falls completely within cluster 2's boundaries has its southwest
    // corner at x=60, y=60, with its northeast corner at x=90, y=90.
    val expectedCluster2Boundary =
        Turtle(gridOrigin).makePolygon {
          north(60)
          east(60)
          square(30)
        }

    // Cluster 3's southwest corner is at x=100, y=100, which also isn't a point on the 30m grid.
    // The 30m grid square that falls within its boundaries goes from x=120, y=120 to x=150,
    // y=150, that is, it has the same northeast corner as the original cluster.
    val expectedCluster3Boundary =
        Turtle(gridOrigin).makePolygon {
          north(120)
          east(120)
          square(30)
        }

    val newCluster1Plot =
        newPlots.singleOrNull { it.permanentCluster == 1 }
            ?: fail("No new plot created for cluster 1")
    val newCluster2Plot =
        newPlots.singleOrNull { it.permanentCluster == 2 }
            ?: fail("No new plot created for cluster 2")
    val newCluster3Plot =
        newPlots.singleOrNull { it.permanentCluster == 3 }
            ?: fail("No new plot created for cluster 3")

    if (!expectedCluster1Boundary.equalsOrBothNull(newCluster1Plot.boundary)) {
      assertEquals(
          expectedCluster1Boundary,
          newCluster1Plot.boundary,
          "Replacement for cluster 1 should have been at grid origin")
    }

    if (!expectedCluster2Boundary.equalsOrBothNull(newCluster2Plot.boundary)) {
      assertEquals(
          expectedCluster2Boundary,
          newCluster2Plot.boundary,
          "Replacement for cluster 2 should have been 60x60m from origin")
    }

    if (!expectedCluster3Boundary.equalsOrBothNull(newCluster3Plot.boundary)) {
      assertEquals(
          expectedCluster3Boundary,
          newCluster3Plot.boundary,
          "Replacement for cluster 3 should have been 120x120m from origin")
    }

    assertEquals(
        listOf(
            MonitoringPlotOverlapsRow(newCluster1Plot.id, oldPlots[0].id),
            MonitoringPlotOverlapsRow(newCluster1Plot.id, oldPlots[1].id),
            MonitoringPlotOverlapsRow(newCluster1Plot.id, oldPlots[2].id),
            MonitoringPlotOverlapsRow(newCluster1Plot.id, oldPlots[3].id),
            MonitoringPlotOverlapsRow(newCluster2Plot.id, oldPlots[4].id),
            MonitoringPlotOverlapsRow(newCluster2Plot.id, oldPlots[5].id),
            MonitoringPlotOverlapsRow(newCluster2Plot.id, oldPlots[6].id),
            MonitoringPlotOverlapsRow(newCluster2Plot.id, oldPlots[7].id),
            MonitoringPlotOverlapsRow(newCluster3Plot.id, oldPlots[8].id),
            MonitoringPlotOverlapsRow(newCluster3Plot.id, oldPlots[9].id),
            MonitoringPlotOverlapsRow(newCluster3Plot.id, oldPlots[10].id),
            MonitoringPlotOverlapsRow(newCluster3Plot.id, oldPlots[11].id),
        ),
        monitoringPlotOverlapsDao.findAll().sortedBy { it.overlapsPlotId },
        "Plot overlaps")
  }

  @Test
  fun `converting an already-converted site does not change anything`() {
    val gridOrigin = point(0)
    val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { rectangle(151, 151) }
    val plantingSiteId = insertPlantingSite(boundary = siteBoundary, gridOrigin = gridOrigin)

    insertPlantingZone(boundary = siteBoundary, numPermanentClusters = 2, numTemporaryPlots = 1)
    insertPlantingSubzone(boundary = siteBoundary)
    insertPermanentPlots(
        gridOrigin,
        listOf(
            // ((cluster, subplot), (east, north))
            (1 to 1) to (0 to 0),
            (1 to 2) to (25 to 0),
            (1 to 3) to (25 to 25),
            (1 to 4) to (0 to 25),
        ))

    store.convert25MeterClusters(plantingSiteId)

    val convertedPlots = monitoringPlotsDao.findAll().sortedBy { it.id }
    val convertedOverlaps = monitoringPlotOverlapsDao.findAll().sortedBy { it.monitoringPlotId }

    store.convert25MeterClusters(plantingSiteId)

    val reconvertedPlots = monitoringPlotsDao.findAll().sortedBy { it.id }
    val reconvertedOverlaps = monitoringPlotOverlapsDao.findAll().sortedBy { it.monitoringPlotId }

    assertEquals(convertedPlots, reconvertedPlots, "Monitoring plots after second conversion")
    assertEquals(convertedOverlaps, reconvertedOverlaps, "Overlaps after second conversion")
  }

  /**
   * Inserts a set of permanent monitoring plots.
   *
   * @param plots List of ((cluster, subplot), (eastMeters, northMeters)) plot parameters.
   */
  private fun insertPermanentPlots(
      gridOrigin: Point,
      plots: List<Pair<Pair<Int, Int>, Pair<Int, Int>>>
  ) {
    plots.forEachIndexed { index, (clusterAndSubplot, eastAndNorth) ->
      val (cluster, subplot) = clusterAndSubplot
      val (eastMeters, northMeters) = eastAndNorth
      insertMonitoringPlot(
          name = "$index",
          permanentCluster = cluster,
          permanentClusterSubplot = subplot,
          sizeMeters = 25,
          boundary =
              Turtle(gridOrigin).makePolygon {
                north(northMeters)
                east(eastMeters)
                square(25)
              })
    }
  }
}
