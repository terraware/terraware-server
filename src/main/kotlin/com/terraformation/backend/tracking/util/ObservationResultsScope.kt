package com.terraformation.backend.tracking.util

import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.ObservationPlots
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
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
import org.jooq.Condition
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Select
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

/**
 * Identifies which level of the observation results hierarchy a recalculation or update applies to:
 * a single plot, a substratum, a stratum, or an entire planting site.
 *
 * Compared to [ObservationSpeciesScope], the scope's [observedTotalsTable] now points at the
 * `observation_*_results` aggregate table that is being written, while [rollupSpeciesTable] points
 * at the `observed_*_species_totals` table the per-species values are read from.
 */
interface ObservationResultsScope<ID : Any, HistoryId : Any> :
    ObservationSpeciesScope<ID, HistoryId> {
  /** Table containing the rolled-up species totals to read from. */
  val rollupSpeciesTable: Table<out Record>

  /** Filter on [rollupSpeciesTable] selecting the rows for this scope. */
  val rollupSpeciesCondition: Condition

  /** Filter on [OBSERVATION_PLOT_RESULTS] selecting the plots that belong to this scope. */
  val observationPlotsCondition: Condition
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

  override val observationPlotsCondition = OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.eq(plotId)

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

  override val observationPlotsCondition =
      OBSERVATION_PLOT_RESULTS.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.`in`(
          substratumHistorySelect
      )

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
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

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
      stratumId: StratumId,
      plotId: MonitoringPlotId? = null,
  ) : this(
      DSL.select(STRATUM_HISTORIES.ID)
          .from(STRATUM_HISTORIES)
          .where(STRATUM_HISTORIES.STRATUM_ID.eq(stratumId)),
      stratumId,
      plotId,
  )

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID)
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))
          .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID)),
      plotId = plotId,
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

  override val observationPlotsCondition =
      OBSERVATION_PLOT_RESULTS.monitoringPlotHistories.substratumHistories.STRATUM_HISTORY_ID.`in`(
          stratumHistorySelect
      )

  override val observedTotalsCondition =
      OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.`in`(stratumHistorySelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_STRATUM_RESULTS.strata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)

  override val observedTotalsScopeField = OBSERVATION_STRATUM_RESULTS.STRATUM_ID

  override val observedTotalsScopeHistoryField = OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_STRATUM_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

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
      DSL.select(OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID).from(OBSERVATION_SITE_RESULTS),
      DSL.select(DSL.inline(siteId)),
  )

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID).from(OBSERVATION_SITE_RESULTS),
      DSL.select(MONITORING_PLOTS.PLANTING_SITE_ID)
          .from(MONITORING_PLOTS)
          .where(MONITORING_PLOTS.ID.eq(plotId)),
      plotId = plotId,
  )

  constructor(
      stratumId: StratumId
  ) : this(
      DSL.select(OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID).from(OBSERVATION_SITE_RESULTS),
      DSL.select(STRATA.PLANTING_SITE_ID).from(STRATA).where(STRATA.ID.eq(stratumId)),
  )

  override val scopeId = siteSelect

  override val scopeHistoryId = siteHistorySelect

  override val rollupSpeciesTable = OBSERVED_SITE_SPECIES_TOTALS

  override val rollupSpeciesCondition = OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID.eq(siteSelect)

  override val observationPlotsCondition = DSL.trueCondition()

  override val observedTotalsCondition = OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID.eq(siteSelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVATION_SITE_RESULTS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)

  override val observedTotalsScopeField = OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID

  override val observedTotalsScopeHistoryField = OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID

  override val observedTotalsTable = OBSERVATION_SITE_RESULTS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      STRATUM_T0_TEMP_DENSITIES.strata.PLANTING_SITE_ID.eq(siteSelect)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SITE_ID.eq(siteSelect)
}
