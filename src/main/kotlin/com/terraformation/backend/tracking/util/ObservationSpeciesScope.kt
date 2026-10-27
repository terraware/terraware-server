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
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import org.jooq.Condition
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Select
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

interface ObservationSpeciesScope<ID : Any, HistoryId : Any> {
  val scopeId: Select<Record1<ID?>>
  val scopeHistoryId: Select<Record1<HistoryId?>>

  val observedTotalsCondition: Condition
  val observedTotalsPlantingSiteTempCondition: Condition
  val observedTotalsScopeField: TableField<*, ID?>
  val observedTotalsScopeHistoryField: TableField<*, HistoryId?>
  val observedTotalsTable: Table<out Record>

  fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>): Condition

  fun tempStratumCondition(tempStratumTable: ObservationPlots): Condition

  fun t0DensityCondition(permPlotsTable: ObservationPlots): Condition
}

class ObservationSpeciesPlot(
    val plotId: MonitoringPlotId,
    plotHistorySelect: Select<Record1<MonitoringPlotHistoryId?>>,
) : ObservationSpeciesScope<MonitoringPlotId, MonitoringPlotHistoryId> {
  constructor(
      plotId: MonitoringPlotId,
      plotHistoryId: MonitoringPlotHistoryId,
  ) : this(plotId, DSL.select(DSL.inline(plotHistoryId, MONITORING_PLOT_HISTORIES.ID.dataType)))

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      plotId,
      DSL.select(MONITORING_PLOT_HISTORIES.ID)
          .from(MONITORING_PLOT_HISTORIES)
          .where(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(plotId)),
  )

  override val scopeId: Select<Record1<MonitoringPlotId?>> =
      DSL.select(DSL.inline(plotId, MONITORING_PLOTS.ID.dataType))

  override val scopeHistoryId = plotHistorySelect

  override val observedTotalsCondition = OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(plotId)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_PLOT_SPECIES_TOTALS.monitoringPlots.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
          .eq(true)

  override val observedTotalsScopeField = OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID

  override val observedTotalsScopeHistoryField =
      OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_HISTORY_ID

  override val observedTotalsTable = OBSERVED_PLOT_SPECIES_TOTALS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      plotField.eq(plotId)

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      tempStratumTable.MONITORING_PLOT_ID.eq(plotId)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(plotId)
}

class ObservationSpeciesSubstratum(
    val substratumHistorySelect: Select<Record1<SubstratumHistoryId?>>,
    val substratumId: SubstratumId? = null,
    val plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope<SubstratumId, SubstratumHistoryId> {
  override val scopeId: Select<Record1<SubstratumId?>> =
      if (substratumId != null) {
        DSL.select(DSL.inline(substratumId, SUBSTRATA.ID.dataType))
      } else {
        DSL.select(DSL.castNull(SUBSTRATA.ID.dataType))
      }

  override val scopeHistoryId = substratumHistorySelect

  override val observedTotalsCondition =
      OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_HISTORY_ID.`in`(substratumHistorySelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_SUBSTRATUM_SPECIES_TOTALS.substrata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
          .eq(true)

  override val observedTotalsScopeField = OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID

  override val observedTotalsScopeHistoryField =
      OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_HISTORY_ID

  override val observedTotalsTable = OBSERVED_SUBSTRATUM_SPECIES_TOTALS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      tempStratumTable.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.`in`(substratumHistorySelect)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      permPlotsTable.monitoringPlotHistories.SUBSTRATUM_HISTORY_ID.`in`(substratumHistorySelect)
}

class ObservationSpeciesStratum(
    val stratumHistorySelect: Select<Record1<StratumHistoryId?>>,
    val stratumId: StratumId? = null,
    val plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope<StratumId, StratumHistoryId> {
  override val scopeId: Select<Record1<StratumId?>> =
      if (stratumId != null) {
        DSL.select(DSL.inline(stratumId, STRATA.ID.dataType))
      } else {
        DSL.select(DSL.castNull(STRATA.ID.dataType))
      }

  override val scopeHistoryId = stratumHistorySelect

  override val observedTotalsCondition =
      OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_HISTORY_ID.`in`(stratumHistorySelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_STRATUM_SPECIES_TOTALS.strata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(
          true
      )

  override val observedTotalsScopeField = OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID

  override val observedTotalsScopeHistoryField = OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_HISTORY_ID

  override val observedTotalsTable = OBSERVED_STRATUM_SPECIES_TOTALS

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

class ObservationSpeciesSite(
    val siteSelect: Select<Record1<PlantingSiteId?>>,
    siteHistorySelect: Select<Record1<PlantingSiteHistoryId?>>,
    val plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope<PlantingSiteId, PlantingSiteHistoryId> {
  override val scopeId = siteSelect

  override val scopeHistoryId = siteHistorySelect

  override val observedTotalsCondition =
      OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID.eq(siteSelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_SITE_SPECIES_TOTALS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)

  override val observedTotalsScopeField = OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID

  override val observedTotalsScopeHistoryField =
      OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_HISTORY_ID

  override val observedTotalsTable = OBSERVED_SITE_SPECIES_TOTALS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      STRATUM_T0_TEMP_DENSITIES.strata.PLANTING_SITE_ID.eq(siteSelect)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SITE_ID.eq(siteSelect)
}

/**
 * Species totals scope for each row of [OBSERVED_PLOT_SPECIES_TOTALS] being updated, so the
 * survival rates of many plots can be calculated in a single statement.
 */
object ObservationSpeciesPlotRow :
    ObservationSpeciesScope<MonitoringPlotId, MonitoringPlotHistoryId> {
  override val scopeId: Select<Record1<MonitoringPlotId?>> =
      DSL.select(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID)

  override val scopeHistoryId: Select<Record1<MonitoringPlotHistoryId?>> =
      DSL.select(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_HISTORY_ID)

  override val observedTotalsCondition: Condition = DSL.trueCondition()

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_PLOT_SPECIES_TOTALS.monitoringPlots.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
          .eq(true)

  override val observedTotalsScopeField = OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID

  override val observedTotalsScopeHistoryField =
      OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_HISTORY_ID

  override val observedTotalsTable = OBSERVED_PLOT_SPECIES_TOTALS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      DSL.falseCondition()

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      tempStratumTable.MONITORING_PLOT_ID.eq(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID)
}
