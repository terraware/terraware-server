package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class MonitoringPlotStore(private val dslContext: DSLContext) {
  fun getOrganizationIdsFromPlots(plotIds: Collection<MonitoringPlotId>): Set<OrganizationId> =
      with(MONITORING_PLOTS) {
        dslContext
            .selectDistinct(ORGANIZATION_ID)
            .from(this)
            .where(ID.`in`(plotIds))
            .fetchSet(ORGANIZATION_ID.asNonNullable())
      }

  fun getPlantingSiteIdsFromPlots(plotIds: Collection<MonitoringPlotId>): Set<PlantingSiteId> =
      with(MONITORING_PLOTS) {
        dslContext
            .selectDistinct(PLANTING_SITE_ID)
            .from(this)
            .where(ID.`in`(plotIds))
            .fetchSet(PLANTING_SITE_ID.asNonNullable())
      }
}
