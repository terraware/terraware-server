package com.terraformation.backend.tracking

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import com.terraformation.backend.tracking.db.T0PlotStore
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedModel
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class T0PlotService(
    private val dslContext: DSLContext,
    private val rateLimitedEventPublisher: RateLimitedEventPublisher,
    private val t0PlotStore: T0PlotStore,
) {
  fun assignT0PlotsData(plotsList: List<PlotT0DataModel>) {
    val plotIds = plotsList.map { it.monitoringPlotId }
    val organizationMap = getOrgAndSitesFromPlots(plotIds)
    val plantingSiteIds = organizationMap.values.flatten()
    require(plantingSiteIds.size == 1) { "Cannot assign T0 data to plots from multiple sites." }

    val plotsChangeList = mutableListOf<PlotT0DensityChangedModel>()
    dslContext.transaction { _ ->
      plotsList.forEach { model ->
        plotsChangeList.add(
            if (model.observationId == null) {
              t0PlotStore.assignT0PlotSpeciesDensities(model.monitoringPlotId, model.densityData)
            } else {
              t0PlotStore.assignT0PlotObservation(model.monitoringPlotId, model.observationId)
            }
        )
      }
    }

    rateLimitedEventPublisher.publishEvent(
        RateLimitedT0DataAssignedEvent(
            organizationId = organizationMap.keys.first(),
            plantingSiteId = plantingSiteIds.first(),
            monitoringPlots = plotsChangeList,
        )
    )
  }

  private fun getOrgAndSitesFromPlots(
      plotIds: Collection<MonitoringPlotId>
  ): Map<OrganizationId, List<PlantingSiteId>> {
    return with(MONITORING_PLOTS) {
      dslContext
          .selectDistinct(ORGANIZATION_ID, PLANTING_SITE_ID)
          .from(this)
          .where(ID.`in`(plotIds.toSet()))
          .fetchGroups(ORGANIZATION_ID.asNonNullable(), PLANTING_SITE_ID.asNonNullable())
    }
  }
}
