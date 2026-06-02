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
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
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
  ) : this(plotId, DSL.select(DSL.inline(plotHistoryId)))

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      plotId,
      DSL.select(MONITORING_PLOT_HISTORIES.ID)
          .from(MONITORING_PLOT_HISTORIES)
          .where(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(plotId)),
  )

  override val scopeId: Select<Record1<MonitoringPlotId?>> = DSL.select(DSL.inline(plotId))

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
          .and(
              OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.OBSERVATION_ID)
          ),
      plotId = plotId,
  )

  override val scopeId: Select<Record1<SubstratumId?>> =
      if (substratumId != null) {
        DSL.select(DSL.inline(substratumId))
      } else {
        DSL.select(DSL.castNull(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID.dataType))
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
          .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID)),
      plotId = plotId,
  )

  override val scopeId: Select<Record1<StratumId?>> =
      if (stratumId != null) {
        DSL.select(DSL.inline(stratumId))
      } else {
        DSL.select(DSL.castNull(OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID.dataType))
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
  constructor(
      siteId: PlantingSiteId,
      siteHistoryId: PlantingSiteHistoryId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(siteId)), DSL.select(DSL.inline(siteHistoryId)), plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(MONITORING_PLOTS.PLANTING_SITE_ID)
          .from(MONITORING_PLOTS)
          .where(MONITORING_PLOTS.ID.eq(plotId)),
      DSL.select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .from(OBSERVATIONS)
          .where(OBSERVATIONS.ID.eq(OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID)),
      plotId,
  )

  constructor(
      stratumId: StratumId
  ) : this(
      DSL.select(STRATA.PLANTING_SITE_ID).from(STRATA).where(STRATA.ID.eq(stratumId)),
      DSL.select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .from(OBSERVATIONS)
          .where(OBSERVATIONS.ID.eq(OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID)),
  )

  constructor(
      plantingSiteId: PlantingSiteId
  ) : this(
      DSL.select(DSL.inline(plantingSiteId)),
      DSL.select(OBSERVATIONS.PLANTING_SITE_HISTORY_ID)
          .from(OBSERVATIONS)
          .where(OBSERVATIONS.ID.eq(OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID)),
  )

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
