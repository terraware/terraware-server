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
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.db.latestObservationForSubstratumField
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

  fun anyChildHasNullSurvivalRateCondition(observationId: ObservationId): Condition

  fun observationPlotsCondition(observationId: ObservationId): Condition

  fun observationPlotResultsCondition(plotResultsTable: ObservationPlotResults): Condition
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

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_PLOT_RESULTS.monitoringPlots.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(
          true
      )

  override val observedTotalsScopeField = OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID

  override val observedTotalsScopeHistoryField = OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_PLOT_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      plotField.eq(plotId)

  override fun anyChildHasNullSurvivalRateCondition(observationId: ObservationId) =
      DSL.falseCondition()

  override fun observationPlotsCondition(observationId: ObservationId) =
      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)
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
) : ObservationResultsScope<SubstratumId, SubstratumHistoryId> {
  constructor(
      substratumHistoryId: SubstratumHistoryId,
      substratumId: SubstratumId? = null,
  ) : this(DSL.select(DSL.inline(substratumHistoryId)), substratumId)

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

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_SUBSTRATUM_RESULTS.substrata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(
          true
      )

  override val observedTotalsScopeField = OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_ID

  override val observedTotalsScopeHistoryField =
      OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_SUBSTRATUM_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      DSL.falseCondition()

  override fun anyChildHasNullSurvivalRateCondition(observationId: ObservationId): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_PLOT_RESULTS)
              .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
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

  override fun observationPlotsCondition(observationId: ObservationId) =
      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)
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
) : ObservationResultsScope<StratumId, StratumHistoryId> {
  constructor(
      stratumHistoryId: StratumHistoryId,
      stratumId: StratumId? = null,
  ) : this(DSL.select(DSL.inline(stratumHistoryId)), stratumId)

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

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_STRATUM_RESULTS.strata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)

  override val observedTotalsScopeField = OBSERVATION_STRATUM_RESULTS.STRATUM_ID

  override val observedTotalsScopeHistoryField = OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_STRATUM_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      DSL.falseCondition()

  override fun anyChildHasNullSurvivalRateCondition(observationId: ObservationId): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_SUBSTRATUM_RESULTS)
              .join(SUBSTRATUM_HISTORIES)
              .on(SUBSTRATUM_HISTORIES.ID.eq(OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID))
              .where(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(observationId))
              .and(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.`in`(stratumHistorySelect))
              .and(OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE.isNull)
              .and(
                  DSL.or(
                      observedTotalsPlantingSiteTempCondition,
                      DSL.exists(
                          DSL.selectOne()
                              .from(OBSERVATION_PLOT_RESULTS)
                              .where(OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(observationId))
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

  override fun observationPlotsCondition(observationId: ObservationId) =
      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)
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
              .where(STRATUM_HISTORIES.ID.`in`(stratumHistorySelect))
      )

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      permPlotsTable.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID.`in`(
          stratumHistorySelect
      )
}

class ObservationResultsSite(
    val siteHistorySelect: Select<Record1<PlantingSiteHistoryId?>>,
    val siteId: PlantingSiteId,
) : ObservationResultsScope<PlantingSiteId, PlantingSiteHistoryId> {
  constructor(
      siteHistoryId: PlantingSiteHistoryId,
      siteId: PlantingSiteId,
  ) : this(DSL.select(DSL.inline(siteHistoryId)), siteId)

  constructor(
      siteId: PlantingSiteId
  ) : this(
      DSL.select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .from(OBSERVATIONS)
          .where(OBSERVATIONS.ID.eq(OBSERVATION_SITE_RESULTS.OBSERVATION_ID)),
      siteId,
  )

  override val scopeId: Select<Record1<PlantingSiteId?>> = DSL.select(DSL.inline(siteId))

  override val scopeHistoryId = siteHistorySelect

  override val rollupSpeciesTable = OBSERVED_SITE_SPECIES_TOTALS

  override val rollupSpeciesCondition = OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID.eq(siteId)

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

  override val observedTotalsCondition = OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID.eq(siteId)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_SITE_RESULTS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)

  override val observedTotalsScopeField = OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID

  override val observedTotalsScopeHistoryField = OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_SITE_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      DSL.falseCondition()

  override fun anyChildHasNullSurvivalRateCondition(observationId: ObservationId): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_STRATUM_RESULTS)
              .join(STRATUM_HISTORIES)
              .on(STRATUM_HISTORIES.ID.eq(OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID))
              .where(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(observationId))
              .and(STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.`in`(siteHistorySelect))
              .and(OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE.isNull)
      )

  override fun observationPlotsCondition(observationId: ObservationId) =
      OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationId)
          .and(OBSERVATION_PLOTS.monitoringPlots.PLANTING_SITE_ID.eq(siteId))

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
      STRATUM_T0_TEMP_DENSITIES.strata.PLANTING_SITE_ID.eq(siteId)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SITE_ID.eq(siteId)
}
