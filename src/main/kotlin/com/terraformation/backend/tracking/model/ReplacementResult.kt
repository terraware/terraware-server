package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId

/** The result of a request to replace a monitoring plot in an observation. */
data class ReplacementResult(
    val addedMonitoringPlotIds: Set<MonitoringPlotId>,
    val removedMonitoringPlotIds: Set<MonitoringPlotId>,
) {
  companion object {
    fun merge(results: Collection<ReplacementResult>): ReplacementResult {
      return ReplacementResult(
          results.flatMap { it.addedMonitoringPlotIds }.toSet(),
          results.flatMap { it.removedMonitoringPlotIds }.toSet(),
      )
    }
  }
}
