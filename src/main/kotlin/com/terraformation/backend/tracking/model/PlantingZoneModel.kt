package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

data class PlantingZoneModel(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val errorMargin: BigDecimal,
    val extraPermanentClusters: Int,
    val id: PlantingZoneId,
    val name: String,
    val numPermanentClusters: Int,
    val numTemporaryPlots: Int,
    val plantingSubzones: List<PlantingSubzoneModel>,
    val studentsT: BigDecimal,
    val targetPlantingDensity: BigDecimal,
    val variance: BigDecimal,
) {
  /**
   * Chooses a set of plots to act as permanent monitoring plots. The number of plots is determined
   * by [numPermanentClusters] (where each cluster has 4 plots).
   *
   * Only clusters whose plots are all in planted subzones are returned, meaning there may be fewer
   * plots than configured, or none at all.
   */
  fun choosePermanentPlots(plantedSubzoneIds: Set<PlantingSubzoneId>): Set<MonitoringPlotId> {
    if (plantingSubzones.isEmpty()) {
      throw IllegalArgumentException("No subzones found for planting zone $id (wrong fetch depth?)")
    }

    val plantedSubzones = plantingSubzones.filter { it.id in plantedSubzoneIds }
    val plotsInPlantedSubzones =
        plantedSubzones.flatMap { subzone ->
          subzone.monitoringPlots.filter { plot ->
            plot.permanentCluster != null && plot.permanentCluster <= numPermanentClusters
          }
        }
    val clustersWithFourQualifiedPlots =
        plotsInPlantedSubzones.groupBy { it.permanentCluster }.values.filter { it.size == 4 }
    return clustersWithFourQualifiedPlots.flatMap { cluster -> cluster.map { it.id } }.toSet()
  }

  /**
   * Chooses a set of plots to act as temporary monitoring plots. The number of plots is determined
   * by [numTemporaryPlots].
   *
   * This follows a few rules:
   * - Plots that are already selected as permanent plots aren't eligible.
   * - Plots that span subzone boundaries aren't eligible.
   * - Plots must be spread across subzones as evenly as possible: the number of temporary plots
   *   can't vary by more than 1 between subzones.
   * - If plots can't be exactly evenly spread across subzones (that is, [numTemporaryPlots] is not
   *   a multiple of the number of subzones) the remaining ones must be placed in the subzones that
   *   have the fewest number of permanent plots. If multiple subzones have the same number of
   *   permanent plots (including 0 of them), temporary plots should be placed in subzones that have
   *   been planted before being "placed" in ones that haven't (but see the next point).
   * - Only subzones that have been planted should have temporary plots. However, they should be
   *   excluded only after all the preceding rules have been followed. That is, we want to choose
   *   plots based on the rules above, and then filter out plots in unplanted subzones, as opposed
   *   to only spreading plots across planted subzones. Otherwise, for a new project with a small
   *   number of planted subzones, we would end up piling the entire planting zone's worth of
   *   temporary plots into a handful of subzones.
   *
   * @throws IllegalArgumentException The number of temporary plots hasn't been configured or the
   *   planting zone has no subzones.
   * @throws PlantingSubzoneFullException There weren't enough eligible plots available in a subzone
   *   to choose the required number.
   */
  fun chooseTemporaryPlots(
      permanentPlotIds: Set<MonitoringPlotId>,
      plantedSubzoneIds: Set<PlantingSubzoneId>,
  ): Collection<MonitoringPlotId> {
    if (plantingSubzones.isEmpty()) {
      throw IllegalArgumentException("No subzones found for planting zone $id (wrong fetch depth?)")
    }

    // We will assign as many plots as possible evenly across all subzones.
    val numEvenlySpreadPlotsPerSubzone = numTemporaryPlots / plantingSubzones.size
    val numExcessPlots = numTemporaryPlots.rem(plantingSubzones.size)

    // Any plots that can't be spread evenly will be placed in the subzones with the smallest
    // number of permanent plots, with priority given to subzones that have been planted.
    //
    // If we sort the subzones by permanent plot count (always a multiple of 4) plus 1 if the
    // subzone has no plants, this means we can assign one extra plot each to the first N subzones
    // on that sorted list where N is the number of excess plots.
    return plantingSubzones
        .sortedBy { subzone ->
          val numPermanentPlots = subzone.monitoringPlots.count { it.id in permanentPlotIds }
          if (subzone.id in plantedSubzoneIds) numPermanentPlots else numPermanentPlots + 1
        }
        .flatMapIndexed { index, subzone ->
          if (subzone.id in plantedSubzoneIds) {
            val numPlots =
                if (index < numExcessPlots) {
                  numEvenlySpreadPlotsPerSubzone + 1
                } else {
                  numEvenlySpreadPlotsPerSubzone
                }

            val selectedPlots = subzone.chooseTemporaryPlots(permanentPlotIds, numPlots)

            if (selectedPlots.size < numPlots) {
              throw PlantingSubzoneFullException(subzone.id, numPlots, selectedPlots.size)
            }

            selectedPlots
          } else {
            // This subzone has no plants, so it gets no temporary plots.
            emptyList()
          }
        }
  }

  /**
   * Returns the planting subzone that contains a monitoring plot, or null if the plot isn't in any
   * of the subzones.
   */
  fun findSubzoneWithMonitoringPlot(monitoringPlotId: MonitoringPlotId): PlantingSubzoneModel? {
    return plantingSubzones.firstOrNull { subzone ->
      subzone.monitoringPlots.any { it.id == monitoringPlotId }
    }
  }

  /** Returns true if the zone contains a permanent cluster with the supplied cluster number. */
  fun permanentClusterExists(clusterNumber: Int): Boolean {
    return plantingSubzones.any { subzone ->
      subzone.monitoringPlots.any { it.permanentCluster == clusterNumber }
    }
  }

  /**
   * Returns the highest-valued monitoring plot name from all the plots that have numeric names.
   * This is used for generating names for new monitoring plots. If there are no monitoring plots,
   * returns 0.
   */
  fun getMaxPlotName(): Int {
    return plantingSubzones
        .flatMap { subzone ->
          subzone.monitoringPlots.mapNotNull { plot -> plot.name.toIntOrNull() }
        }
        .maxOrNull()
        ?: 0
  }

  /**
   * Returns a square Polygon of the requested width/height that is completely contained in the zone
   * and does not intersect with an exclusion area.
   *
   * The square will be positioned a whole multiple of [gridInterval] meters from the specified
   * origin.
   *
   * @param exclusion Areas to exclude from the zone, or null if the whole zone is available.
   * @return The unused square, or null if no suitable area could be found.
   */
  fun findUnusedSquare(
      gridOrigin: Point,
      sizeMeters: Number,
      exclusion: MultiPolygon? = null,
  ): Polygon? {
    return UnusedSquareFinder(boundary, gridOrigin, sizeMeters, exclusion).findUnusedSquare()
  }

  /**
   * Returns a list of square Polygons of the requested width/height that are completely contained
   * in the zone and does not intersect with existing permanent plots or with each other.
   *
   * The squares will all be positioned whole multiples of [gridInterval] from the specified origin.
   *
   * @param excludeAllPermanentPlots If true, exclude the boundaries of all permanent monitoring
   *   plots. If false, only exclude the boundaries of permanent monitoring plots that will be
   *   candidates for inclusion in the next observation (that is, ignore permanent plots that were
   *   used in previous observations but won't be used in the next one).
   * @param exclusion Areas to exclude from the zone, or null if the whole zone is available.
   * @return List of unused squares. If there is not enough room for the requested number of
   *   squares, this may be shorter than [count] elements; if there's no room for even a single
   *   square, the list will be empty.
   */
  fun findUnusedSquares(
      gridOrigin: Point,
      sizeMeters: Number,
      count: Int,
      excludeAllPermanentPlots: Boolean,
      exclusion: MultiPolygon? = null,
  ): List<Polygon> {
    val factory = GeometryFactory(PrecisionModel(), boundary.srid)

    // For purposes of checking whether or not a particular grid position is available, we treat
    // existing permanent plots as part of the exclusion area.
    var exclusionWithAllocatedSquares = getMonitoringPlotGeometry(excludeAllPermanentPlots)
    if (exclusion != null) {
      exclusionWithAllocatedSquares =
          exclusionWithAllocatedSquares.union(exclusion).toMultiPolygon(factory)
    }

    return (1..count).mapNotNull { squareNumber ->
      val square = findUnusedSquare(gridOrigin, sizeMeters, exclusionWithAllocatedSquares)

      if (square != null && squareNumber < count) {
        // Prevent the square from being repeated by adding it to the exclusion area.
        //
        // We can't exclude the entire square because rounding errors would cause adjoining squares
        // to be treated as intersecting with this one, incorrectly disqualifying them. So instead
        // we exclude a small (1/8 the square width/height) triangle near the middle of the square.
        val width = (square.coordinates[2].x - square.coordinates[0].x) / 8.0
        val height = (square.coordinates[2].y - square.coordinates[0].y) / 8.0
        val middle = square.centroid

        exclusionWithAllocatedSquares =
            exclusionWithAllocatedSquares
                .union(
                    factory.createPolygon(
                        arrayOf(
                            middle.coordinate,
                            Coordinate(middle.x + width, middle.y),
                            Coordinate(middle.x, middle.y + height),
                            middle.coordinate)))
                .toMultiPolygon()
      }

      square
    }
  }

  /**
   * Returns the subzone that contains the largest portion of a shape, or null if the shape does not
   * overlap with any subzone.
   */
  fun findPlantingSubzone(geometry: Geometry): PlantingSubzoneModel? {
    return plantingSubzones
        .filter { it.boundary.intersects(geometry) }
        .maxByOrNull { it.boundary.intersection(geometry).area }
  }

  /** Returns the monitoring plot that contains a shape, or null if the shape isn't in any plot. */
  fun findMonitoringPlot(geometry: Geometry): MonitoringPlotModel? {
    return plantingSubzones.firstNotNullOfOrNull { subzone ->
      subzone.monitoringPlots.firstOrNull { plot -> plot.boundary.covers(geometry) }
    }
  }

  /**
   * Returns a MultiPolygon that contains the boundaries of the zone's permanent monitoring plots.
   *
   * @param includeAll If true, include the boundaries of all permanent monitoring plots. If false,
   *   only include the boundaries of permanent monitoring plots that will be candidates for
   *   inclusion in the next observation.
   */
  private fun getMonitoringPlotGeometry(includeAll: Boolean = false): MultiPolygon {
    val factory = GeometryFactory(PrecisionModel(), boundary.srid)

    return factory.createMultiPolygon(
        plantingSubzones
            .flatMap { it.monitoringPlots }
            .filter { plot ->
              plot.permanentCluster != null &&
                  (includeAll || plot.permanentCluster <= numPermanentClusters)
            }
            .map { it.boundary }
            .toTypedArray())
  }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingZoneModel &&
        id == other.id &&
        name == other.name &&
        extraPermanentClusters == other.extraPermanentClusters &&
        numPermanentClusters == other.numPermanentClusters &&
        numTemporaryPlots == other.numTemporaryPlots &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        errorMargin.equalsIgnoreScale(other.errorMargin) &&
        studentsT.equalsIgnoreScale(other.studentsT) &&
        variance.equalsIgnoreScale(other.variance) &&
        plantingSubzones.zip(other.plantingSubzones).all { (a, b) -> a.equals(b, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }
}
