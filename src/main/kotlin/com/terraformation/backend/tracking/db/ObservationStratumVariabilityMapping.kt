package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.tables.records.ObservationStratumVariabilityRecord
import com.terraformation.backend.tracking.model.ObservationPlotCountRange
import com.terraformation.backend.tracking.model.ObservationPlotCountRecommendation
import com.terraformation.backend.tracking.model.ObservationStratumVariabilityModel
import com.terraformation.backend.tracking.model.PlausibleVariabilityRange
import com.terraformation.backend.tracking.model.PlotCountRecommendationType

internal fun ObservationStratumVariabilityRecord.toModel(): ObservationStratumVariabilityModel {
  val comparisonCount = configuredPlotCountAtCompletion
  val recommendation = recommendedPlotCount?.let {
    val type =
        if (it > comparisonCount!!) PlotCountRecommendationType.Increase
        else PlotCountRecommendationType.Decrease
    ObservationPlotCountRecommendation(type, it)
  }
  return ObservationStratumVariabilityModel(
      observationId = observationId!!,
      sampledPlotCount = sampledPlotCount!!,
      measuredVariability = measuredVariability,
      plausibleVariabilityRange =
          plausibleVariabilityLow?.let {
            PlausibleVariabilityRange(it, plausibleVariabilityHigh!!)
          },
      configuredPlotCountAtCompletion = comparisonCount,
      estimatedPlotCountRange =
          estimatedPlotCountLow?.let {
            ObservationPlotCountRange(it, estimatedPlotCountBest!!, estimatedPlotCountHigh!!)
          },
      recommendation = recommendation,
  )
}
