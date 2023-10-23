package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId

/** Result IDs of a request to replace a monitoring plot in an observation. */
data class ReplacementResultIds(
    val addedMonitoringPlotIds: Set<MonitoringPlotId>,
    val removedMonitoringPlotIds: Set<MonitoringPlotId>,
)

/** A monitoring plot in the replacement result */
data class ReplacementResultPlot(
    val monitoringPlotId: MonitoringPlotId,
    val monitoringPlotName: String,
)

/** The result of a request to replace a monitoring plot in an observation. */
data class ReplacementResult(
    val addedMonitoringPlots: List<ReplacementResultPlot>,
    val removedMonitoringPlots: List<ReplacementResultPlot>,
)
