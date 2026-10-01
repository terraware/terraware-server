package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.StratumHistories
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.util.ObservationResultsPlotRow
import com.terraformation.backend.tracking.util.ObservationResultsScope
import com.terraformation.backend.tracking.util.ObservationResultsSite
import com.terraformation.backend.tracking.util.ObservationResultsStratum
import com.terraformation.backend.tracking.util.ObservationResultsSubstratum
import com.terraformation.backend.tracking.util.ObservationSpeciesPlotRow
import com.terraformation.backend.tracking.util.ObservationSpeciesScope
import com.terraformation.backend.tracking.util.ObservationSpeciesSite
import com.terraformation.backend.tracking.util.ObservationSpeciesStratum
import com.terraformation.backend.tracking.util.ObservationSpeciesSubstratum
import com.terraformation.backend.util.SQUARE_METERS_PER_HECTARE
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
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
    recalculateFlaggedSubstratumResults(plantingSiteId)
    recalculateFlaggedStratumResults(plantingSiteId)
    recalculateFlaggedSiteResults(plantingSiteId)
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

  private fun recalculateFlaggedSubstratumResults(plantingSiteId: PlantingSiteId) {
    val speciesTotals = OBSERVED_SUBSTRATUM_SPECIES_TOTALS
    val flaggedSpecies =
        flaggedResultsCondition(
            OBSERVATION_SUBSTRATUM_RESULTS,
            speciesTotals.OBSERVATION_ID,
            OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID.eq(
                speciesTotals.SUBSTRATUM_HISTORY_ID
            ),
        )

    dslContext
        .deleteFrom(speciesTotals)
        .where(flaggedSpecies)
        .and(siteObservationCondition(speciesTotals.OBSERVATION_ID, plantingSiteId))
        .execute()

    insertPlotSpeciesRollup(
        plantingSiteId,
        speciesTotals,
        speciesTotals.SUBSTRATUM_ID,
        speciesTotals.SUBSTRATUM_HISTORY_ID,
        { plots -> plots.substratumId },
        { plots -> plots.substratumHistoryId },
        { plots ->
          flaggedResultsCondition(
              OBSERVATION_SUBSTRATUM_RESULTS,
              plots.observationId,
              OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID.eq(plots.substratumHistoryId),
          )
        },
    )

    recalculateFlaggedSpeciesRates(
        ObservationSpeciesSubstratum(DSL.select(speciesTotals.SUBSTRATUM_HISTORY_ID)),
        flaggedSpecies,
        plantingSiteId,
        { plots -> plots.substratumHistoryId.eq(speciesTotals.SUBSTRATUM_HISTORY_ID) },
    )

    with(OBSERVATION_SUBSTRATUM_RESULTS) {
      val scope = ObservationResultsSubstratum(DSL.select(SUBSTRATUM_HISTORY_ID))
      val totals = OBSERVED_SUBSTRATUM_SPECIES_TOTALS.`as`("substratum_rollup")
      fun sumOfTotals(field: Field<Int?>): Field<Int> =
          DSL.field(
              DSL.select(rollup(field))
                  .from(totals)
                  .where(totals.OBSERVATION_ID.eq(OBSERVATION_ID))
                  .and(totals.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORY_ID))
          )

      dslContext
          .update(OBSERVATION_SUBSTRATUM_RESULTS)
          .set(TOTAL_LIVE, sumOfTotals(totals.TOTAL_LIVE))
          .set(TOTAL_DEAD, sumOfTotals(totals.TOTAL_DEAD))
          .set(TOTAL_EXISTING, sumOfTotals(totals.TOTAL_EXISTING))
          .set(PERMANENT_LIVE, sumOfTotals(totals.PERMANENT_LIVE))
          .set(PLANT_DENSITY, scope.latestPlantDensityField(OBSERVATION_ID))
          .set(PLANT_DENSITY_STD_DEV, scope.latestPlantDensityStdDevField(OBSERVATION_ID))
          .where(NEEDS_RECALCULATION.eq(true))
          .and(siteObservationCondition(OBSERVATION_ID, plantingSiteId))
          .and(nonAdHocObservationCondition(OBSERVATION_ID))
          .execute()

      recalculateFlaggedResultsRates(scope, plantingSiteId)
    }
  }

  private fun recalculateFlaggedStratumResults(plantingSiteId: PlantingSiteId) {
    val speciesTotals = OBSERVED_STRATUM_SPECIES_TOTALS
    val flaggedSpecies =
        flaggedResultsCondition(
            OBSERVATION_STRATUM_RESULTS,
            speciesTotals.OBSERVATION_ID,
            OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.eq(speciesTotals.STRATUM_HISTORY_ID),
        )

    dslContext
        .deleteFrom(speciesTotals)
        .where(flaggedSpecies)
        .and(siteObservationCondition(speciesTotals.OBSERVATION_ID, plantingSiteId))
        .execute()

    // Stratum species totals are only read alongside their stratum results, so rows for strata an
    // observation didn't observe are never used. Remove any left over from earlier calculations.
    dslContext
        .deleteFrom(speciesTotals)
        .where(flaggedResultsCondition(OBSERVATION_SITE_RESULTS, speciesTotals.OBSERVATION_ID))
        .and(siteObservationCondition(speciesTotals.OBSERVATION_ID, plantingSiteId))
        .andNotExists(
            DSL.selectOne()
                .from(OBSERVATION_STRATUM_RESULTS)
                .where(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(speciesTotals.OBSERVATION_ID))
                .and(
                    OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.eq(
                        speciesTotals.STRATUM_HISTORY_ID
                    )
                )
        )
        .execute()

    insertPlotSpeciesRollup(
        plantingSiteId,
        speciesTotals,
        speciesTotals.STRATUM_ID,
        speciesTotals.STRATUM_HISTORY_ID,
        { plots -> plots.stratumId },
        { plots -> plots.stratumHistoryId },
        { plots ->
          flaggedResultsCondition(
              OBSERVATION_STRATUM_RESULTS,
              plots.observationId,
              OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.eq(plots.stratumHistoryId),
          )
        },
    )

    rollForwardPermanentLive(
        plantingSiteId,
        speciesTotals,
        speciesTotals.STRATUM_ID,
        speciesTotals.STRATUM_HISTORY_ID,
        { strata -> strata.STRATUM_ID },
        { strata -> strata.ID },
        { observationIdField, strata ->
          flaggedResultsCondition(
              OBSERVATION_STRATUM_RESULTS,
              observationIdField,
              OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.eq(strata.ID),
          )
        },
    )

    recalculateFlaggedSpeciesRates(
        ObservationSpeciesStratum(DSL.select(speciesTotals.STRATUM_HISTORY_ID)),
        flaggedSpecies,
        plantingSiteId,
        { plots -> plots.stratumHistoryId.eq(speciesTotals.STRATUM_HISTORY_ID) },
    )

    with(OBSERVATION_STRATUM_RESULTS) {
      val scope = ObservationResultsStratum(DSL.select(STRATUM_HISTORY_ID))
      val totals = OBSERVED_STRATUM_SPECIES_TOTALS.`as`("stratum_rollup")
      fun sumOfTotals(field: Field<Int?>): Field<Int> =
          DSL.field(
              DSL.select(rollup(field))
                  .from(totals)
                  .where(totals.OBSERVATION_ID.eq(OBSERVATION_ID))
                  .and(totals.STRATUM_HISTORY_ID.eq(STRATUM_HISTORY_ID))
          )

      dslContext
          .update(OBSERVATION_STRATUM_RESULTS)
          .set(TOTAL_LIVE, sumOfTotals(totals.TOTAL_LIVE))
          .set(TOTAL_DEAD, sumOfTotals(totals.TOTAL_DEAD))
          .set(TOTAL_EXISTING, sumOfTotals(totals.TOTAL_EXISTING))
          .set(PERMANENT_LIVE, sumOfTotals(totals.PERMANENT_LIVE))
          .set(PLANT_DENSITY, scope.latestPlantDensityField(OBSERVATION_ID))
          .set(PLANT_DENSITY_STD_DEV, scope.latestPlantDensityStdDevField(OBSERVATION_ID))
          .set(OBSERVED_DENSITY, scope.observedPlantDensityField(OBSERVATION_ID))
          .where(NEEDS_RECALCULATION.eq(true))
          .and(siteObservationCondition(OBSERVATION_ID, plantingSiteId))
          .and(nonAdHocObservationCondition(OBSERVATION_ID))
          .execute()

      recalculateFlaggedResultsRates(scope, plantingSiteId)
    }
  }

  private fun recalculateFlaggedSiteResults(plantingSiteId: PlantingSiteId) {
    val speciesTotals = OBSERVED_SITE_SPECIES_TOTALS
    val flaggedSpecies =
        flaggedResultsCondition(OBSERVATION_SITE_RESULTS, speciesTotals.OBSERVATION_ID)

    dslContext
        .deleteFrom(speciesTotals)
        .where(flaggedSpecies)
        .and(siteObservationCondition(speciesTotals.OBSERVATION_ID, plantingSiteId))
        .execute()

    insertPlotSpeciesRollup(
        plantingSiteId,
        speciesTotals,
        speciesTotals.PLANTING_SITE_ID,
        speciesTotals.PLANTING_SITE_HISTORY_ID,
        { plots -> plots.plantingSiteId },
        { plots -> plots.plantingSiteHistoryId },
        { plots -> flaggedResultsCondition(OBSERVATION_SITE_RESULTS, plots.observationId) },
    )

    rollForwardPermanentLive(
        plantingSiteId,
        speciesTotals,
        speciesTotals.PLANTING_SITE_ID,
        speciesTotals.PLANTING_SITE_HISTORY_ID,
        { _ -> OBSERVATIONS.PLANTING_SITE_ID },
        { _ -> OBSERVATIONS.PLANTING_SITE_HISTORY_ID },
        { observationIdField, _ ->
          flaggedResultsCondition(OBSERVATION_SITE_RESULTS, observationIdField)
        },
    )

    recalculateFlaggedSpeciesRates(
        ObservationSpeciesSite(
            DSL.select(speciesTotals.PLANTING_SITE_ID),
            DSL.select(speciesTotals.PLANTING_SITE_HISTORY_ID),
        ),
        flaggedSpecies,
        plantingSiteId,
        { _ -> DSL.trueCondition() },
    )

    with(OBSERVATION_SITE_RESULTS) {
      val scope =
          ObservationResultsSite(
              DSL.select(PLANTING_SITE_HISTORY_ID),
              DSL.select(PLANTING_SITE_ID),
          )
      val totals = OBSERVED_SITE_SPECIES_TOTALS.`as`("site_rollup")
      fun sumOfTotals(field: Field<Int?>): Field<Int> =
          DSL.field(
              DSL.select(rollup(field))
                  .from(totals)
                  .where(totals.OBSERVATION_ID.eq(OBSERVATION_ID))
                  .and(totals.PLANTING_SITE_ID.eq(PLANTING_SITE_ID))
          )

      // Site density rolls each substratum's plots forward from its latest observation, including
      // plots whose history is from an older version of the site map.
      val densityPlots = OBSERVATION_PLOT_RESULTS.`as`("site_density_plots")
      val densityPlotHistories = MONITORING_PLOT_HISTORIES.`as`("site_density_mph")
      val densitySubstrata = SUBSTRATUM_HISTORIES.`as`("site_density_ssh")
      fun densityField(aggregate: (Field<Int?>) -> Field<*>): Field<Int?> =
          DSL.field(
              DSL.select(aggregate(densityPlots.PLANT_DENSITY).cast(SQLDataType.INTEGER))
                  .from(densityPlots)
                  .join(MONITORING_PLOTS)
                  .on(MONITORING_PLOTS.ID.eq(densityPlots.MONITORING_PLOT_ID))
                  .join(densityPlotHistories)
                  .on(densityPlotHistories.ID.eq(densityPlots.MONITORING_PLOT_HISTORY_ID))
                  .join(densitySubstrata)
                  .on(densitySubstrata.ID.eq(densityPlotHistories.SUBSTRATUM_HISTORY_ID))
                  .where(MONITORING_PLOTS.PLANTING_SITE_ID.eq(PLANTING_SITE_ID))
                  .and(
                      densityPlots.OBSERVATION_ID.eq(
                          latestObservationForSubstratumField(
                              OBSERVATION_ID,
                              densitySubstrata.SUBSTRATUM_ID,
                          )
                      )
                  )
          )

      dslContext
          .update(OBSERVATION_SITE_RESULTS)
          .set(TOTAL_LIVE, sumOfTotals(totals.TOTAL_LIVE))
          .set(TOTAL_DEAD, sumOfTotals(totals.TOTAL_DEAD))
          .set(TOTAL_EXISTING, sumOfTotals(totals.TOTAL_EXISTING))
          .set(PERMANENT_LIVE, sumOfTotals(totals.PERMANENT_LIVE))
          .set(PLANT_DENSITY, densityField { DSL.avg(it) })
          .set(PLANT_DENSITY_STD_DEV, densityField { DSL.stddevSamp(it) })
          .set(OBSERVED_DENSITY, scope.observedPlantDensityField(OBSERVATION_ID))
          .where(NEEDS_RECALCULATION.eq(true))
          .and(siteObservationCondition(OBSERVATION_ID, plantingSiteId))
          .and(nonAdHocObservationCondition(OBSERVATION_ID))
          .execute()

      recalculateFlaggedResultsRates(scope, plantingSiteId)
    }
  }

  /**
   * The completed plots of a planting site's observations joined with their plot-level species
   * totals and the substratum, stratum, and site versions the plots belonged to at the time of the
   * observation.
   */
  private class CompletedPlotSpecies {
    val observationPlots = OBSERVATION_PLOTS.`as`("rollup_op")
    val plotSpecies = OBSERVED_PLOT_SPECIES_TOTALS.`as`("rollup_pst")
    val plotHistories = MONITORING_PLOT_HISTORIES.`as`("rollup_mph")
    val substratumHistories = SUBSTRATUM_HISTORIES.`as`("rollup_ssh")
    val stratumHistories = STRATUM_HISTORIES.`as`("rollup_sh")
    val observations = OBSERVATIONS.`as`("rollup_obs")

    val observationId = observationPlots.OBSERVATION_ID
    val substratumId = substratumHistories.SUBSTRATUM_ID
    val substratumHistoryId = substratumHistories.ID
    val stratumId = stratumHistories.STRATUM_ID
    val stratumHistoryId = stratumHistories.ID
    val plantingSiteId = observations.PLANTING_SITE_ID
    val plantingSiteHistoryId = observations.PLANTING_SITE_HISTORY_ID

    val table =
        plotSpecies
            .join(observationPlots)
            .on(
                observationPlots.OBSERVATION_ID.eq(plotSpecies.OBSERVATION_ID),
                observationPlots.MONITORING_PLOT_ID.eq(plotSpecies.MONITORING_PLOT_ID),
            )
            .join(observations)
            .on(observations.ID.eq(observationPlots.OBSERVATION_ID))
            .join(plotHistories)
            .on(plotHistories.ID.eq(observationPlots.MONITORING_PLOT_HISTORY_ID))
            .join(substratumHistories)
            .on(substratumHistories.ID.eq(plotHistories.SUBSTRATUM_HISTORY_ID))
            .join(stratumHistories)
            .on(stratumHistories.ID.eq(substratumHistories.STRATUM_HISTORY_ID))

    fun condition(plantingSiteId: PlantingSiteId): Condition =
        DSL.and(
            observations.PLANTING_SITE_ID.eq(plantingSiteId),
            observations.IS_AD_HOC.isFalse,
            observationPlots.STATUS_ID.eq(ObservationPlotStatus.Completed),
        )
  }

  /**
   * Inserts species totals rows for flagged scopes by adding up the plot-level species totals of
   * the scopes' completed plots in the same observation.
   */
  private fun insertPlotSpeciesRollup(
      plantingSiteId: PlantingSiteId,
      table: Table<out Record>,
      scopeIdField: Field<*>,
      scopeHistoryIdField: Field<*>,
      plotsScopeId: (CompletedPlotSpecies) -> Field<*>,
      plotsScopeHistoryId: (CompletedPlotSpecies) -> Field<*>,
      flaggedCondition: (CompletedPlotSpecies) -> Condition,
  ) {
    val plots = CompletedPlotSpecies()
    val plotSpecies = plots.plotSpecies

    dslContext
        .insertInto(table)
        .columns(
            listOf(
                table.field("observation_id")!!,
                scopeIdField,
                scopeHistoryIdField,
                table.field("certainty_id")!!,
                table.field("species_id")!!,
                table.field("species_name")!!,
                table.field("total_live")!!,
                table.field("total_dead")!!,
                table.field("total_existing")!!,
                table.field("permanent_live")!!,
            )
        )
        .select(
            DSL.select(
                    plots.observationId,
                    plotsScopeId(plots),
                    plotsScopeHistoryId(plots),
                    plotSpecies.CERTAINTY_ID,
                    plotSpecies.SPECIES_ID,
                    plotSpecies.SPECIES_NAME,
                    rollup(plotSpecies.TOTAL_LIVE),
                    rollup(plotSpecies.TOTAL_DEAD),
                    rollup(plotSpecies.TOTAL_EXISTING),
                    rollup(
                        DSL.`when`(
                                plots.observationPlots.IS_PERMANENT.isTrue,
                                plotSpecies.TOTAL_LIVE,
                            )
                            .else_(DSL.inline(0))
                    ),
                )
                .from(plots.table)
                .where(plots.condition(plantingSiteId))
                .and(flaggedCondition(plots))
                .groupBy(
                    plots.observationId,
                    plotsScopeId(plots),
                    plotsScopeHistoryId(plots),
                    plotSpecies.CERTAINTY_ID,
                    plotSpecies.SPECIES_ID,
                    plotSpecies.SPECIES_NAME,
                )
        )
        .execute()
  }

  /**
   * For flagged stratum or site species totals in completed and abandoned observations, replaces
   * the permanent live counts with the sum of the substratum species totals rolled forward from
   * each substratum's latest observation, adding rows for species that only appear in
   * rolled-forward substrata.
   */
  private fun rollForwardPermanentLive(
      plantingSiteId: PlantingSiteId,
      table: Table<out Record>,
      scopeIdField: Field<*>,
      scopeHistoryIdField: Field<*>,
      strataScopeId: (StratumHistories) -> Field<*>,
      strataScopeHistoryId: (StratumHistories) -> Field<*>,
      flaggedCondition: (Field<ObservationId?>, StratumHistories) -> Condition,
  ) {
    val snapshotStrata = STRATUM_HISTORIES.`as`("rf_sh")
    val snapshotSubstrata = SUBSTRATUM_HISTORIES.`as`("rf_ssh")
    val substratumSpecies = OBSERVED_SUBSTRATUM_SPECIES_TOTALS.`as`("rf_sst")

    val rolledForward =
        DSL.select(
                OBSERVATIONS.ID.`as`("rf_observation_id"),
                strataScopeId(snapshotStrata).`as`("rf_scope_id"),
                strataScopeHistoryId(snapshotStrata).`as`("rf_scope_history_id"),
                substratumSpecies.CERTAINTY_ID.`as`("rf_certainty_id"),
                substratumSpecies.SPECIES_ID.`as`("rf_species_id"),
                substratumSpecies.SPECIES_NAME.`as`("rf_species_name"),
                rollup(substratumSpecies.PERMANENT_LIVE).`as`("rf_permanent_live"),
            )
            .from(OBSERVATIONS)
            .join(snapshotStrata)
            .on(snapshotStrata.PLANTING_SITE_HISTORY_ID.eq(OBSERVATIONS.PLANTING_SITE_HISTORY_ID))
            .join(snapshotSubstrata)
            .on(snapshotSubstrata.STRATUM_HISTORY_ID.eq(snapshotStrata.ID))
            .join(substratumSpecies)
            .on(substratumSpecies.SUBSTRATUM_ID.eq(snapshotSubstrata.SUBSTRATUM_ID))
            .where(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
            .and(OBSERVATIONS.IS_AD_HOC.isFalse)
            .and(OBSERVATIONS.STATE_ID.`in`(ObservationState.Completed, ObservationState.Abandoned))
            .and(flaggedCondition(OBSERVATIONS.ID, snapshotStrata))
            .and(
                substratumSpecies.OBSERVATION_ID.eq(
                    latestObservationForSubstratumField(
                        OBSERVATIONS.ID,
                        substratumSpecies.SUBSTRATUM_ID,
                    )
                )
            )
            .groupBy(
                OBSERVATIONS.ID,
                strataScopeId(snapshotStrata),
                strataScopeHistoryId(snapshotStrata),
                substratumSpecies.CERTAINTY_ID,
                substratumSpecies.SPECIES_ID,
                substratumSpecies.SPECIES_NAME,
            )
            .asTable("rolled_forward")

    val rfObservationId = rolledForward.field("rf_observation_id", OBSERVATIONS.ID.dataType)!!
    val rfScopeId = rolledForward.field("rf_scope_id")!!
    val rfScopeHistoryId = rolledForward.field("rf_scope_history_id")!!
    val rfCertaintyId =
        rolledForward.field("rf_certainty_id", OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID.dataType)!!
    val rfSpeciesId = rolledForward.field("rf_species_id", SPECIES.ID.dataType)!!
    val rfSpeciesName = rolledForward.field("rf_species_name", String::class.java)!!
    val rfPermanentLive = rolledForward.field("rf_permanent_live", Int::class.java)!!

    val observationIdField = table.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val certaintyIdField =
        table.field("certainty_id", OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID.dataType)!!
    val speciesIdField = table.field("species_id", SPECIES.ID.dataType)!!
    val speciesNameField = table.field("species_name", String::class.java)!!
    val permanentLiveField = table.field("permanent_live", Int::class.java)!!

    @Suppress("UNCHECKED_CAST")
    val matchesRolledForward =
        DSL.and(
            observationIdField.eq(rfObservationId),
            (scopeHistoryIdField as Field<Any?>).eq(rfScopeHistoryId as Field<Any?>),
            certaintyIdField.eq(rfCertaintyId),
            speciesIdField.isNotDistinctFrom(rfSpeciesId),
            speciesNameField.isNotDistinctFrom(rfSpeciesName),
        )

    dslContext
        .update(table)
        .set(permanentLiveField, rfPermanentLive)
        .from(rolledForward)
        .where(matchesRolledForward)
        .execute()

    dslContext
        .insertInto(table)
        .columns(
            listOf(
                observationIdField,
                scopeIdField,
                scopeHistoryIdField,
                certaintyIdField,
                speciesIdField,
                speciesNameField,
                permanentLiveField,
            )
        )
        .select(
            DSL.select(
                    rfObservationId,
                    rfScopeId,
                    rfScopeHistoryId,
                    rfCertaintyId,
                    rfSpeciesId,
                    rfSpeciesName,
                    rfPermanentLive,
                )
                .from(rolledForward)
                .whereNotExists(DSL.selectOne().from(table).where(matchesRolledForward))
        )
        .execute()
  }

  /**
   * Recalculates the per-species survival rates of flagged species totals rows. In completed and
   * abandoned observations, every species with t0 data gets a rate. In other observations, a
   * species only gets a rate if it was recorded in a permanent plot in the scope or the planting
   * site includes temporary plots in survival rates.
   */
  private fun <ID : Any, HistoryId : Any> recalculateFlaggedSpeciesRates(
      scope: ObservationSpeciesScope<ID, HistoryId>,
      flaggedCondition: Condition,
      plantingSiteId: PlantingSiteId,
      plotInScopeCondition: (CompletedPlotSpecies) -> Condition,
  ) {
    val table = scope.observedTotalsTable
    val observationIdField = table.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val speciesIdField = table.field("species_id", SPECIES.ID.dataType)!!
    val survivalRateField = table.field("survival_rate", Int::class.java)!!

    val terms = getSurvivalRateTerms(scope, observationIdField, speciesIdField)
    val survivalRate = getSurvivalRate(terms.numerator, terms.denominatorOrNull)

    val gatingPlots = CompletedPlotSpecies()
    val rateIsCalculated =
        DSL.or(
            observationIdField.`in`(
                DSL.select(OBSERVATIONS.ID)
                    .from(OBSERVATIONS)
                    .where(
                        OBSERVATIONS.STATE_ID.`in`(
                            ObservationState.Completed,
                            ObservationState.Abandoned,
                        )
                    )
                    .or(
                        OBSERVATIONS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.isTrue.and(
                            speciesIdField.isNotNull
                        )
                    )
            ),
            DSL.exists(
                DSL.selectOne()
                    .from(gatingPlots.table)
                    .where(gatingPlots.observationId.eq(observationIdField))
                    .and(gatingPlots.plotSpecies.SPECIES_ID.eq(speciesIdField))
                    .and(gatingPlots.observationPlots.IS_PERMANENT.isTrue)
                    .and(plotInScopeCondition(gatingPlots))
            ),
        )

    dslContext
        .update(table)
        .set(
            survivalRateField,
            DSL.`when`(rateIsCalculated, survivalRate).else_(DSL.castNull(SQLDataType.INTEGER)),
        )
        .where(flaggedCondition)
        .and(siteObservationCondition(observationIdField, plantingSiteId))
        .execute()
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
  private fun flaggedResultsCondition(
      resultsTable: Table<*>,
      observationIdField: Field<ObservationId?>,
      scopeCondition: Condition = DSL.trueCondition(),
  ): Condition {
    val resultsObservationId = resultsTable.field("observation_id", OBSERVATIONS.ID.dataType)!!
    val needsRecalculation = resultsTable.field("needs_recalculation", Boolean::class.java)!!

    return DSL.exists(
        DSL.selectOne()
            .from(resultsTable)
            .where(resultsObservationId.eq(observationIdField))
            .and(scopeCondition)
            .and(needsRecalculation.eq(true))
    )
  }

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
