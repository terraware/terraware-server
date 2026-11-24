package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.records.ObservationStratumVariabilityRecord

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

  companion object {
    fun of(record: ObservationStratumVariabilityRecord): ObservationStratumVariabilityModel =
        with(record) {
          val recommendation =
              recommendedPlotCount
                  ?.takeIf { recommendationResolutionId == null }
                  ?.let { plotCount ->
                    val type =
                        if (plotCount > configuredPlotCountAtCompletion!!) {
                          PlotCountRecommendationType.Increase
                        } else {
                          PlotCountRecommendationType.Decrease
                        }
                    ObservationPlotCountRecommendation(type, plotCount)
                  }

          ObservationStratumVariabilityModel(
              observationId = observationId!!,
              sampledPlotCount = sampledPlotCount!!,
              measuredVariability = measuredVariability,
              plausibleVariabilityRange =
                  plausibleVariabilityLow?.let {
                    PlausibleVariabilityRange(it, plausibleVariabilityHigh!!)
                  },
              configuredPlotCountAtCompletion = configuredPlotCountAtCompletion,
              estimatedPlotCountRange =
                  estimatedPlotCountLow?.let {
                    ObservationPlotCountRange(
                        it,
                        estimatedPlotCountBest!!,
                        estimatedPlotCountHigh!!,
                    )
                  },
              recommendation = recommendation,
          )
        }
  }
}

data class ObservationPlotCountRange(val low: Long, val best: Long, val high: Long)

data class ObservationPlotCountRecommendation(
    val type: PlotCountRecommendationType,
    val recommendedPlotCount: Long,
)
