package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.tables.records.ObservationStratumVariabilityRecord
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_PLOT_SELECTION_PARAMETERS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.model.MeasuredVariability
import com.terraformation.backend.tracking.model.PlausibleVariabilityRange
import com.terraformation.backend.tracking.model.RecommendedPlotCountRange
import com.terraformation.backend.tracking.model.VariabilityCalculator
import com.terraformation.backend.util.SQUARE_METERS_PER_HECTARE
import java.math.BigDecimal
import java.math.MathContext
import org.jooq.DSLContext
import org.jooq.impl.DSL

internal class ObservationVariabilityStore(private val dslContext: DSLContext) {
  /** Record results while the completing transaction still holds the planting site lock. */
  fun recordVariability(observationId: ObservationId) {
    val parameters = STRATUM_PLOT_SELECTION_PARAMETERS
    val plotStratum = STRATUM_HISTORIES.`as`("plot_stratum")
    val liveCount =
        with(OBSERVED_PLOT_SPECIES_TOTALS) {
          DSL.field(
              DSL.select(DSL.coalesce(DSL.sum(TOTAL_LIVE), DSL.inline(BigDecimal.ZERO)))
                  .from(this)
                  .where(OBSERVATION_ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID))
                  .and(MONITORING_PLOT_ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_ID))
          )
        }

    val plotsByStratum =
        dslContext
            .select(
                STRATUM_HISTORIES.ID,
                OBSERVATION_PLOTS.STATUS_ID,
                MONITORING_PLOTS.SIZE_METERS,
                liveCount,
                parameters.PLOT_SELECTION_MODE_ID,
                parameters.STUDENTS_T,
                parameters.ERROR_MARGIN,
                parameters.NUM_PERMANENT_PLOTS,
                parameters.NUM_TEMPORARY_PLOTS,
            )
            .from(OBSERVATIONS)
            .join(OBSERVATION_PLOTS)
            .on(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
            .join(MONITORING_PLOTS)
            .on(MONITORING_PLOTS.ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_ID))
            .join(MONITORING_PLOT_HISTORIES)
            .on(MONITORING_PLOT_HISTORIES.ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID))
            .join(SUBSTRATUM_HISTORIES)
            .on(SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
            .join(plotStratum)
            .on(plotStratum.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID))
            // Replacement plots can have been added under a newer map.
            .join(STRATUM_HISTORIES)
            .on(
                STRATUM_HISTORIES.STABLE_ID.eq(plotStratum.STABLE_ID),
                STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                    OBSERVATIONS.PLANTING_SITE_HISTORY_ID
                ),
            )
            .leftJoin(parameters)
            .on(
                parameters.STRATUM_ID.eq(STRATUM_HISTORIES.STRATUM_ID),
                parameters.OBSERVATION_TYPE_ID.eq(ObservationType.Monitoring),
            )
            .where(OBSERVATIONS.ID.eq(observationId))
            .and(OBSERVATIONS.STATE_ID.`in`(ObservationState.InProgress, ObservationState.Overdue))
            .and(OBSERVATIONS.IS_AD_HOC.isFalse)
            .and(MONITORING_PLOTS.IS_AD_HOC.isFalse)
            .fetchGroups(STRATUM_HISTORIES.ID)

    val snapshots = plotsByStratum.map { (stratumHistoryId, plots) ->
      val settings = plots.first()
      // Filter after grouping so strata with only skipped plots still get a zero-sample snapshot.
      val densities = plots.mapNotNull { plot ->
        if (plot[OBSERVATION_PLOTS.STATUS_ID] != ObservationPlotStatus.Completed) {
          null
        } else {
          val plotArea = plot[MONITORING_PLOTS.SIZE_METERS]!!.toBigDecimal().pow(2)
          plot[liveCount]!!
              .multiply(SQUARE_METERS_PER_HECTARE.toBigDecimal())
              .divide(plotArea, MathContext.DECIMAL128)
        }
      }

      val currentPlotCount =
          settings[parameters.NUM_PERMANENT_PLOTS]
              ?.toLong()
              ?.plus(settings[parameters.NUM_TEMPORARY_PLOTS]!!)
      val measurement = VariabilityCalculator.measuredVariability(densities)
      val plausibleRange = measurement?.let(VariabilityCalculator::plausibleRange)
      val plotCountRange =
          if (measurement != null && plausibleRange != null) {
            estimatePlotCounts(
                measurement,
                plausibleRange,
                settings[parameters.STUDENTS_T],
                settings[parameters.ERROR_MARGIN],
            )
          } else {
            null
          }
      val recommendation =
          if (
              plotCountRange != null &&
                  currentPlotCount != null &&
                  settings[parameters.PLOT_SELECTION_MODE_ID] != PlotSelectionMode.Direct
          ) {
            VariabilityCalculator.recommendation(currentPlotCount, plotCountRange)
          } else {
            null
          }

      ObservationStratumVariabilityRecord(
          observationId = observationId,
          stratumHistoryId = stratumHistoryId!!,
          numPlots = densities.size,
          measuredVariability = measurement?.variability,
          plausibleVariabilityLow = plausibleRange?.low,
          plausibleVariabilityHigh = plausibleRange?.high,
          currentPlotCount = currentPlotCount,
          plotCountLow = plotCountRange?.low?.plotCounts?.deployableTotal,
          plotCountBest = plotCountRange?.best?.plotCounts?.deployableTotal,
          plotCountHigh = plotCountRange?.high?.plotCounts?.deployableTotal,
          recommendedPlotCount = recommendation?.estimate?.plotCounts?.deployableTotal,
      )
    }
    dslContext.batchInsert(snapshots).execute()
  }

  private fun estimatePlotCounts(
      measurement: MeasuredVariability,
      plausibleRange: PlausibleVariabilityRange,
      studentsT: BigDecimal?,
      errorMargin: BigDecimal?,
  ): RecommendedPlotCountRange? {
    if (
        studentsT == null ||
            studentsT.signum() <= 0 ||
            errorMargin == null ||
            errorMargin.signum() <= 0
    ) {
      return null
    }

    return try {
      VariabilityCalculator.recommendedPlotCountRange(
          measurement,
          plausibleRange,
          studentsT,
          errorMargin,
      )
    } catch (_: ArithmeticException) {
      // Plot count estimates that exceed the supported range cannot be deployed.
      null
    }
  }
}
