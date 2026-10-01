package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.tracking.util.ObservationResultsPlotRow
import com.terraformation.backend.tracking.util.ObservationResultsScope
import com.terraformation.backend.tracking.util.ObservationSpeciesPlotRow
import com.terraformation.backend.util.SQUARE_METERS_PER_HECTARE
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * Recalculates observation results that have been flagged for recalculation, along with the species
 * totals they're derived from.
 */
@Named
class ObservationRecalculationStore(private val dslContext: DSLContext) {
  /**
   * Recalculates a planting site's observation results rows that are flagged for recalculation,
   * along with the species totals rows for the same observations and scopes. Plot-level species
   * totals counts are treated as the source of truth, since edits to species counts are applied to
   * them directly.
   *
   * Works one level at a time, from plots up to the planting site, so each level only reads levels
   * that have already been recalculated. Each step is a single statement covering every flagged row
   * at that level. Does not clear the flags.
   *
   * Does not lock anything; the caller is responsible for serializing recalculations of a planting
   * site.
   */
  fun recalculateFlaggedResults(plantingSiteId: PlantingSiteId) {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    recalculateFlaggedPlotResults(plantingSiteId)
  }

  private fun recalculateFlaggedPlotResults(plantingSiteId: PlantingSiteId) {
    val flaggedPlotSpecies =
        DSL.exists(
            DSL.selectOne()
                .from(OBSERVATION_PLOT_RESULTS)
                .where(
                    OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(
                        OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID
                    )
                )
                .and(
                    OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.eq(
                        OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID
                    )
                )
                .and(OBSERVATION_PLOT_RESULTS.NEEDS_RECALCULATION.eq(true))
        )

    with(OBSERVED_PLOT_SPECIES_TOTALS) {
      val terms = getSurvivalRateTerms(ObservationSpeciesPlotRow, OBSERVATION_ID, SPECIES_ID)

      dslContext
          .update(OBSERVED_PLOT_SPECIES_TOTALS)
          .set(
              SURVIVAL_RATE,
              getSurvivalRate(terms.numerator, terms.denominatorOrNull),
          )
          .where(flaggedPlotSpecies)
          .and(siteObservationCondition(OBSERVATION_ID, plantingSiteId))
          .execute()
    }

    with(OBSERVATION_PLOT_RESULTS) {
      val totals = OBSERVED_PLOT_SPECIES_TOTALS.`as`("plot_rollup")
      fun sumOfTotals(field: Field<Int?>): Field<Int> =
          DSL.field(
              DSL.select(rollup(field))
                  .from(totals)
                  .where(totals.OBSERVATION_ID.eq(OBSERVATION_ID))
                  .and(totals.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
          )

      val plantDensity =
          DSL.field(
              DSL.select(
                      DSL.round(
                              sumOfTotals(totals.TOTAL_LIVE)
                                  .cast(SQLDataType.NUMERIC)
                                  .times(DSL.inline(SQUARE_METERS_PER_HECTARE))
                                  .div(
                                      MONITORING_PLOTS.SIZE_METERS.times(
                                              MONITORING_PLOTS.SIZE_METERS
                                          )
                                          .cast(SQLDataType.NUMERIC)
                                  )
                          )
                          .cast(SQLDataType.INTEGER)
                  )
                  .from(MONITORING_PLOTS)
                  .where(MONITORING_PLOTS.ID.eq(MONITORING_PLOT_ID))
          )

      dslContext
          .update(OBSERVATION_PLOT_RESULTS)
          .set(TOTAL_LIVE, sumOfTotals(totals.TOTAL_LIVE))
          .set(TOTAL_DEAD, sumOfTotals(totals.TOTAL_DEAD))
          .set(TOTAL_EXISTING, sumOfTotals(totals.TOTAL_EXISTING))
          .set(PERMANENT_LIVE, sumOfTotals(totals.PERMANENT_LIVE))
          .set(PLANT_DENSITY, plantDensity)
          .where(NEEDS_RECALCULATION.eq(true))
          .and(siteObservationCondition(OBSERVATION_ID, plantingSiteId))
          .execute()
    }

    recalculateFlaggedResultsRates(ObservationResultsPlotRow, plantingSiteId)
  }

  /**
   * Recalculates the survival rates, and where the table has them the survival rate standard
   * deviations and areas, of flagged results rows. Rates are only calculated for scopes whose plots
   * in the observation are all completed.
   */
  private fun <ID : Any, HistoryId : Any> recalculateFlaggedResultsRates(
      scope: ObservationResultsScope<ID, HistoryId>,
      plantingSiteId: PlantingSiteId,
  ) {
    val table = scope.observedTotalsTable
    val observationIdField = table.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val needsRecalculationField = table.field("needs_recalculation", Boolean::class.java)!!
    val survivalRateField = table.field("survival_rate", Int::class.java)!!
    val survivalRateStdDevField = table.field("survival_rate_std_dev", Int::class.java)
    val survivalRateAreaField = table.field("survival_rate_area", BigDecimal::class.java)

    val terms = getSurvivalRateTerms(scope, observationIdField)
    val recalculationCondition =
        DSL.and(
            needsRecalculationField.eq(true),
            siteObservationCondition(observationIdField, plantingSiteId),
            nonAdHocObservationCondition(observationIdField),
            DSL.notExists(
                DSL.selectOne()
                    .from(OBSERVATION_PLOTS)
                    .where(scope.observationPlotsCondition(observationIdField))
                    .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNull)
                    .and(OBSERVATION_PLOTS.STATUS_ID.ne(ObservationPlotStatus.NotObserved))
            ),
        )

    dslContext
        .update(table)
        .set(
            survivalRateField,
            scope.survivalRateValue(observationIdField, terms.numerator, terms.denominatorOrNull),
        )
        .where(recalculationCondition)
        .execute()

    // Std dev and area depend on the survival rate computed above, so they're set in a separate
    // statement that reads the stored value.
    if (survivalRateStdDevField != null && survivalRateAreaField != null) {
      dslContext
          .update(table)
          .set(
              survivalRateStdDevField,
              DSL.if_(
                  survivalRateField.isNotNull,
                  getSurvivalRateWeightedStandardDeviation(scope, observationIdField),
                  DSL.castNull(SQLDataType.INTEGER),
              ),
          )
          .set(
              survivalRateAreaField,
              DSL.if_(
                  survivalRateField.isNotNull,
                  scope.survivalRateAreaValue(observationIdField),
                  DSL.castNull(SQLDataType.NUMERIC),
              ),
          )
          .where(recalculationCondition)
          .execute()
    }
  }

  /** Condition on a results table joined by observation ID that its row is flagged. */
  private fun siteObservationCondition(
      observationIdField: Field<ObservationId?>,
      plantingSiteId: PlantingSiteId,
  ): Condition =
      observationIdField.`in`(
          DSL.select(OBSERVATIONS.ID)
              .from(OBSERVATIONS)
              .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
      )

  private fun nonAdHocObservationCondition(observationIdField: Field<ObservationId?>): Condition =
      observationIdField.`in`(
          DSL.select(OBSERVATIONS.ID).from(OBSERVATIONS).where(OBSERVATIONS.IS_AD_HOC.isFalse)
      )
}
