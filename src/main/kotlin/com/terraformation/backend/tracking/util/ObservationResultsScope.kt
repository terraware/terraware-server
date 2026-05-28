package com.terraformation.backend.tracking.util

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.ObservationPlotResults
import com.terraformation.backend.db.tracking.tables.ObservationPlots
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
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.db.latestObservationForSubstratumField
import com.terraformation.backend.tracking.db.substratumObservedAtOrBefore
import java.math.BigDecimal
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Select
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

interface ObservationResultsScope<ID : Any, HistoryId : Any> :
    ObservationSpeciesScope<ID, HistoryId> {
  /** Table containing the rolled-up species totals to read from. */
  val rollupSpeciesTable: Table<out Record>

  /** Filter on [rollupSpeciesTable] selecting the rows for this scope. */
  val rollupSpeciesCondition: Condition

  /** Filter on [OBSERVATION_PLOT_RESULTS] selecting the plots that belong to this scope. */
  val plotResultsCondition: Condition

  val latestLiveField: Field<Int>

  /**
   * Condition that covers all result table rows that could be affected by a change to the scoped
   * entity. For ID-based scopes this is the same as [observedTotalsCondition]. For plotId-based
   * scopes it expands to include all observations for every substratum/stratum the plot has ever
   * historically belonged to, ensuring that observations which reference the plot's data via
   * [observationIdForPlot] are also updated.
   */
  val survivalRateRecalculationCondition: Condition

  fun anyChildHasNullSurvivalRateCondition(observationIdField: Field<ObservationId?>): Condition

  fun observationPlotsCondition(observationIdField: Field<ObservationId?>): Condition

  fun observationPlotResultsCondition(plotResultsTable: ObservationPlotResults): Condition

  /**
   * Returns the SQL expression that produces the survival rate (as an integer percentage 0–100) for
   * this scope on a row of [observedTotalsTable], given the observation id field. The default
   * implementation uses a density-weighted formula; the site scope overrides this to return the
   * area-weighted strata average.
   */
  fun survivalRateValue(
      observationIdField: Field<ObservationId?>,
      survivalRateDenominator: Field<BigDecimal>,
      latestLiveField: Field<Int>,
      permanentLiveField: Field<Int>,
  ): Field<Int?> =
      DSL.case_()
          .`when`(
              anyChildHasNullSurvivalRateCondition(observationIdField),
              DSL.castNull(SQLDataType.INTEGER),
          )
          .`when`(
              survivalRateDenominator.eq(BigDecimal.ZERO),
              DSL.castNull(SQLDataType.INTEGER),
          )
          .else_(
              DSL.case_()
                  .`when`(
                      observedTotalsPlantingSiteTempCondition,
                      latestLiveField.mul(BigDecimal.valueOf(100)).div(survivalRateDenominator),
                  )
                  .else_(
                      permanentLiveField.mul(BigDecimal.valueOf(100)).div(survivalRateDenominator),
                  ),
          )
}

class ObservationResultsPlot(
    plotHistorySelect: Select<Record1<MonitoringPlotHistoryId?>>,
    val plotId: MonitoringPlotId,
) : ObservationResultsScope<MonitoringPlotId, MonitoringPlotHistoryId> {
  constructor(
      plotHistoryId: MonitoringPlotHistoryId,
      plotId: MonitoringPlotId,
  ) : this(DSL.select(DSL.inline(plotHistoryId)), plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(MONITORING_PLOT_HISTORIES.ID)
          .from(MONITORING_PLOT_HISTORIES)
          .where(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(plotId)),
      plotId,
  )

  override val scopeId: Select<Record1<MonitoringPlotId?>> = DSL.select(DSL.inline(plotId))

  override val scopeHistoryId = plotHistorySelect

  override val rollupSpeciesTable = OBSERVED_PLOT_SPECIES_TOTALS

  override val rollupSpeciesCondition = OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(plotId)

  override val plotResultsCondition = OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.eq(plotId)

  override val latestLiveField = OBSERVATION_PLOT_RESULTS.TOTAL_LIVE.asNonNullable()

  override val observedTotalsCondition = OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.eq(plotId)

  override val survivalRateRecalculationCondition: Condition
    get() = observedTotalsCondition

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_PLOT_RESULTS.monitoringPlots.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(
          true
      )

  override val observedTotalsScopeField = OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID

  override val observedTotalsScopeHistoryField = OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_PLOT_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      plotField.eq(plotId)

  override fun anyChildHasNullSurvivalRateCondition(observationIdField: Field<ObservationId?>) =
      DSL.falseCondition()

  override fun observationPlotsCondition(observationIdField: Field<ObservationId?>) =
      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationIdField)
          .and(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))

  override fun observationPlotResultsCondition(plotResultsTable: ObservationPlotResults) =
      DSL.falseCondition()

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      tempStratumTable.MONITORING_PLOT_ID.eq(plotId)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(plotId)
}

class ObservationResultsSubstratum(
    val substratumHistorySelect: Select<Record1<SubstratumHistoryId?>>,
    val substratumId: SubstratumId? = null,
    val plotId: MonitoringPlotId? = null,
) : ObservationResultsScope<SubstratumId, SubstratumHistoryId> {
  constructor(
      substratumHistoryId: SubstratumHistoryId,
      substratumId: SubstratumId? = null,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(substratumHistoryId)), substratumId, plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID)
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))
          .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID)),
      plotId = plotId,
  )

  override val scopeId: Select<Record1<SubstratumId?>> =
      if (substratumId != null) {
        DSL.select(DSL.inline(substratumId))
      } else {
        DSL.select(DSL.castNull(OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_ID.dataType))
      }

  override val scopeHistoryId = substratumHistorySelect

  override val rollupSpeciesTable = OBSERVED_SUBSTRATUM_SPECIES_TOTALS

  override val rollupSpeciesCondition =
      OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_HISTORY_ID.`in`(substratumHistorySelect)

  override val plotResultsCondition =
      OBSERVATION_PLOT_RESULTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.`in`(
          substratumHistorySelect
      )

  override val latestLiveField = OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_LIVE.asNonNullable()

  override val observedTotalsCondition =
      OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID.`in`(substratumHistorySelect)

  override val survivalRateRecalculationCondition: Condition
    get() = observedTotalsCondition

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_SUBSTRATUM_RESULTS.substrata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(
          true
      )

  override val observedTotalsScopeField = OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_ID

  override val observedTotalsScopeHistoryField =
      OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_SUBSTRATUM_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun anyChildHasNullSurvivalRateCondition(
      observationIdField: Field<ObservationId?>
  ): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_PLOT_RESULTS)
              .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationIdField))
              .and(
                  OBSERVATION_PLOT_RESULTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.`in`(
                      substratumHistorySelect
                  )
              )
              .and(OBSERVATION_PLOT_RESULTS.observationPlots.IS_PERMANENT.isTrue)
              .and(
                  DSL.notExists(
                      DSL.selectOne()
                          .from(PLOT_T0_DENSITIES)
                          .where(
                              PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(
                                  OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID
                              )
                          )
                          .and(PLOT_T0_DENSITIES.PLOT_DENSITY.gt(BigDecimal.ZERO))
                  )
              )
      )

  override fun observationPlotsCondition(observationIdField: Field<ObservationId?>) =
      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationIdField)
          .and(
              OBSERVATION_PLOTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.`in`(
                  substratumHistorySelect
              )
          )

  override fun observationPlotResultsCondition(plotResultsTable: ObservationPlotResults) =
      DSL.and(
          plotResultsTable.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.eq(
              OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID
          ),
          plotResultsTable.OBSERVATION_ID.eq(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID),
          DSL.or(
              observedTotalsPlantingSiteTempCondition,
              plotResultsTable.observationPlots.IS_PERMANENT.isTrue,
          ),
      )

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      tempStratumTable.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.`in`(substratumHistorySelect)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      permPlotsTable.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.`in`(substratumHistorySelect)
}

class ObservationResultsStratum(
    val stratumHistorySelect: Select<Record1<StratumHistoryId?>>,
    val stratumId: StratumId? = null,
    val plotId: MonitoringPlotId? = null,
) : ObservationResultsScope<StratumId, StratumHistoryId> {
  constructor(
      stratumHistoryId: StratumHistoryId,
      stratumId: StratumId? = null,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(stratumHistoryId)), stratumId, plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID)
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))
          .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID)),
      plotId = plotId,
  )

  constructor(
      stratumId: StratumId
  ) : this(
      DSL.select(STRATUM_HISTORIES.ID)
          .from(STRATUM_HISTORIES)
          .where(STRATUM_HISTORIES.STRATUM_ID.eq(stratumId)),
      stratumId,
  )

  override val scopeId: Select<Record1<StratumId?>> =
      if (stratumId != null) {
        DSL.select(DSL.inline(stratumId))
      } else {
        DSL.select(DSL.castNull(OBSERVATION_STRATUM_RESULTS.STRATUM_ID.dataType))
      }

  override val scopeHistoryId = stratumHistorySelect

  override val rollupSpeciesTable = OBSERVED_STRATUM_SPECIES_TOTALS

  override val rollupSpeciesCondition =
      OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_HISTORY_ID.`in`(stratumHistorySelect)

  override val plotResultsCondition =
      OBSERVATION_PLOT_RESULTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID.`in`(
          stratumHistorySelect
      )

  override val latestLiveField =
      with(OBSERVATION_SUBSTRATUM_RESULTS) {
        DSL.field(
            DSL.select(DSL.sum(DSL.coalesce(TOTAL_LIVE, 0)).cast(SQLDataType.INTEGER))
                .from(this)
                .join(SUBSTRATUM_HISTORIES)
                .on(SUBSTRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORY_ID))
                .where(
                    SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(
                        OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID
                    )
                )
                .and(
                    OBSERVATION_ID.eq(
                        latestObservationForSubstratumField(
                            OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID,
                            SUBSTRATUM_ID,
                        )
                    )
                )
        )
      }

  override val observedTotalsCondition =
      OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.`in`(stratumHistorySelect)

  override val survivalRateRecalculationCondition: Condition
    get() =
        if (plotId != null) {
          OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.`in`(
              DSL.selectDistinct(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID)
                  .from(MONITORING_PLOT_HISTORIES)
                  .join(SUBSTRATUM_HISTORIES)
                  .on(SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
                  .where(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(plotId))
          )
        } else {
          observedTotalsCondition
        }

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_STRATUM_RESULTS.strata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)

  override val observedTotalsScopeField = OBSERVATION_STRATUM_RESULTS.STRATUM_ID

  override val observedTotalsScopeHistoryField = OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_STRATUM_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun anyChildHasNullSurvivalRateCondition(
      observationIdField: Field<ObservationId?>
  ): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_SUBSTRATUM_RESULTS)
              .join(SUBSTRATUM_HISTORIES)
              .on(SUBSTRATUM_HISTORIES.ID.eq(OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID))
              .where(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(observationIdField))
              .and(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.`in`(stratumHistorySelect))
              .and(OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE.isNull)
              .and(
                  DSL.or(
                      observedTotalsPlantingSiteTempCondition,
                      DSL.exists(
                          DSL.selectOne()
                              .from(OBSERVATION_PLOT_RESULTS)
                              .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationIdField))
                              .and(
                                  OBSERVATION_PLOT_RESULTS.monitoringPlotHistories
                                      .SUBSTRATUM_HISTORY_ID
                                      .eq(OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID)
                              )
                              .and(OBSERVATION_PLOT_RESULTS.observationPlots.IS_PERMANENT.isTrue)
                      ),
                  )
              )
      )

  override fun observationPlotsCondition(observationIdField: Field<ObservationId?>) =
      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationIdField)
          .and(
              OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID.`in`(
                  stratumHistorySelect
              )
          )

  override fun observationPlotResultsCondition(plotResultsTable: ObservationPlotResults) =
      DSL.and(
          plotResultsTable.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID.eq(
              OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID
          ),
          plotResultsTable.OBSERVATION_ID.eq(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID),
          DSL.or(
              observedTotalsPlantingSiteTempCondition,
              plotResultsTable.observationPlots.IS_PERMANENT.isTrue,
          ),
      )

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.`in`(
          DSL.select(STRATUM_HISTORIES.STRATUM_ID)
              .from(STRATUM_HISTORIES)
              .where(STRATUM_HISTORIES.ID.eq(OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID))
      )

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      permPlotsTable.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID.`in`(
          DSL.select(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID)
              .from(SUBSTRATUM_HISTORIES)
              .where(
                  SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(
                      OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID
                  )
              )
      )
}

class ObservationResultsSite(
    val siteHistorySelect: Select<Record1<PlantingSiteHistoryId?>>,
    val siteSelect: Select<Record1<PlantingSiteId?>>,
    val plotId: MonitoringPlotId? = null,
) : ObservationResultsScope<PlantingSiteId, PlantingSiteHistoryId> {
  constructor(
      siteHistoryId: PlantingSiteHistoryId,
      siteId: PlantingSiteId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(siteHistoryId)), DSL.select(DSL.inline(siteId)), plotId)

  constructor(
      siteId: PlantingSiteId
  ) : this(
      DSL.select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .from(OBSERVATIONS)
          .where(OBSERVATIONS.ID.eq(OBSERVATION_SITE_RESULTS.OBSERVATION_ID)),
      DSL.select(DSL.inline(siteId)),
  )

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .from(OBSERVATIONS)
          .where(OBSERVATIONS.ID.eq(OBSERVATION_SITE_RESULTS.OBSERVATION_ID)),
      DSL.select(MONITORING_PLOTS.PLANTING_SITE_ID)
          .from(MONITORING_PLOTS)
          .where(MONITORING_PLOTS.ID.eq(plotId)),
      plotId = plotId,
  )

  constructor(
      stratumId: StratumId
  ) : this(
      DSL.select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .from(OBSERVATIONS)
          .where(OBSERVATIONS.ID.eq(OBSERVATION_SITE_RESULTS.OBSERVATION_ID)),
      DSL.select(STRATA.PLANTING_SITE_ID).from(STRATA).where(STRATA.ID.eq(stratumId)),
  )

  override val scopeId = siteSelect

  override val scopeHistoryId = siteHistorySelect

  override val rollupSpeciesTable = OBSERVED_SITE_SPECIES_TOTALS

  override val rollupSpeciesCondition = OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID.eq(siteSelect)

  override val plotResultsCondition = DSL.trueCondition()

  override val latestLiveField =
      with(OBSERVATION_SUBSTRATUM_RESULTS) {
        DSL.field(
            DSL.select(DSL.sum(DSL.coalesce(TOTAL_LIVE, 0)).cast(SQLDataType.INTEGER))
                .from(this)
                .join(SUBSTRATUM_HISTORIES)
                .on(SUBSTRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORY_ID))
                .join(STRATUM_HISTORIES)
                .on(STRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID))
                .where(
                    STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                        OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID
                    )
                )
                .and(
                    OBSERVATION_ID.eq(
                        latestObservationForSubstratumField(
                            OBSERVATION_SITE_RESULTS.OBSERVATION_ID,
                            SUBSTRATUM_ID,
                        )
                    )
                ),
        )
      }

  override val observedTotalsCondition = OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID.eq(siteSelect)

  override val survivalRateRecalculationCondition: Condition
    get() = observedTotalsCondition

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_SITE_RESULTS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)

  override val observedTotalsScopeField = OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID

  override val observedTotalsScopeHistoryField = OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_SITE_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun anyChildHasNullSurvivalRateCondition(
      observationIdField: Field<ObservationId?>
  ): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_STRATUM_RESULTS)
              .join(STRATUM_HISTORIES)
              .on(STRATUM_HISTORIES.ID.eq(OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID))
              .where(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(observationIdField))
              .and(STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.`in`(siteHistorySelect))
              .and(OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE.isNull)
      )

  /**
   * Returns the site-level survival rate as an area-weighted average of the stratum-level survival
   * rates. Substrata that weren't observed in or before this observation don't count toward the
   * areas of their strata in the weighting formula.
   */
  override fun survivalRateValue(
      observationIdField: Field<ObservationId?>,
      survivalRateDenominator: Field<BigDecimal>,
      latestLiveField: Field<Int>,
      permanentLiveField: Field<Int>,
  ): Field<Int?> {
    val weightedAverage =
        DSL.field(
            DSL.select(
                    DSL.sum(
                            SUBSTRATUM_HISTORIES.AREA_HA.mul(
                                OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE
                            )
                        )
                        .div(DSL.nullif(DSL.sum(SUBSTRATUM_HISTORIES.AREA_HA), BigDecimal.ZERO))
                )
                .from(OBSERVATION_STRATUM_RESULTS)
                .join(STRATUM_HISTORIES)
                .on(STRATUM_HISTORIES.ID.eq(OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID))
                .join(SUBSTRATUM_HISTORIES)
                .on(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID))
                .where(
                    STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                        OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID
                    )
                )
                .and(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(observationIdField))
                .and(OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE.isNotNull)
                .and(
                    substratumObservedAtOrBefore(
                        SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
                        observationIdField,
                    )
                )
        )

    return DSL.case_()
        .`when`(
            anyChildHasNullSurvivalRateCondition(observationIdField),
            DSL.castNull(SQLDataType.INTEGER),
        )
        .`when`(
            weightedAverage.isNull,
            DSL.castNull(SQLDataType.INTEGER),
        )
        .else_(weightedAverage.cast(SQLDataType.INTEGER))
  }

  override fun observationPlotsCondition(observationIdField: Field<ObservationId?>) =
      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationIdField)
          .and(OBSERVATION_PLOTS.monitoringPlots.PLANTING_SITE_ID.eq(siteSelect))

  override fun observationPlotResultsCondition(plotResultsTable: ObservationPlotResults) =
      DSL.and(
          plotResultsTable.monitoringPlotHistories.substratumHistories.stratumHistories
              .PLANTING_SITE_HISTORY_ID
              .eq(OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID),
          plotResultsTable.OBSERVATION_ID.eq(OBSERVATION_SITE_RESULTS.OBSERVATION_ID),
          DSL.or(
              observedTotalsPlantingSiteTempCondition,
              plotResultsTable.observationPlots.IS_PERMANENT.isTrue,
          ),
      )

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      STRATUM_T0_TEMP_DENSITIES.strata.PLANTING_SITE_ID.eq(siteSelect)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SITE_ID.eq(siteSelect)
}
