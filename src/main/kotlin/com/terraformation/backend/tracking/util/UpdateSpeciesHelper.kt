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

// todo see if you can change these to getters
interface UpdateSpeciesHelper {
  fun getTempZoneCondition(): Condition

  fun getT0DensityCondition(): Condition

  fun getAlternateCompletedCondition(): Condition
}

class UpdatePlotSpeciesHelper(private val plotId: MonitoringPlotId) : UpdateSpeciesHelper {
  override fun getTempZoneCondition(): Condition = MONITORING_PLOTS.ID.eq(plotId)

  override fun getT0DensityCondition(): Condition = PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(plotId)

  override fun getAlternateCompletedCondition(): Condition =
      OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)
}

class UpdateSubzoneSpeciesHelper(
    private val subzoneSelect: Select<Record1<PlantingSubzoneId?>>,
    private val plotId: MonitoringPlotId? = null,
) : UpdateSpeciesHelper {
  constructor(
      subzoneId: PlantingSubzoneId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(subzoneId)), plotId)

  override fun getTempZoneCondition(): Condition =
      MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(subzoneSelect)

  override fun getT0DensityCondition(): Condition =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SUBZONE_ID.eq(subzoneSelect)

  override fun getAlternateCompletedCondition(): Condition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)
}

class UpdateZoneSpeciesHelper(
    private val zoneSelect: Select<Record1<PlantingZoneId?>>,
    private val plotId: MonitoringPlotId? = null,
) : UpdateSpeciesHelper {
  constructor(
      zoneId: PlantingZoneId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(zoneId)), plotId)

  override fun getTempZoneCondition(): Condition =
      PLANTING_ZONE_T0_TEMP_DENSITIES.PLANTING_ZONE_ID.eq(zoneSelect)

  override fun getT0DensityCondition(): Condition =
      PLOT_T0_DENSITIES.monitoringPlots.plantingSubzones.PLANTING_ZONE_ID.eq(zoneSelect)

  override fun getAlternateCompletedCondition(): Condition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)
}

class UpdateSiteSpeciesHelper(
    private val siteSelect: Select<Record1<PlantingSiteId?>>,
    private val plotId: MonitoringPlotId? = null,
) : UpdateSpeciesHelper {
  constructor(
      siteId: PlantingSiteId,
      plotId: MonitoringPlotId? = null,
  ) : this(DSL.select(DSL.inline(siteId)), plotId)

  override fun getTempZoneCondition(): Condition =
      PLANTING_ZONE_T0_TEMP_DENSITIES.plantingZones.PLANTING_SITE_ID.eq(siteSelect)

  override fun getT0DensityCondition(): Condition =
      PLOT_T0_DENSITIES.monitoringPlots.PLANTING_SITE_ID.eq(siteSelect)

  override fun getAlternateCompletedCondition(): Condition =
      if (plotId == null) DSL.falseCondition() else OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(plotId)
}
