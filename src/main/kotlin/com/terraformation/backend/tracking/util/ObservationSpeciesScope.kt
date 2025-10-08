package com.terraformation.backend.tracking.util

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import org.jooq.Condition
import org.jooq.Record1
import org.jooq.Select
import org.jooq.impl.DSL

interface ObservationSpeciesScope {
  val tempZoneCondition: Condition
  val t0DensityCondition: Condition
  val alternateCompletedCondition: Condition
}

class ObservationSpeciesPlot(plotId: MonitoringPlotId) : ObservationSpeciesScope {
  override val tempZoneCondition = MONITORING_PLOTS.ID.eq(plotId)

  override val t0DensityCondition = PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(plotId)

  override val alternateCompletedCondition = OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)
}

class ObservationSpeciesSubzone(
    subzoneSelect: Select<Record1<PlantingSubzoneId?>>,
    plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope {
  constructor(
      subzoneId: PlantingSubzoneId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(subzoneId)), plotId)

  override val tempZoneCondition = MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(subzoneSelect)

  override val t0DensityCondition =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SUBZONE_ID.eq(subzoneSelect)

  override val alternateCompletedCondition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)
}

class ObservationSpeciesZone(
    zoneSelect: Select<Record1<PlantingZoneId?>>,
    plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope {
  constructor(
      zoneId: PlantingZoneId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(zoneId)), plotId)

  override val tempZoneCondition = PLANTING_ZONE_T0_TEMP_DENSITIES.PLANTING_ZONE_ID.eq(zoneSelect)

  override val t0DensityCondition =
      PLOT_T0_DENSITIES.monitoringPlots.plantingSubzones.PLANTING_ZONE_ID.eq(zoneSelect)

  override val alternateCompletedCondition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)
}

class ObservationSpeciesSite(
    siteSelect: Select<Record1<PlantingSiteId?>>,
    plotId: MonitoringPlotId? = null,
) : ObservationSpeciesScope {
  constructor(
      siteId: PlantingSiteId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(siteId)), plotId)

  override val tempZoneCondition =
      PLANTING_ZONE_T0_TEMP_DENSITIES.plantingZones.PLANTING_SITE_ID.eq(siteSelect)

  override val t0DensityCondition =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SITE_ID.eq(siteSelect)

  override val alternateCompletedCondition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)
}
