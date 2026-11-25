package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId

/** The result of a request to replace a monitoring plot in an observation. */
data class ReplacementResult(
    val addedMonitoringPlotIds: Set<MonitoringPlotId>,
    val removedMonitoringPlotIds: Set<MonitoringPlotId>,
    /**
     * Other upcoming observations that included a plot that was replaced long-term, and that were
     * given new plots as a result.
     */
    val reassignedObservationIds: Set<ObservationId> = emptySet(),
    /**
     * Other upcoming observations that included a plot that was replaced long-term, and that were
     * deleted because they couldn't be given new plots.
     */
    val deletedObservationIds: Set<ObservationId> = emptySet(),
    /**
     * True if the observation whose plot was replaced was deleted because the replacement left it
     * without any plots.
     */
    val isObservationDeleted: Boolean = false,
    /**
     * True if the observation whose plot was replaced was completed because the replacement left it
     * with only completed plots.
     */
    val isObservationCompleted: Boolean = false,
) {
  companion object {
    fun merge(results: Collection<ReplacementResult>): ReplacementResult {
      return ReplacementResult(
          results.flatMap { it.addedMonitoringPlotIds }.toSet(),
          results.flatMap { it.removedMonitoringPlotIds }.toSet(),
          results.flatMap { it.reassignedObservationIds }.toSet(),
          results.flatMap { it.deletedObservationIds }.toSet(),
          results.any { it.isObservationDeleted },
          results.any { it.isObservationCompleted },
      )
    }
  }
}
