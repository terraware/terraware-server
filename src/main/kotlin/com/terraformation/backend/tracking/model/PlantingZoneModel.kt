package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.coveragePercent
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.nearlyCoveredBy
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

data class PlantingZoneModel<PZID : PlantingZoneId?, PSZID : PlantingSubzoneId?>(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val errorMargin: BigDecimal = DEFAULT_ERROR_MARGIN,
    val extraPermanentClusters: Int = 0,
    val id: PZID,
    val name: String,
    val numPermanentClusters: Int = DEFAULT_NUM_PERMANENT_CLUSTERS,
    val numTemporaryPlots: Int = DEFAULT_NUM_TEMPORARY_PLOTS,
    val plantingSubzones: List<PlantingSubzoneModel<PSZID>>,
    val studentsT: BigDecimal = DEFAULT_STUDENTS_T,
    val targetPlantingDensity: BigDecimal = DEFAULT_TARGET_PLANTING_DENSITY,
    val variance: BigDecimal = DEFAULT_VARIANCE,
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

    val plantedSubzones = plantingSubzones.filter { it.id != null && it.id in plantedSubzoneIds }
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
   * @return A collection of plot boundaries. These may or may not be the boundaries of plots that
   *   already exist in the database; callers can use [findMonitoringPlot] to check whether they
   *   already exist.
   * @throws IllegalArgumentException The number of temporary plots hasn't been configured or the
   *   planting zone has no subzones.
   * @throws PlantingSubzoneFullException There weren't enough eligible plots available in a subzone
   *   to choose the required number.
   */
  fun chooseTemporaryPlots(
      plantedSubzoneIds: Set<PlantingSubzoneId>,
      gridOrigin: Point,
      exclusion: MultiPolygon? = null,
  ): Collection<Polygon> {
    val permanentPlotIds = choosePermanentPlots(plantedSubzoneIds)

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
          if (subzone.id != null && subzone.id in plantedSubzoneIds) numPermanentPlots
          else numPermanentPlots + 1
        }
        .flatMapIndexed { index, subzone ->
          if (subzone.id != null && subzone.id in plantedSubzoneIds) {
            val numPlots =
                if (index < numExcessPlots) {
                  numEvenlySpreadPlotsPerSubzone + 1
                } else {
                  numEvenlySpreadPlotsPerSubzone
                }

            val squares =
                findUnusedSquares(
                    count = numPlots,
                    exclusion = exclusion,
                    gridOrigin = gridOrigin,
                    searchBoundary = subzone.boundary,
                )

            if (squares.size < numPlots) {
              throw PlantingSubzoneFullException(subzone.id, numPlots, squares.size)
            }

            squares
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
  fun findSubzoneWithMonitoringPlot(
      monitoringPlotId: MonitoringPlotId
  ): PlantingSubzoneModel<PSZID>? {
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
        .maxOrNull() ?: 0
  }

  /**
   * Returns a square Polygon of the requested width/height that is completely contained in the zone
   * and does not intersect with an exclusion area.
   *
   * The square will be positioned a whole multiple of [sizeMeters] meters from the specified
   * origin.
   *
   * @param exclusion Areas to exclude from the zone, or null if the whole zone is available.
   * @return The unused square, or null if no suitable area could be found.
   */
  fun findUnusedSquare(
      gridOrigin: Point,
      sizeMeters: Number,
      exclusion: MultiPolygon? = null,
      searchBoundary: MultiPolygon = this.boundary,
  ): Polygon? {
    return UnusedSquareFinder(searchBoundary, gridOrigin, sizeMeters, exclusion).findUnusedSquare()
  }

  /**
   * Returns a list of square Polygons of the requested width/height that are completely contained
   * in the zone and does not intersect with existing permanent plots or with each other.
   *
   * The squares will all be positioned whole multiples of [sizeMeters] from the specified origin.
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
      sizeMeters: Number = MONITORING_PLOT_SIZE,
      count: Int,
      excludeAllPermanentPlots: Boolean = false,
      excludePlotIds: Set<MonitoringPlotId> = emptySet(),
      exclusion: MultiPolygon? = null,
      searchBoundary: MultiPolygon = this.boundary,
  ): List<Polygon> {
    // For purposes of checking whether or not a particular grid position is available, we treat
    // existing permanent plots as part of the exclusion area.
    var exclusionWithAllocatedSquares =
        getMonitoringPlotExclusions(excludeAllPermanentPlots, excludePlotIds)
    if (exclusion != null) {
      exclusionWithAllocatedSquares =
          exclusionWithAllocatedSquares?.union(exclusion)?.toMultiPolygon() ?: exclusion
    }

    return (1..count).mapNotNull { squareNumber ->
      val square =
          findUnusedSquare(gridOrigin, sizeMeters, exclusionWithAllocatedSquares, searchBoundary)

      if (square != null && squareNumber < count) {
        // Prevent this square from being selected again by excluding an area in the middle of it.
        val additionalExclusion = middleTriangle(square)

        val newCombinedExclusion =
            exclusionWithAllocatedSquares?.union(additionalExclusion) ?: additionalExclusion

        exclusionWithAllocatedSquares = newCombinedExclusion.toMultiPolygon()
      }

      square
    }
  }

  /**
   * Returns a small triangle in the middle of a square. We add this to the exclusion area to
   * prevent the square from being selected. Adding the entire square might cause adjoining squares
   * to also be counted as excluded due to rounding errors.
   */
  private fun middleTriangle(square: Polygon): Polygon {
    val width = (square.coordinates[2].x - square.coordinates[0].x) / 8.0
    val height = (square.coordinates[2].y - square.coordinates[0].y) / 8.0
    val middle = square.centroid

    return square.factory.createPolygon(
        arrayOf(
            middle.coordinate,
            Coordinate(middle.x + width, middle.y),
            Coordinate(middle.x, middle.y + height),
            middle.coordinate))
  }

  /**
   * Returns the subzone that contains the largest portion of a shape, or null if the shape does not
   * overlap with any subzone.
   */
  fun findPlantingSubzone(geometry: Geometry): PlantingSubzoneModel<PSZID>? {
    return plantingSubzones
        .filter { it.boundary.intersects(geometry) }
        .maxByOrNull { it.boundary.intersection(geometry).area }
  }

  /**
   * Returns the monitoring plot that contains the center point of a shape, or null if the shape
   * isn't in any plot.
   */
  fun findMonitoringPlot(geometry: Geometry): MonitoringPlotModel? {
    val centroid = geometry.centroid

    return plantingSubzones.firstNotNullOfOrNull { subzone ->
      subzone.monitoringPlots.firstOrNull { plot -> plot.boundary.contains(centroid) }
    }
  }

  fun validate(newModel: PlantingSiteModel<*, *, *>): List<String> {
    val problems = mutableListOf<String>()

    // Find 5 squares: 4 for the cluster and one for a temporary plot.
    val plotBoundaries =
        findUnusedSquares(
            count = 5,
            exclusion = newModel.exclusion,
            gridOrigin = newModel.gridOrigin!!,
        )

    // Make sure we have room for an actual cluster.
    val clusterBoundaries =
        findUnusedSquares(
            count = 1,
            exclusion = newModel.exclusion,
            gridOrigin = newModel.gridOrigin,
            sizeMeters = MONITORING_PLOT_SIZE * 2,
        )

    if (clusterBoundaries.isEmpty() || plotBoundaries.size < 5) {
      problems.add(
          "Planting zone $name is too small to create minimum number of monitoring plots (is the " +
              "zone at least 150x75 meters?)")
    }

    plantingSubzones
        .groupBy { it.name.lowercase() }
        .values
        .filter { it.size > 1 }
        .forEach { problems.add("Zone $name has two subzones named ${it[0].name}") }

    if (plantingSubzones.isEmpty()) {
      problems.add("Planting zone $name has no subzones")
    }

    plantingSubzones.forEachIndexed { index, subzone ->
      if (!subzone.boundary.nearlyCoveredBy(boundary)) {
        val percent = "%.02f%%".format(100.0 - subzone.boundary.coveragePercent(boundary))
        problems.add(
            "$percent of planting subzone ${subzone.name} is not contained within planting " +
                "zone $name")
      }

      if (newModel.exclusion != null && subzone.boundary.nearlyCoveredBy(newModel.exclusion)) {
        problems.add("Subzone ${subzone.name} in zone $name is inside exclusion area")
      }

      plantingSubzones.drop(index + 1).forEach { otherSubzone ->
        val overlapPercent = subzone.boundary.coveragePercent(otherSubzone.boundary)
        if (overlapPercent > PlantingSiteModel.REGION_OVERLAP_MAX_PERCENT) {
          val overlapPercentText = "%.02f%%".format(overlapPercent)
          problems.add(
              "$overlapPercentText of subzone ${subzone.name} in zone $name overlaps with " +
                  "subzone ${otherSubzone.name}")
        }
      }
    }

    return problems
  }

  /**
   * Returns a MultiPolygon that contains polygons in each of the zone's permanent monitoring plots,
   * its unavailable plots, and plots with particular IDs. Returns null if there are no plots
   * matching any of the criteria.
   *
   * @param includeAll If true, include the boundaries of all permanent monitoring plots. If false,
   *   only include the boundaries of permanent monitoring plots that will be candidates for
   *   inclusion in the next observation.
   */
  private fun getMonitoringPlotExclusions(
      includeAll: Boolean = false,
      excludePlotIds: Set<MonitoringPlotId>,
  ): MultiPolygon? {
    val relevantPlots =
        plantingSubzones
            .flatMap { it.monitoringPlots }
            .filter { plot ->
              !plot.isAvailable ||
                  plot.id in excludePlotIds ||
                  plot.permanentCluster != null &&
                      (includeAll || plot.permanentCluster <= numPermanentClusters)
            }

    if (relevantPlots.isEmpty()) {
      return null
    }

    return relevantPlots
        .map { it.boundary }
        .map { middleTriangle(it) }
        .reduce { acc: Geometry, polygon: Geometry -> acc.union(polygon) }
        .toMultiPolygon()
  }

  fun equals(other: Any?, tolerance: Double): Boolean {
    return other is PlantingZoneModel<*, *> &&
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

  fun toNew(): NewPlantingZoneModel =
      create(
          boundary,
          name,
          plantingSubzones.map { it.toNew() },
          areaHa,
          errorMargin,
          extraPermanentClusters,
          numPermanentClusters,
          numTemporaryPlots,
          studentsT,
          targetPlantingDensity,
          variance)

  companion object {
    // Default values of the three parameters that determine how many monitoring plots should be
    // required in each observation. The "Student's t" value is a constant based on an 80%
    // confidence level and should rarely need to change, but the other two will be adjusted by
    // admins based on the conditions at the planting site. These defaults mean that planting zones
    // will have 7 permanent clusters and 9 temporary plots.
    val DEFAULT_ERROR_MARGIN = BigDecimal(100)
    val DEFAULT_STUDENTS_T = BigDecimal("1.282")
    val DEFAULT_VARIANCE = BigDecimal(40000)
    const val DEFAULT_NUM_PERMANENT_CLUSTERS = 7
    const val DEFAULT_NUM_TEMPORARY_PLOTS = 9

    /** Target planting density to use if not included in zone properties. */
    val DEFAULT_TARGET_PLANTING_DENSITY = BigDecimal(1500)

    fun create(
        boundary: MultiPolygon,
        name: String,
        plantingSubzones: List<NewPlantingSubzoneModel>,
        areaHa: BigDecimal = boundary.calculateAreaHectares(),
        errorMargin: BigDecimal = DEFAULT_ERROR_MARGIN,
        extraPermanentClusters: Int = 0,
        numPermanentClusters: Int = DEFAULT_NUM_PERMANENT_CLUSTERS,
        numTemporaryPlots: Int = DEFAULT_NUM_TEMPORARY_PLOTS,
        studentsT: BigDecimal = DEFAULT_STUDENTS_T,
        targetPlantingDensity: BigDecimal = DEFAULT_TARGET_PLANTING_DENSITY,
        variance: BigDecimal = DEFAULT_VARIANCE,
    ): NewPlantingZoneModel {
      return NewPlantingZoneModel(
          areaHa = areaHa,
          boundary = boundary,
          errorMargin = errorMargin,
          extraPermanentClusters = extraPermanentClusters,
          id = null,
          name = name,
          numPermanentClusters = numPermanentClusters,
          numTemporaryPlots = numTemporaryPlots,
          plantingSubzones = plantingSubzones,
          studentsT = studentsT,
          targetPlantingDensity = targetPlantingDensity,
          variance = variance,
      )
    }
  }
}

typealias AnyPlantingZoneModel = PlantingZoneModel<out PlantingZoneId?, out PlantingSubzoneId?>

typealias ExistingPlantingZoneModel = PlantingZoneModel<PlantingZoneId, PlantingSubzoneId>

typealias NewPlantingZoneModel = PlantingZoneModel<Nothing?, Nothing?>
