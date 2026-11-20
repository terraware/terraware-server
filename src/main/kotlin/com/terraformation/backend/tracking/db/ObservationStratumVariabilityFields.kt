package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.tables.records.ObservationStratumVariabilityRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_VARIABILITY
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.tracking.model.ObservationPlotCountRange
import com.terraformation.backend.tracking.model.ObservationPlotCountRecommendation
import com.terraformation.backend.tracking.model.ObservationStratumVariabilityModel
import com.terraformation.backend.tracking.model.PlausibleVariabilityRange
import com.terraformation.backend.tracking.model.PlotCountRecommendationType
import org.jooq.Field
import org.jooq.impl.DSL

private val observationStratumVariabilitySelect =
    with(OBSERVATION_STRATUM_VARIABILITY) {
      DSL.selectFrom(this)
          .where(OBSERVATION_ID.eq(OBSERVATIONS.ID))
          .and(STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID))
    }

internal val observationStratumVariabilityField: Field<ObservationStratumVariabilityModel?> =
    DSL.multiset(observationStratumVariabilitySelect).convertFrom { it.firstOrNull()?.toModel() }

internal val hasObservationStratumVariability = DSL.exists(observationStratumVariabilitySelect)

internal val latestStratumVariabilityField: Field<ObservationStratumVariabilityModel?> =
    with(OBSERVATION_STRATUM_VARIABILITY) {
      DSL.multiset(
              DSL.selectFrom(this)
                  .where(stratumHistories.STRATUM_ID.eq(STRATA.ID))
                  .orderBy(observations.COMPLETED_TIME.desc(), OBSERVATION_ID.desc())
                  .limit(1)
          )
          .convertFrom { it.firstOrNull()?.toModel() }
    }

private fun ObservationStratumVariabilityRecord.toModel(): ObservationStratumVariabilityModel {
  val comparisonCount = currentPlotCount
  val recommendation = recommendedPlotCount?.let {
    val type =
        if (it > comparisonCount!!) PlotCountRecommendationType.Increase
        else PlotCountRecommendationType.Decrease
    ObservationPlotCountRecommendation(type, it)
  }
  return ObservationStratumVariabilityModel(
      observationId = observationId!!,
      numPlots = numPlots!!,
      measuredVariability = measuredVariability,
      plausibleRange =
          plausibleVariabilityLow?.let {
            PlausibleVariabilityRange(it, plausibleVariabilityHigh!!)
          },
      currentPlotCount = comparisonCount,
      plotCountRange =
          plotCountLow?.let { ObservationPlotCountRange(it, plotCountBest!!, plotCountHigh!!) },
      recommendation = recommendation,
  )
}
