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

data class StratumModel<
    SID : StratumId?,
    SSID : SubstratumId?,
    TIMESTAMP : Instant?,
>(
    val areaHa: BigDecimal,
    val boundary: MultiPolygon,
    val boundaryModifiedTime: TIMESTAMP,
    val errorMargin: BigDecimal = DEFAULT_ERROR_MARGIN,
    val id: SID,
    /** The time of the latest observation, if the stratum has completed observations */
    val latestObservationCompletedTime: Instant? = null,
    /** The ID of the latest observation, if the stratum has completed observations */
    val latestObservationId: ObservationId? = null,
    val name: String,
    val numPermanentPlots: Int = DEFAULT_NUM_PERMANENT_PLOTS,
    val numTemporaryPlots: Int = DEFAULT_NUM_TEMPORARY_PLOTS,
    val substrata: List<SubstratumModel<SSID>>,
    val stableId: StableId,
    val studentsT: BigDecimal = DEFAULT_STUDENTS_T,
    val targetPlantingDensity: BigDecimal = DEFAULT_TARGET_PLANTING_DENSITY,
    val variance: BigDecimal = DEFAULT_VARIANCE,
) {
  /**
   * Chooses a set of plots to act as permanent monitoring plots. The number of plots is determined
   * by [numPermanentPlots].
   *
   * Only plots in requested substrata are returned, meaning there may be fewer plots than
   * configured, or none at all.
   */
  fun choosePermanentPlots(requestedSubstratumIds: Set<SubstratumId>): Set<MonitoringPlotId> {
    if (substrata.isEmpty()) {
      throw IllegalArgumentException("No substrata found for stratum $id (wrong fetch depth?)")
    }

    val requestedSubstrata = substrata.filter { it.id != null && it.id in requestedSubstratumIds }
    val plotsInRequestedSubstrata =
        requestedSubstrata.flatMap { substratum ->
          substratum.monitoringPlots.filter { plot ->
            plot.permanentIndex != null && plot.permanentIndex <= numPermanentPlots
          }
        }

    return plotsInRequestedSubstrata.map { it.id }.toSet()
  }

  /**
   * Chooses a set of plots to act as temporary monitoring plots. The number of plots is determined
   * by [numTemporaryPlots].
   *
   * This follows some rules:
   * - Plots that are already selected as permanent plots aren't eligible.
   * - Plots that span substratum boundaries aren't eligible.
   * - Plots in substrata that were not requested for the observation aren't eligible.
   * - Plots must be spread across substrata as evenly as possible: the number of temporary plots
   *   can't vary by more than 1 between substrata. This even spreading doesn't take eligibility
   *   into account.
   * - If plots can't be exactly evenly spread across substrata (that is, [numTemporaryPlots] is not
   *   a multiple of the number of substrata), the substrata for the remaining plots are determined
   *   using the following criteria in order (with the later criteria used as a tiebreaker if more
   *   than one substratum matches the earlier ones):
   *     - Substrata with the fewest number of permanent plots.
   *     - Substrata that were requested for the observation.
   *     - Substrata with the lowest substratum IDs. Note that it is possible for unrequested
   *       substrata to still be high enough on the priority list to have plots allocated to them;
   *       see the next point.
   * - Only substrata that have been requested should have temporary plots. However, they should be
   *   excluded only after all the preceding rules have been followed. That is, we want to choose
   *   plots based on the rules above, and then filter out plots in unrequested substrata, as
   *   opposed to only spreading plots across requested substrata. Otherwise, for an observation
   *   with a small number of requested substrata, we would end up piling the entire stratum's worth
   *   of temporary plots into a handful of substrata.
   *
   * @return A collection of plot boundaries. These may or may not be the boundaries of plots that
   *   already exist in the database; callers can use [findMonitoringPlot] to check whether they
   *   already exist.
   * @throws IllegalArgumentException The number of temporary plots hasn't been configured or the
   *   stratum has no substrata.
   * @throws SubstratumFullException There weren't enough eligible plots available in a substratum
   *   to choose the required number.
   */
  fun chooseTemporaryPlots(
      requestedSubstratumIds: Set<SubstratumId>,
      gridOrigin: Point,
      exclusion: MultiPolygon? = null,
  ): Collection<Polygon> {
    if (substrata.isEmpty()) {
      throw IllegalArgumentException("No substrata found for stratum $id (wrong fetch depth?)")
    }

    // We will assign as many plots as possible evenly across all substrata, eligible or not.
    val numEvenlySpreadPlotsPerSubstratum = numTemporaryPlots / substrata.size
    val numExcessPlots = numTemporaryPlots.rem(substrata.size)

    // Any plots that can't be spread evenly will be placed in the substrata with the smallest
    // number of permanent plots, with priority given to substrata that are requested, and
    // substratum ID
    // used as a tie-breaker.
    //
    // If we sort the substrata by those criteria, this means we can assign one extra plot each to
    // the first N substrata on that sorted list where N is the number of excess plots.
    return substrata
        .sortedWith(
            compareBy { substratum: SubstratumModel<SSID> ->
                  substratum.monitoringPlots.count { plot ->
                    plot.permanentIndex != null && plot.permanentIndex <= numPermanentPlots
                  }
                }
                .thenBy { if (it.id != null && it.id in requestedSubstratumIds) 0 else 1 }
                .thenBy { it.id?.value ?: 0L }
        )
        .flatMapIndexed { index, substratum ->
          if (substratum.id != null && substratum.id in requestedSubstratumIds) {
            val numPlots =
                if (index < numExcessPlots) {
                  numEvenlySpreadPlotsPerSubstratum + 1
                } else {
                  numEvenlySpreadPlotsPerSubstratum
                }

            val squares =
                findUnusedSquares(
                    count = numPlots,
                    exclusion = exclusion,
                    gridOrigin = gridOrigin,
                    searchBoundary = substratum.boundary,
                )

            if (squares.size < numPlots) {
              throw SubstratumFullException(substratum.id, numPlots, squares.size)
            }

            squares
          } else {
            // This substratum has no plants or wasn't requested, so it gets no temporary plots.
            emptyList()
          }
        }
  }

  /**
   * Returns the substratum that contains a monitoring plot, or null if the plot isn't in any of the
   * substrata.
   */
  fun findSubstratumWithMonitoringPlot(monitoringPlotId: MonitoringPlotId): SubstratumModel<SSID>? {
    return substrata.firstOrNull { substratum ->
      substratum.monitoringPlots.any { it.id == monitoringPlotId }
    }
  }

  /** Returns true if the stratum contains a permanent plot with the supplied index. */
  fun permanentIndexExists(permanentIndex: Int): Boolean {
    return substrata.any { substratum ->
      substratum.monitoringPlots.any { it.permanentIndex == permanentIndex }
    }
  }

  /**
   * Returns a square Polygon of the requested width/height that is completely contained in the
   * stratum and does not intersect with an exclusion area.
   *
   * The square will be positioned a whole multiple of [sizeMeters] meters from the specified
   * origin.
   *
   * @param exclusion Areas to exclude from the stratum, or null if the whole stratum is available.
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
   * in the stratum and does not intersect with existing permanent plots or with each other.
   *
   * The squares will all be positioned whole multiples of [sizeMeters] from the specified origin.
   *
   * @param excludeAllPermanentPlots If true, exclude the boundaries of all permanent monitoring
   *   plots. If false, only exclude the boundaries of permanent monitoring plots that will be
   *   candidates for inclusion in the next observation (that is, ignore permanent plots that were
   *   used in previous observations but won't be used in the next one).
   * @param exclusion Areas to exclude from the stratum, or null if the whole stratum is available.
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
   * Returns the substratum that contains the largest portion of a shape, or null if the shape does
   * not overlap with any substratum.
   */
  fun findSubstratum(geometry: Geometry): SubstratumModel<SSID>? {
    return substrata
        .filter { it.boundary.intersects(geometry) }
        .maxByOrNull { it.boundary.intersection(geometry).area }
  }

  /**
   * Returns the monitoring plot that is of the correct size and contains the center point of a
   * shape, or null if the point isn't in any plot.
   */
  fun findMonitoringPlot(geometry: Geometry): MonitoringPlotModel? {
    val centroid = geometry.centroid

    return substrata.firstNotNullOfOrNull { substratum ->
      substratum.monitoringPlots.firstOrNull { plot ->
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
      problems.add(PlantingSiteValidationFailure.stratumTooSmall(name))
    }

    substrata
        .groupBy { it.name.lowercase() }
        .values
        .filter { it.size > 1 }
        .forEach {
          problems.add(PlantingSiteValidationFailure.duplicateSubstratumName(it[0].name, name))
        }

    if (substrata.isEmpty()) {
      problems.add(PlantingSiteValidationFailure.stratumHasNoSubstrata(name))
    }

    substrata.forEachIndexed { index, substratum ->
      if (!substratum.boundary.nearlyCoveredBy(boundary)) {
        problems.add(PlantingSiteValidationFailure.substratumNotInStratum(substratum.name, name))
      }

      if (newModel.exclusion != null && substratum.boundary.nearlyCoveredBy(newModel.exclusion)) {
        problems.add(PlantingSiteValidationFailure.substratumInExclusionArea(substratum.name, name))
      }

      substrata.drop(index + 1).forEach { otherSubstratum ->
        val overlapPercent = substratum.boundary.coveragePercent(otherSubstratum.boundary)
        if (overlapPercent > PlantingSiteModel.REGION_OVERLAP_MAX_PERCENT) {
          problems.add(
              PlantingSiteValidationFailure.substratumBoundaryOverlaps(
                  setOf(otherSubstratum.name),
                  substratum.name,
                  name,
              )
          )
        }
      }
    }

    return problems
  }

  /**
   * Returns a MultiPolygon that contains polygons in each of the stratum's permanent monitoring
   * plots, its unavailable plots, and plots with particular IDs. Returns null if there are no plots
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
        substrata
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
    return other is AnyStratumModel &&
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
        substrata.zip(other.substrata).all { (a, b) -> a.equals(b, tolerance) } &&
        boundary.equalsExact(other.boundary, tolerance)
  }

  fun toNew(): NewStratumModel =
      NewStratumModel(
          areaHa = areaHa,
          boundary = boundary,
          boundaryModifiedTime = null,
          errorMargin = errorMargin,
          id = null,
          name = name,
          numPermanentPlots = numPermanentPlots,
          numTemporaryPlots = numTemporaryPlots,
          substrata = substrata.map { it.toNew() },
          stableId = stableId,
          studentsT = studentsT,
          targetPlantingDensity = targetPlantingDensity,
          variance = variance,
      )

  companion object {
    // Default values of the three parameters that determine how many monitoring plots should be
    // required in each observation. The "Student's t" value is a constant based on a 90%
    // confidence level and should rarely need to change, but the other two will be adjusted by
    // admins based on the conditions at the planting site. These defaults mean that strata
    // will have 11 permanent plots and 14 temporary plots.
    val DEFAULT_ERROR_MARGIN = BigDecimal(100)
    val DEFAULT_STUDENTS_T = BigDecimal("1.645")
    val DEFAULT_VARIANCE = BigDecimal(40000)
    const val DEFAULT_NUM_PERMANENT_PLOTS = 8
    const val DEFAULT_NUM_TEMPORARY_PLOTS = 3

    /** Target planting density to use if not included in stratum properties. */
    val DEFAULT_TARGET_PLANTING_DENSITY = BigDecimal(1500)

    fun create(
        boundary: MultiPolygon,
        name: String,
        substrata: List<NewSubstratumModel>,
        exclusion: MultiPolygon? = null,
        errorMargin: BigDecimal = DEFAULT_ERROR_MARGIN,
        numPermanentPlots: Int? = null,
        numTemporaryPlots: Int? = null,
        stableId: StableId = StableId(name),
        studentsT: BigDecimal = DEFAULT_STUDENTS_T,
        targetPlantingDensity: BigDecimal = DEFAULT_TARGET_PLANTING_DENSITY,
        variance: BigDecimal = DEFAULT_VARIANCE,
    ): NewStratumModel {
      val areaHa: BigDecimal = boundary.differenceNullable(exclusion).calculateAreaHectares()
      val defaultTotalPlots =
          (studentsT * studentsT * variance / errorMargin / errorMargin)
              .setScale(0, RoundingMode.UP)
              .toInt()
      val defaultPermanentPlots = max((defaultTotalPlots * 0.75).roundToInt(), 1)
      val defaultTemporaryPlots = max(defaultTotalPlots - defaultPermanentPlots, 1)

      return NewStratumModel(
          areaHa = areaHa,
          boundary = boundary,
          boundaryModifiedTime = null,
          errorMargin = errorMargin,
          id = null,
          name = name,
          numPermanentPlots = numPermanentPlots ?: defaultPermanentPlots,
          numTemporaryPlots = numTemporaryPlots ?: defaultTemporaryPlots,
          substrata = substrata,
          stableId = stableId,
          studentsT = studentsT,
          targetPlantingDensity = targetPlantingDensity,
          variance = variance,
      )
    }
  }
}

typealias AnyStratumModel = StratumModel<out StratumId?, out SubstratumId?, out Instant?>

typealias ExistingStratumModel = StratumModel<StratumId, SubstratumId, Instant>

typealias NewStratumModel = StratumModel<Nothing?, Nothing?, Nothing?>
