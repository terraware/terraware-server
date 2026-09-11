package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.util.nearlyCoveredBy
import kotlin.math.roundToInt
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

/**
 * Chooses the monitoring plots for one stratum in one observation.
 *
 * A stratum can hold fewer plots than it is configured for. When that happens, 75% of the plots
 * that fit are allocated as permanent, 25% as temporary. This can mean demoting a permanent plot to
 * temporary even though its permanent index is less than [StratumModel.numPermanentPlots].
 *
 * A permanent plot that has already been observed is never demoted, even if that pushes the split
 * away from the target ratio.
 */
class StratumPlotAllocator(
    private val stratum: ExistingStratumModel,
    private val gridOrigin: Point,
    private val exclusion: MultiPolygon?,
    /**
     * Plots that have already been observed as permanent. May include plots outside this stratum.
     */
    private val observedPermanentPlotIds: Set<MonitoringPlotId>,
) {
  /**
   * Fraction of a stratum's plots that should be treated as permanent when there isn't room for the
   * full number of configured plots.
   */
  private val permanentPlotFraction = 0.75

  /**
   * Allocates as many plots as possible (up to the configured limits) in the stratum.
   *
   * @throws StratumFullException The stratum has no room for any monitoring plots at all.
   */
  fun allocate(requestedSubstratumIds: Set<SubstratumId>): Allocation {
    val numConfigured = stratum.numPermanentPlots + stratum.numTemporaryPlots

    val existingPermanent =
        stratum.substrata
            .flatMap { it.monitoringPlots }
            .filter { plot ->
              plot.permanentIndex != null && plot.permanentIndex <= stratum.numPermanentPlots
            }
            .sortedBy { it.permanentIndex }

    val numUnusedSquares =
        stratum
            .findUnusedSquares(
                count = numConfigured - existingPermanent.size,
                exclusion = exclusion,
                gridOrigin = gridOrigin,
                predicate = { squareBoundary ->
                  stratum.substrata.any { squareBoundary.nearlyCoveredBy(it.boundary) }
                },
            )
            .size

    var numAllocated = existingPermanent.size + numUnusedSquares

    if (numAllocated == 0) {
      throw StratumFullException(stratum.id)
    }

    if (numAllocated >= numConfigured) {
      return Allocation(
          permanentPlotIds = stratum.choosePermanentPlots(requestedSubstratumIds),
          temporaryPlotBoundaries =
              stratum.chooseTemporaryPlots(requestedSubstratumIds, gridOrigin, exclusion),
          numConfigured = numConfigured,
          numAllocated = numAllocated,
      )
    }

    val (observed, unobserved) = existingPermanent.partition { it.id in observedPermanentPlotIds }
    val availablePermanent = observed + unobserved
    var numPermanent = availablePermanent.size

    while (
        numPermanent > observed.size &&
            numPermanent > (numAllocated * permanentPlotFraction).roundToInt()
    ) {
      val demotedPlot = availablePermanent[--numPermanent]

      // A permanent plot can cross substratum boundaries, but a temporary plot must fit within
      // one substratum. Losing such a plot reduces capacity and can require further demotions.
      if (stratum.substrata.none { demotedPlot.boundary.nearlyCoveredBy(it.boundary) }) {
        numAllocated--
      }
    }

    val permanentPlotIds =
        availablePermanent
            .take(numPermanent)
            .map { it.id }
            .toSet()
            .intersect(stratum.choosePermanentPlots(requestedSubstratumIds))

    return Allocation(
        permanentPlotIds = permanentPlotIds,
        temporaryPlotBoundaries =
            stratum.chooseTemporaryPlots(
                requestedSubstratumIds,
                gridOrigin,
                exclusion,
                count = numAllocated - numPermanent,
                permanentPlotIds = permanentPlotIds,
            ),
        numConfigured = numConfigured,
        numAllocated = numAllocated,
    )
  }

  /** The monitoring plots chosen for a stratum in an observation. */
  data class Allocation(
      val permanentPlotIds: Set<MonitoringPlotId>,
      val temporaryPlotBoundaries: Collection<Polygon>,
      /**
       * Number of monitoring plots configured for the stratum. This is the sum of
       * [StratumModel.numPermanentPlots] and [StratumModel.numTemporaryPlots].
       */
      val numConfigured: Int,
      /**
       * Number of monitoring plots that ultimately fit in the stratum. This is the stratum-wide
       * total and includes plots that would have been allocated in unrequested substrata, so the
       * actual number of plots in the observation may be less than this.
       */
      val numAllocated: Int,
  ) {
    val isShortfall: Boolean
      get() = numAllocated < numConfigured
  }
}
