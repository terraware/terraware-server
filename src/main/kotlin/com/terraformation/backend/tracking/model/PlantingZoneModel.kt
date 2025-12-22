package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.coveragePercent
import com.terraformation.backend.util.differenceNullable
import com.terraformation.backend.util.equalsIgnoreScale
import com.terraformation.backend.util.nearlyCoveredBy
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

data class PlantingZoneModel<
    PZID : StratumId?,
    PSZID : SubstratumId?,
    TIMESTAMP : Instant?,
>(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val boundaryModifiedTime: TIMESTAMP,
    val errorMargin: BigDecimal = DEFAULT_ERROR_MARGIN,
    val id: PZID,
    /** The time of the latest observation, if the planting zone has completed observations */
    val latestObservationCompletedTime: Instant? = null,
    /** The ID of the latest observation, if the planting zone has completed observations */
    val latestObservationId: ObservationId? = null,
    val name: String,
    val numPermanentPlots: Int = DEFAULT_NUM_PERMANENT_PLOTS,
    val numTemporaryPlots: Int = DEFAULT_NUM_TEMPORARY_PLOTS,
    val plantingSubzones: List<PlantingSubzoneModel<PSZID>>,
    val stableId: StableId,
    val studentsT: BigDecimal = DEFAULT_STUDENTS_T,
    val targetPlantingDensity: BigDecimal = DEFAULT_TARGET_PLANTING_DENSITY,
    val variance: BigDecimal = DEFAULT_VARIANCE,
) {
  /**
   * Chooses a set of plots to act as permanent monitoring plots. The number of plots is determined
   * by [numPermanentPlots].
   *
   * Only plots in requested subzones are returned, meaning there may be fewer plots than
   * configured, or none at all.
   */
  fun choosePermanentPlots(requestedSubzoneIds: Set<SubstratumId>): Set<MonitoringPlotId> {
    if (plantingSubzones.isEmpty()) {
      throw IllegalArgumentException("No subzones found for planting zone $id (wrong fetch depth?)")
    }

    val requestedSubzones =
        plantingSubzones.filter { it.id != null && it.id in requestedSubzoneIds }
    val plotsInRequestedSubzones =
        requestedSubzones.flatMap { subzone ->
          subzone.monitoringPlots.filter { plot ->
            plot.permanentIndex != null && plot.permanentIndex <= numPermanentPlots
          }
        }

    return plotsInRequestedSubzones.map { it.id }.toSet()
  }

  /**
   * Chooses a set of plots to act as temporary monitoring plots. The number of plots is determined
   * by [numTemporaryPlots].
   *
   * This follows some rules:
   * - Plots that are already selected as permanent plots aren't eligible.
   * - Plots that span subzone boundaries aren't eligible.
   * - Plots in subzones that were not requested for the observation aren't eligible.
   * - Plots must be spread across subzones as evenly as possible: the number of temporary plots
   *   can't vary by more than 1 between subzones. This even spreading doesn't take eligibility into
   *   account.
   * - If plots can't be exactly evenly spread across subzones (that is, [numTemporaryPlots] is not
   *   a multiple of the number of subzones), the subzones for the remaining plots are determined
   *   using the following criteria in order (with the later criteria used as a tiebreaker if more
   *   than one subzone matches the earlier ones):
   *     - Subzones with the fewest number of permanent plots.
   *     - Subzones that were requested for the observation.
   *     - Subzones with the lowest subzone IDs. Note that it is possible for unrequested subzones
   *       to still be high enough on the priority list to have plots allocated to them; see the
   *       next point.
   * - Only subzones that have been requested should have temporary plots. However, they should be
   *   excluded only after all the preceding rules have been followed. That is, we want to choose
   *   plots based on the rules above, and then filter out plots in unrequested subzones, as opposed
   *   to only spreading plots across requested subzones. Otherwise, for an observation with a small
   *   number of requested subzones, we would end up piling the entire planting zone's worth of
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
      requestedSubzoneIds: Set<SubstratumId>,
      gridOrigin: Point,
      exclusion: MultiPolygon? = null,
  ): Collection<Polygon> {
    if (plantingSubzones.isEmpty()) {
      throw IllegalArgumentException("No subzones found for planting zone $id (wrong fetch depth?)")
    }

    // We will assign as many plots as possible evenly across all subzones, eligible or not.
    val numEvenlySpreadPlotsPerSubzone = numTemporaryPlots / plantingSubzones.size
    val numExcessPlots = numTemporaryPlots.rem(plantingSubzones.size)

    // Any plots that can't be spread evenly will be placed in the subzones with the smallest
    // number of permanent plots, with priority given to subzones that are requested, and subzone ID
    // used as a tie-breaker.
    //
    // If we sort the subzones by those criteria, this means we can assign one extra plot each to
    // the first N subzones on that sorted list where N is the number of excess plots.
    return plantingSubzones
        .sortedWith(
            compareBy { subzone: PlantingSubzoneModel<PSZID> ->
                  subzone.monitoringPlots.count { plot ->
                    plot.permanentIndex != null && plot.permanentIndex <= numPermanentPlots
                  }
                }
                .thenBy { if (it.id != null && it.id in requestedSubzoneIds) 0 else 1 }
                .thenBy { it.id?.value ?: 0L }
        )
        .flatMapIndexed { index, subzone ->
          if (subzone.id != null && subzone.id in requestedSubzoneIds) {
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
            // This subzone has no plants or wasn't requested, so it gets no temporary plots.
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

  /** Returns true if the zone contains a permanent plot with the supplied index. */
  fun permanentIndexExists(permanentIndex: Int): Boolean {
    return plantingSubzones.any { subzone ->
      subzone.monitoringPlots.any { it.permanentIndex == permanentIndex }
    }
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
            middle.coordinate,
        )
    )
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
   * Returns the monitoring plot that is of the correct size and contains the center point of a
   * shape, or null if the point isn't in any plot.
   */
  fun findMonitoringPlot(geometry: Geometry): MonitoringPlotModel? {
    val centroid = geometry.centroid

    return plantingSubzones.firstNotNullOfOrNull { subzone ->
      subzone.monitoringPlots.firstOrNull { plot ->
        plot.boundary.contains(centroid) && plot.sizeMeters == MONITORING_PLOT_SIZE_INT
      }
    }
  }

  fun validate(newModel: AnyPlantingSiteModel): List<PlantingSiteValidationFailure> {
    val problems = mutableListOf<PlantingSiteValidationFailure>()

    // Find two squares: one for a permanent plot and one for a temporary plot.
    val plotBoundaries =
        findUnusedSquares(
            count = 2,
            exclusion = newModel.exclusion,
            gridOrigin = newModel.gridOrigin!!,
        )

    if (plotBoundaries.size < 2) {
      problems.add(PlantingSiteValidationFailure.zoneTooSmall(name))
    }

    plantingSubzones
        .groupBy { it.name.lowercase() }
        .values
        .filter { it.size > 1 }
        .forEach {
          problems.add(PlantingSiteValidationFailure.duplicateSubzoneName(it[0].name, name))
        }

    if (plantingSubzones.isEmpty()) {
      problems.add(PlantingSiteValidationFailure.zoneHasNoSubzones(name))
    }

    plantingSubzones.forEachIndexed { index, subzone ->
      if (!subzone.boundary.nearlyCoveredBy(boundary)) {
        problems.add(PlantingSiteValidationFailure.subzoneNotInZone(subzone.name, name))
      }

      if (newModel.exclusion != null && subzone.boundary.nearlyCoveredBy(newModel.exclusion)) {
        problems.add(PlantingSiteValidationFailure.subzoneInExclusionArea(subzone.name, name))
      }

      plantingSubzones.drop(index + 1).forEach { otherSubzone ->
        val overlapPercent = subzone.boundary.coveragePercent(otherSubzone.boundary)
        if (overlapPercent > PlantingSiteModel.REGION_OVERLAP_MAX_PERCENT) {
          problems.add(
              PlantingSiteValidationFailure.subzoneBoundaryOverlaps(
                  setOf(otherSubzone.name),
                  subzone.name,
                  name,
              )
          )
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
                  plot.permanentIndex != null &&
                      (includeAll || plot.permanentIndex <= numPermanentPlots)
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
    return other is AnyPlantingZoneModel &&
        id == other.id &&
        name == other.name &&
        boundaryModifiedTime == other.boundaryModifiedTime &&
        numPermanentPlots == other.numPermanentPlots &&
        numTemporaryPlots == other.numTemporaryPlots &&
        areaHa.equalsIgnoreScale(other.areaHa) &&
        errorMargin.equalsIgnoreScale(other.errorMargin) &&
        studentsT.equalsIgnoreScale(other.studentsT) &&
        variance.equalsIgnoreScale(other.variance) &&
        targetPlantingDensity.equalsIgnoreScale(other.targetPlantingDensity) &&
        plantingSubzones.zip(other.plantingSubzones).all { (a, b) -> a.equals(b, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }

  fun toNew(): NewPlantingZoneModel =
      NewPlantingZoneModel(
          areaHa = areaHa,
          boundary = boundary,
          boundaryModifiedTime = null,
          errorMargin = errorMargin,
          id = null,
          name = name,
          numPermanentPlots = numPermanentPlots,
          numTemporaryPlots = numTemporaryPlots,
          plantingSubzones = plantingSubzones.map { it.toNew() },
          stableId = stableId,
          studentsT = studentsT,
          targetPlantingDensity = targetPlantingDensity,
          variance = variance,
      )

  companion object {
    // Default values of the three parameters that determine how many monitoring plots should be
    // required in each observation. The "Student's t" value is a constant based on a 90%
    // confidence level and should rarely need to change, but the other two will be adjusted by
    // admins based on the conditions at the planting site. These defaults mean that planting zones
    // will have 11 permanent plots and 14 temporary plots.
    val DEFAULT_ERROR_MARGIN = BigDecimal(100)
    val DEFAULT_STUDENTS_T = BigDecimal("1.645")
    val DEFAULT_VARIANCE = BigDecimal(40000)
    const val DEFAULT_NUM_PERMANENT_PLOTS = 8
    const val DEFAULT_NUM_TEMPORARY_PLOTS = 3

    /** Target planting density to use if not included in zone properties. */
    val DEFAULT_TARGET_PLANTING_DENSITY = BigDecimal(1500)

    fun create(
        boundary: MultiPolygon,
        name: String,
        plantingSubzones: List<NewPlantingSubzoneModel>,
        exclusion: MultiPolygon? = null,
        errorMargin: BigDecimal = DEFAULT_ERROR_MARGIN,
        numPermanentPlots: Int? = null,
        numTemporaryPlots: Int? = null,
        stableId: StableId = StableId(name),
        studentsT: BigDecimal = DEFAULT_STUDENTS_T,
        targetPlantingDensity: BigDecimal = DEFAULT_TARGET_PLANTING_DENSITY,
        variance: BigDecimal = DEFAULT_VARIANCE,
    ): NewPlantingZoneModel {
      val areaHa: BigDecimal = boundary.differenceNullable(exclusion).calculateAreaHectares()
      val defaultTotalPlots =
          (studentsT * studentsT * variance / errorMargin / errorMargin)
              .setScale(0, RoundingMode.UP)
              .toInt()
      val defaultPermanentPlots = max((defaultTotalPlots * 0.75).roundToInt(), 1)
      val defaultTemporaryPlots = max(defaultTotalPlots - defaultPermanentPlots, 1)

      return NewPlantingZoneModel(
          areaHa = areaHa,
          boundary = boundary,
          boundaryModifiedTime = null,
          errorMargin = errorMargin,
          id = null,
          name = name,
          numPermanentPlots = numPermanentPlots ?: defaultPermanentPlots,
          numTemporaryPlots = numTemporaryPlots ?: defaultTemporaryPlots,
          plantingSubzones = plantingSubzones,
          stableId = stableId,
          studentsT = studentsT,
          targetPlantingDensity = targetPlantingDensity,
          variance = variance,
      )
    }
  }
}

typealias AnyPlantingZoneModel = PlantingZoneModel<out StratumId?, out SubstratumId?, out Instant?>

typealias ExistingPlantingZoneModel = PlantingZoneModel<StratumId, SubstratumId, Instant>

typealias NewPlantingZoneModel = PlantingZoneModel<Nothing?, Nothing?, Nothing?>
