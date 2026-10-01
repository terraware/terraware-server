package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.tables.ObservationPlots
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.tracking.util.ObservationResultsScope
import com.terraformation.backend.tracking.util.ObservationSpeciesScope
import com.terraformation.backend.util.HECTARES_PER_PLOT
import java.math.BigDecimal
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

internal fun rollup(field: Field<Int?>): Field<Int> =
    DSL.coalesce(DSL.sum(field).cast(SQLDataType.INTEGER), 0)

/**
 * The rows that feed a survival rate calculation: one row per (monitoring plot, species) that has
 * t0 data and belongs to a scope, joined to the plot's per-species live plant count from the
 * observation the plot is attributed to. The numerator and denominator of a survival rate are both
 * aggregated from the same set so a plot without t0 data contributes to neither.
 */
internal class T0PlotSet(
    val table: Table<*>,
    val condition: Condition,
    val speciesIdField: Field<SpeciesId?>,
    val densityField: Field<BigDecimal?>,
    val liveField: Field<Int?>,
) {
  /** Sum of t0 densities across the set, or SQL null if the set is empty. */
  val denominator: Field<BigDecimal?> =
      DSL.field(
          DSL.select(DSL.sum(densityField).mul(DSL.inline(HECTARES_PER_PLOT)))
              .from(table)
              .where(condition)
      )

  /** Sum of live plants across the set, or SQL null if the set is empty. */
  val numerator: Field<Int?> =
      DSL.field(
          DSL.select(DSL.sum(liveField).cast(SQLDataType.INTEGER)).from(table).where(condition)
      )
}

/**
 * Returns the t0 plot set for the permanent plots in [updateScope], restricted to [speciesIdField]
 * if it is non-null. [plotObservationCondition] selects, for each plot, the observation whose live
 * plant counts should be used.
 */
internal fun <ID : Any, HistoryId : Any> permanentT0PlotSet(
    updateScope: ObservationSpeciesScope<ID, HistoryId>,
    speciesIdField: Field<SpeciesId?>?,
    plotObservationCondition: (ObservationPlots) -> Condition,
): T0PlotSet {
  val opPerm = OBSERVATION_PLOTS.`as`("opPerm")
  val permLiveTotals = OBSERVED_PLOT_SPECIES_TOTALS.`as`("permLiveTotals")

  val table =
      PLOT_T0_DENSITIES.join(opPerm)
          .on(opPerm.MONITORING_PLOT_ID.eq(PLOT_T0_DENSITIES.MONITORING_PLOT_ID))
          .leftJoin(permLiveTotals)
          .on(
              permLiveTotals.OBSERVATION_ID.eq(opPerm.OBSERVATION_ID),
              permLiveTotals.MONITORING_PLOT_ID.eq(PLOT_T0_DENSITIES.MONITORING_PLOT_ID),
              permLiveTotals.SPECIES_ID.eq(PLOT_T0_DENSITIES.SPECIES_ID),
          )

  val plotSetCondition =
      DSL.and(
          updateScope.t0DensityCondition(opPerm),
          speciesIdField?.let { PLOT_T0_DENSITIES.SPECIES_ID.eq(it) } ?: DSL.trueCondition(),
          plotHasCompletedObservations(
              PLOT_T0_DENSITIES.MONITORING_PLOT_ID,
              true,
              updateScope.alternateCompletedCondition(PLOT_T0_DENSITIES.MONITORING_PLOT_ID),
          ),
          opPerm.IS_PERMANENT.isTrue,
          plotObservationCondition(opPerm),
      )

  return T0PlotSet(
      table,
      plotSetCondition,
      PLOT_T0_DENSITIES.SPECIES_ID,
      PLOT_T0_DENSITIES.PLOT_DENSITY,
      permLiveTotals.TOTAL_LIVE,
  )
}

/**
 * Returns the t0 plot set for the temporary plots in [updateScope], restricted to [speciesIdField]
 * if it is non-null. Temporary plots take their t0 density from their stratum and only count if the
 * planting site includes temporary plots in survival rates. [plotObservationCondition] selects, for
 * each plot, the observation whose live plant counts should be used.
 */
internal fun <ID : Any, HistoryId : Any> tempT0PlotSet(
    updateScope: ObservationSpeciesScope<ID, HistoryId>,
    speciesIdField: Field<SpeciesId?>?,
    plotObservationCondition: (ObservationPlots) -> Condition,
): T0PlotSet {
  val opTemp = OBSERVATION_PLOTS.`as`("opTemp")
  val tempLiveTotals = OBSERVED_PLOT_SPECIES_TOTALS.`as`("tempLiveTotals")

  return with(STRATUM_T0_TEMP_DENSITIES) {
    val table =
        STRATUM_T0_TEMP_DENSITIES.join(opTemp)
            .on(
                opTemp.monitoringPlotHistories.substratumHistories.stratumHistories.STRATUM_ID.eq(
                    STRATUM_ID
                )
            )
            .leftJoin(tempLiveTotals)
            .on(
                tempLiveTotals.OBSERVATION_ID.eq(opTemp.OBSERVATION_ID),
                tempLiveTotals.MONITORING_PLOT_ID.eq(opTemp.MONITORING_PLOT_ID),
                tempLiveTotals.SPECIES_ID.eq(SPECIES_ID),
            )

    val plotSetCondition =
        DSL.and(
            speciesIdField?.let { SPECIES_ID.eq(it) } ?: DSL.trueCondition(),
            updateScope.tempStratumCondition(opTemp),
            strata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true),
            plotHasCompletedObservations(
                opTemp.MONITORING_PLOT_ID,
                false,
                updateScope.alternateCompletedCondition(opTemp.MONITORING_PLOT_ID),
            ),
            opTemp.IS_PERMANENT.isFalse,
            opTemp.monitoringPlots.IS_AD_HOC.isFalse,
            plotObservationCondition(opTemp),
        )

    T0PlotSet(table, plotSetCondition, SPECIES_ID, STRATUM_DENSITY, tempLiveTotals.TOTAL_LIVE)
  }
}

/**
 * Attributes each plot to its substratum's latest observation at or before [observationIdField],
 * exactly as the rolled-up live totals do. A substratum observed in this observation resolves to
 * the observation itself; one it did not observe resolves to the rolled-forward source. Requires
 * the observation's substratum dependencies to have been recorded.
 */
internal fun latestObservationForPlotCondition(
    observationIdField: Field<ObservationId?>
): (ObservationPlots) -> Condition = { observationPlots ->
  observationPlots.OBSERVATION_ID.eq(
      latestObservationForSubstratumField(
          observationIdField,
          observationPlots.monitoringPlotHistories.SUBSTRATUM_ID,
      )
  )
}

/**
 * Attributes each plot to an observation based on which substrata [observationIdField] requested,
 * for use while a plot is being completed and the observation's substratum dependencies haven't
 * been recorded yet.
 */
internal fun requestedObservationForPlotCondition(
    observationIdField: Field<ObservationId?>,
    isPermanent: Boolean,
): (ObservationPlots) -> Condition = { observationPlots ->
  observationPlots.OBSERVATION_ID.eq(
      observationIdForPlot(observationPlots.MONITORING_PLOT_ID, observationIdField, isPermanent)
  )
}

internal fun plotHasCompletedObservations(
    monitoringPlotIdField: Field<MonitoringPlotId?>,
    isPermanent: Boolean,
    alternateCompleteCondition: Condition = DSL.falseCondition(),
): Condition =
    DSL.exists(
        DSL.selectOne()
            .from(
                DSL.select(OBSERVATION_PLOTS.IS_PERMANENT)
                    .from(OBSERVATION_PLOTS)
                    .where(
                        OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotIdField)
                            .and(
                                OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull.or(
                                    alternateCompleteCondition
                                )
                            )
                    )
                    .orderBy(
                        OBSERVATION_PLOTS.COMPLETED_TIME.desc().nullsFirst(),
                        OBSERVATION_PLOTS.OBSERVATION_ID.desc(),
                    )
                    .limit(1)
                    .asTable("most_recent")
            )
            .where(DSL.field("most_recent.IS_PERMANENT", Boolean::class.java).eq(isPermanent))
    )

/**
 * SQL expressions for the terms of a scope's survival rate, combining the permanent and temporary
 * t0 plot sets. The numerator and denominator are aggregated over the same plots, so a plot without
 * t0 data contributes to neither.
 */
internal class SurvivalRateTermFields(permanentPlots: T0PlotSet, tempPlots: T0PlotSet) {
  /** Live plants in plots that have t0 data. Zero rather than null when there are none. */
  val numerator: Field<Int> =
      DSL.coalesce(permanentPlots.numerator, 0)
          .plus(DSL.coalesce(tempPlots.numerator, 0))
          .coerce(SQLDataType.INTEGER)

  /** Total t0 density, or SQL null if no plot has t0 data. */
  val denominatorOrNull: Field<BigDecimal?> =
      DSL.coalesce(
          permanentPlots.denominator.plus(tempPlots.denominator),
          permanentPlots.denominator,
          tempPlots.denominator,
      )
}

/** Returns the survival-rate expression: null if [denominator] is null, 0 if it is zero. */
internal fun getSurvivalRate(numerator: Field<Int>, denominator: Field<BigDecimal?>): Field<Int> =
    DSL.if_(
        denominator.eq(BigDecimal.ZERO),
        DSL.zero(),
        numerator.mul(100).div(denominator),
    )

/**
 * Returns the survival rate terms for a scope, attributing each plot to its substratum's latest
 * observation at or before [observationIdField]. [speciesIdField] restricts the terms to a single
 * species; if null, every species with t0 data is included.
 */
internal fun <ID : Any, HistoryId : Any> getSurvivalRateTerms(
    updateScope: ObservationSpeciesScope<ID, HistoryId>,
    observationIdField: Field<ObservationId?>,
    speciesIdField: Field<SpeciesId?>? = null,
): SurvivalRateTermFields {
  val plotObservationCondition = latestObservationForPlotCondition(observationIdField)

  return SurvivalRateTermFields(
      permanentT0PlotSet(updateScope, speciesIdField, plotObservationCondition),
      tempT0PlotSet(updateScope, speciesIdField, plotObservationCondition),
  )
}

/**
 * Computes the variance of survival rates weighted by planting density
 * https://en.wikipedia.org/wiki/Reduced_chi-squared_statistic
 */
internal fun <ID : Any, HistoryId : Any> getSurvivalRateWeightedStandardDeviation(
    updateScope: ObservationResultsScope<ID, HistoryId>,
    observationIdField: Field<ObservationId?>,
): Field<Int> {
  val plotResults = OBSERVATION_PLOT_RESULTS.`as`("plotResults")
  val survivalRate = plotResults.SURVIVAL_RATE.cast(SQLDataType.NUMERIC)
  val weight = plotResults.PLANT_DENSITY.cast(SQLDataType.NUMERIC)
  val weightedSumOfSquares = DSL.sum(weight * survivalRate * survivalRate)
  val weightedSum = DSL.sum(weight * survivalRate)
  val weightedSumSquared = weightedSum * weightedSum
  val totalWeight = DSL.sum(weight)
  val totalWeightSquared = totalWeight * totalWeight
  val variance = ((weightedSumOfSquares * totalWeight) - weightedSumSquared).div(totalWeightSquared)
  val standardDeviation = DSL.cast(DSL.sqrt(variance), SQLDataType.INTEGER)

  return DSL.field(
      DSL.select(
              DSL.if_(
                  totalWeight.eq(BigDecimal.ZERO),
                  DSL.castNull(SQLDataType.INTEGER),
                  standardDeviation,
              )
          )
          .from(plotResults)
          .where(updateScope.latestPlotResultsCondition(plotResults, observationIdField))
          .and(
              DSL.or(
                  updateScope.observedTotalsPlantingSiteTempCondition,
                  plotResults.observationPlots.IS_PERMANENT.isTrue,
              )
          )
          .and(plotResults.SURVIVAL_RATE.isNotNull)
  )
}
