package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationType
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
 * temporary even though its permanent index is less than [StratumPlotCounts.numPermanentPlots].
 *
 * A permanent plot that has already been observed is never demoted, even if that pushes the split
 * away from the target ratio.
 */
class StratumPlotAllocator(
    private val stratum: ExistingStratumModel,
    private val observationType: ObservationType,
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
    val plan = planAllocation(stratum)
    if (plan.numAllocated == 0) {
      throw StratumFullException(stratum.id)
    }

    if (!plan.isShortfall) {
      return Allocation(
          permanentPlotIds = stratum.choosePermanentPlots(requestedSubstratumIds, observationType),
          temporaryPlotBoundaries =
              stratum.chooseTemporaryPlots(
                  requestedSubstratumIds,
                  gridOrigin,
                  observationType,
                  exclusion,
              ),
          numConfigured = plan.numConfigured,
          numAllocated = plan.numAllocated,
      )
    }

    val permanentPlotIds =
        plan.availablePermanent
            .take(plan.numPermanent)
            .map { it.id }
            .toSet()
            .intersect(stratum.choosePermanentPlots(requestedSubstratumIds, observationType))

    return Allocation(
        permanentPlotIds = permanentPlotIds,
        temporaryPlotBoundaries =
            stratum.chooseTemporaryPlots(
                requestedSubstratumIds,
                gridOrigin,
                observationType,
                exclusion,
                count = plan.numAllocated - plan.numPermanent,
                permanentPlotIds =
                    if (stratum.plotCounts(observationType).numPermanentPlots == 0) {
                      null
                    } else {
                      permanentPlotIds
                    },
            ),
        numConfigured = plan.numConfigured,
        numAllocated = plan.numAllocated,
    )
  }

  /**
   * Returns how many permanent and temporary plots fit in the stratum for its configured counts,
   * simulating placement of any missing permanent plots first.
   */
  fun countPlaceable(): PlaceablePlotCounts {
    if (stratum.substrata.isEmpty()) {
      return PlaceablePlotCounts(numPermanentPlots = 0, numTemporaryPlots = 0)
    }

    val targetStratum = withSimulatedPermanentPlots()
    val plan = planAllocation(targetStratum)
    return PlaceablePlotCounts(
        numPermanentPlots = plan.numPermanent,
        numTemporaryPlots = plan.numAllocated - plan.numPermanent,
    )
  }

  private fun planAllocation(targetStratum: ExistingStratumModel): AllocationPlan {
    val plotCounts = targetStratum.plotCounts(observationType)
    val numConfigured = plotCounts.numPermanentPlots + plotCounts.numTemporaryPlots

    val existingPermanent =
        targetStratum.substrata
            .flatMap { it.monitoringPlots }
            .filter { plot ->
              plot.permanentIndex != null && plot.permanentIndex <= plotCounts.numPermanentPlots
            }
            .sortedBy { it.permanentIndex }

    val numUnusedSquares =
        if (numConfigured > existingPermanent.size) {
          targetStratum
              .findUnusedSquares(
                  count = numConfigured - existingPermanent.size,
                  exclusion = exclusion,
                  gridOrigin = gridOrigin,
                  predicate = { squareBoundary ->
                    targetStratum.substrata.any { squareBoundary.nearlyCoveredBy(it.boundary) }
                  },
              )
              .size
        } else {
          0
        }

    var numAllocated = existingPermanent.size + numUnusedSquares
    if (numAllocated == 0) {
      return AllocationPlan(
          availablePermanent = emptyList(),
          isShortfall = numConfigured > 0,
          numAllocated = 0,
          numConfigured = numConfigured,
          numPermanent = 0,
      )
    }

    if (numAllocated >= numConfigured) {
      return AllocationPlan(
          availablePermanent = existingPermanent,
          isShortfall = false,
          numAllocated = numAllocated,
          numConfigured = numConfigured,
          numPermanent = existingPermanent.size,
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
      if (targetStratum.substrata.none { demotedPlot.boundary.nearlyCoveredBy(it.boundary) }) {
        numAllocated--
      }
    }

    return AllocationPlan(
        availablePermanent = availablePermanent,
        isShortfall = true,
        numAllocated = numAllocated,
        numConfigured = numConfigured,
        numPermanent = numPermanent,
    )
  }

  private fun withSimulatedPermanentPlots(): ExistingStratumModel {
    val numPermanentPlots = stratum.plotCounts(observationType).numPermanentPlots
    val missingIndexes = (1..numPermanentPlots).filterNot { stratum.permanentIndexExists(it) }
    if (missingIndexes.isEmpty()) {
      return stratum
    }

    val placement =
        stratum.placePermanentPlots(
            gridOrigin = gridOrigin,
            exclusion = exclusion,
            requests = listOf(PermanentPlotPlacementRequest(stratum.boundary, missingIndexes)),
        )
    if (placement.plots.isEmpty()) {
      return stratum
    }

    val promotedByPlotId = mutableMapOf<MonitoringPlotId, Int>()
    val addedBySubstratumId = mutableMapOf<SubstratumId, MutableList<MonitoringPlotModel>>()

    placement.plots.forEach { (plotBoundary, permanentIndex) ->
      val existingPlot = stratum.findMonitoringPlot(plotBoundary)
      if (existingPlot != null && existingPlot.permanentIndex == null) {
        promotedByPlotId[existingPlot.id] = permanentIndex
      } else if (existingPlot == null) {
        val substratum = stratum.findSubstratum(plotBoundary) ?: return@forEach
        addedBySubstratumId
            .getOrPut(substratum.id) { mutableListOf() }
            .add(
                MonitoringPlotModel(
                    boundary = plotBoundary,
                    elevationMeters = null,
                    id = MonitoringPlotId(-permanentIndex.toLong()),
                    isAdHoc = false,
                    isAvailable = true,
                    permanentIndex = permanentIndex,
                    plotNumber = -permanentIndex.toLong(),
                    sizeMeters = MONITORING_PLOT_SIZE_INT,
                )
            )
      }
    }

    return stratum.copy(
        substrata =
            stratum.substrata.map { substratum ->
              val updatedPlots =
                  substratum.monitoringPlots.map { plot ->
                    val newIndex = promotedByPlotId[plot.id]
                    if (newIndex != null) plot.copy(permanentIndex = newIndex) else plot
                  } + addedBySubstratumId[substratum.id].orEmpty()
              substratum.copy(monitoringPlots = updatedPlots)
            }
    )
  }

  private data class AllocationPlan(
      val availablePermanent: List<MonitoringPlotModel>,
      val isShortfall: Boolean,
      val numAllocated: Int,
      val numConfigured: Int,
      val numPermanent: Int,
  )

  /** The monitoring plots chosen for a stratum in an observation. */
  data class Allocation(
      val permanentPlotIds: Set<MonitoringPlotId>,
      val temporaryPlotBoundaries: Collection<Polygon>,
      /**
       * Number of monitoring plots configured for the stratum. This is the sum of
       * [StratumPlotCounts.numPermanentPlots] and [StratumPlotCounts.numTemporaryPlots].
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

data class PlaceablePlotCounts(val numPermanentPlots: Int, val numTemporaryPlots: Int)
