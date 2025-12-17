package com.terraformation.backend.tracking.util

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.ObservationPlots
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import org.jooq.Condition
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Select
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

interface ObservationSpeciesScope<ID : Any> {
  val scopeId: Select<Record1<ID?>>
  val observedTotalsCondition: Condition
  val observedTotalsPlantingSiteTempCondition: Condition
  val observedTotalsScopeField: TableField<*, ID?>
  val observedTotalsTable: Table<out Record>

  fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>): Condition

  fun tempStratumCondition(tempStratumTable: ObservationPlots): Condition

  fun t0DensityCondition(permPlotsTable: ObservationPlots): Condition
}

class ObservationSpeciesPlot(val plotId: MonitoringPlotId) :
    ObservationSpeciesScope<MonitoringPlotId> {
  override val scopeId = DSL.select(DSL.inline(plotId))

  override val observedTotalsCondition = OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(plotId)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_PLOT_SPECIES_TOTALS.monitoringPlots.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
          .eq(true)

  override val observedTotalsScopeField = OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID

  override val observedTotalsTable = OBSERVED_PLOT_SPECIES_TOTALS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      plotField.eq(plotId)

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      tempStratumTable.MONITORING_PLOT_ID.eq(plotId)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(plotId)
}

class ObservationSpeciesSubstratum(
    val substratumSelect: Select<Record1<SubstratumId?>>,
    val plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope<SubstratumId> {
  constructor(
      substratumId: SubstratumId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(substratumId)), plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID)
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))
          .and(
              OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.OBSERVATION_ID)
          ),
      plotId,
  )

  override val scopeId = substratumSelect

  override val observedTotalsCondition =
      OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID.eq(substratumSelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_SUBSTRATUM_SPECIES_TOTALS.substrata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
          .eq(true)

  override val observedTotalsScopeField = OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_ID

  override val observedTotalsTable = OBSERVED_SUBSTRATUM_SPECIES_TOTALS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      tempStratumTable.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID.eq(
          substratumSelect
      )

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      permPlotsTable.monitoringPlotHistories.substratumHistories.SUBSTRATUM_ID.eq(substratumSelect)
}

class ObservationSpeciesStratum(
    val stratumSelect: Select<Record1<StratumId?>>,
    val plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope<StratumId> {
  constructor(
      stratumId: StratumId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(stratumId)), plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(
              OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories.stratumHistories
                  .STRATUM_ID
          )
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))
          .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID)),
      plotId,
  )

  override val scopeId = stratumSelect

  override val observedTotalsCondition =
      OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID.eq(stratumSelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_STRATUM_SPECIES_TOTALS.strata.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(
          true
      )

  override val observedTotalsScopeField = OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID

  override val observedTotalsTable = OBSERVED_STRATUM_SPECIES_TOTALS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(stratumSelect)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      permPlotsTable.monitoringPlotHistories.substratumHistories.stratumHistories.STRATUM_ID.eq(
          stratumSelect
      )
}

class ObservationSpeciesSite(
    val siteSelect: Select<Record1<PlantingSiteId?>>,
    val plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope<PlantingSiteId> {
  constructor(
      siteId: PlantingSiteId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(siteId)), plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(MONITORING_PLOTS.PLANTING_SITE_ID)
          .from(MONITORING_PLOTS)
          .where(MONITORING_PLOTS.ID.eq(plotId)),
      plotId,
  )

  constructor(
      stratumId: StratumId
  ) : this(DSL.select(STRATA.PLANTING_SITE_ID).from(STRATA).where(STRATA.ID.eq(stratumId)))

  override val scopeId = siteSelect

  override val observedTotalsCondition =
      OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID.eq(siteSelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_SITE_SPECIES_TOTALS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)

  override val observedTotalsScopeField = OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID

  override val observedTotalsTable = OBSERVED_SITE_SPECIES_TOTALS

  override fun alternateCompletedCondition(plotField: TableField<*, MonitoringPlotId?>) =
      if (plotId == null) DSL.falseCondition() else plotField.eq(plotId)

  override fun tempStratumCondition(tempStratumTable: ObservationPlots) =
      STRATUM_T0_TEMP_DENSITIES.strata.PLANTING_SITE_ID.eq(siteSelect)

  override fun t0DensityCondition(permPlotsTable: ObservationPlots) =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SITE_ID.eq(siteSelect)
}
