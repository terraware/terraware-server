package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationId

data class ObservationStratumVariabilityModel(
    val observationId: ObservationId,
    val numPlots: Int,
    val measuredVariability: Double?,
    val plausibleRange: PlausibleVariabilityRange?,
    val currentPlotCount: Long?,
    val plotCountRange: ObservationPlotCountRange?,
    val recommendation: ObservationPlotCountRecommendation?,
) {
  val hasSmallSample: Boolean
    get() = numPlots < MIN_VARIABILITY_PLOTS
}

data class ObservationPlotCountRange(val low: Long, val best: Long, val high: Long)

data class ObservationPlotCountRecommendation(
    val type: PlotCountRecommendationType,
    val plotCount: Long,
)
