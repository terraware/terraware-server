package com.terraformation.backend.tracking.util

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import org.jooq.Condition
import org.jooq.Record
import org.jooq.Record1
import org.jooq.Select
import org.jooq.Table
import org.jooq.TableField
import org.jooq.impl.DSL

interface ObservationSpeciesScope<ID : Any> {
  val scopeId: Select<Record1<ID?>>
  val observedTotalsTable: Table<out Record>
  val observedTotalsScopeField: TableField<*, ID?>
  val tempZoneCondition: Condition
  val t0DensityCondition: Condition
  val alternateCompletedCondition: Condition
  val observedTotalsCondition: Condition
  val observedTotalsPlantingSiteTempCondition: Condition
}

class ObservationSpeciesPlot(plotId: MonitoringPlotId) : ObservationSpeciesScope<MonitoringPlotId> {
  override val scopeId = DSL.select(DSL.inline(plotId))

  override val observedTotalsTable = OBSERVED_PLOT_SPECIES_TOTALS

  override val observedTotalsScopeField = OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID

  override val tempZoneCondition = MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(plotId)

  override val t0DensityCondition = PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(plotId)

  override val alternateCompletedCondition = OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)

  override val observedTotalsCondition = OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(plotId)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_PLOT_SPECIES_TOTALS.monitoringPlots.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
          .eq(true)
}

class ObservationSpeciesSubzone(
    subzoneSelect: Select<Record1<PlantingSubzoneId?>>,
    plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope<PlantingSubzoneId> {
  constructor(
      subzoneId: PlantingSubzoneId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(subzoneId)), plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(
              OBSERVATION_PLOTS.monitoringPlotHistories.plantingSubzoneHistories.PLANTING_SUBZONE_ID
          )
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))
          .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVED_SUBZONE_SPECIES_TOTALS.OBSERVATION_ID)),
      plotId,
  )

  override val scopeId = subzoneSelect

  override val observedTotalsTable = OBSERVED_SUBZONE_SPECIES_TOTALS

  override val observedTotalsScopeField = OBSERVED_SUBZONE_SPECIES_TOTALS.PLANTING_SUBZONE_ID

  override val tempZoneCondition =
      MONITORING_PLOT_HISTORIES.plantingSubzoneHistories.PLANTING_SUBZONE_ID.eq(subzoneSelect)

  override val t0DensityCondition =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SUBZONE_ID.eq(subzoneSelect)

  override val alternateCompletedCondition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)

  override val observedTotalsCondition =
      OBSERVED_SUBZONE_SPECIES_TOTALS.PLANTING_SUBZONE_ID.eq(subzoneSelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_SUBZONE_SPECIES_TOTALS.plantingSubzones.plantingSites
          .SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
          .eq(true)
}

class ObservationSpeciesZone(
    zoneSelect: Select<Record1<PlantingZoneId?>>,
    plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope<PlantingZoneId> {
  constructor(
      zoneId: PlantingZoneId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(zoneId)), plotId)

  constructor(
      plotId: MonitoringPlotId
  ) : this(
      DSL.select(
              OBSERVATION_PLOTS.monitoringPlotHistories.plantingSubzoneHistories
                  .plantingZoneHistories
                  .PLANTING_ZONE_ID
          )
          .from(OBSERVATION_PLOTS)
          .where(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId))
          .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVED_ZONE_SPECIES_TOTALS.OBSERVATION_ID)),
      plotId,
  )

  override val scopeId = zoneSelect

  override val observedTotalsTable = OBSERVED_ZONE_SPECIES_TOTALS

  override val observedTotalsScopeField = OBSERVED_ZONE_SPECIES_TOTALS.PLANTING_ZONE_ID

  override val tempZoneCondition = PLANTING_ZONE_T0_TEMP_DENSITIES.PLANTING_ZONE_ID.eq(zoneSelect)

  override val t0DensityCondition =
      PLOT_T0_DENSITIES.monitoringPlots.plantingSubzones.PLANTING_ZONE_ID.eq(zoneSelect)

  override val alternateCompletedCondition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)

  override val observedTotalsCondition =
      OBSERVED_ZONE_SPECIES_TOTALS.PLANTING_ZONE_ID.eq(zoneSelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_ZONE_SPECIES_TOTALS.plantingZones.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(
          true
      )
}

class ObservationSpeciesSite(
    siteSelect: Select<Record1<PlantingSiteId?>>,
    plotId: MonitoringPlotId? = null,
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
      zoneId: PlantingZoneId
  ) : this(
      DSL.select(PLANTING_ZONES.PLANTING_SITE_ID)
          .from(PLANTING_ZONES)
          .where(PLANTING_ZONES.ID.eq(zoneId))
  )

  override val scopeId = siteSelect

  override val observedTotalsTable = OBSERVED_SITE_SPECIES_TOTALS

  override val observedTotalsScopeField = OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID

  override val tempZoneCondition =
      PLANTING_ZONE_T0_TEMP_DENSITIES.plantingZones.PLANTING_SITE_ID.eq(siteSelect)

  override val t0DensityCondition =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SITE_ID.eq(siteSelect)

  override val alternateCompletedCondition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)

  override val observedTotalsCondition =
      OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID.eq(siteSelect)

  override val observedTotalsPlantingSiteTempCondition =
      OBSERVED_SITE_SPECIES_TOTALS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true)
}
