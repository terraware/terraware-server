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
) {
  companion object {
    fun merge(results: Collection<ReplacementResult>): ReplacementResult {
      return ReplacementResult(
          results.flatMap { it.addedMonitoringPlotIds }.toSet(),
          results.flatMap { it.removedMonitoringPlotIds }.toSet(),
          results.flatMap { it.reassignedObservationIds }.toSet(),
          results.flatMap { it.deletedObservationIds }.toSet(),
      )
    }
  }
}
