package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationId

data class ObservationStratumVariabilityModel(
    val observationId: ObservationId,
    val sampledPlotCount: Int,
    val measuredVariability: Double?,
    val plausibleVariabilityRange: PlausibleVariabilityRange?,
    val configuredPlotCountAtCompletion: Long?,
    val estimatedPlotCountRange: ObservationPlotCountRange?,
    val recommendation: ObservationPlotCountRecommendation?,
) {
  val hasSmallSample: Boolean
    get() = sampledPlotCount < MIN_VARIABILITY_PLOTS
}

data class ObservationPlotCountRange(val low: Long, val best: Long, val high: Long)

data class ObservationPlotCountRecommendation(
    val type: PlotCountRecommendationType,
    val recommendedPlotCount: Long,
)
