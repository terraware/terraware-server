package com.terraformation.backend.tracking

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import com.terraformation.backend.tracking.db.T0Store
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedEventModel
import com.terraformation.backend.tracking.model.ZoneT0TempDataModel
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class T0Service(
    private val dslContext: DSLContext,
    private val rateLimitedEventPublisher: RateLimitedEventPublisher,
    private val t0Store: T0Store,
) {
  private data class PlotEventDetailsModel(
      val plotNumber: Long,
      val organizationId: OrganizationId,
      val plantingSiteId: PlantingSiteId,
  )

  fun assignT0PlotsData(plotsList: List<PlotT0DataModel>) {
    val plotIds = plotsList.map { it.monitoringPlotId }
    val plotMap = getPlotInfo(plotIds)
    val orgList = plotMap.values.map { it.organizationId }.toSet()
    val plantingSiteIds = plotMap.values.map { it.plantingSiteId }.toSet()
    require(plantingSiteIds.size == 1) { "Cannot assign T0 data to plots from multiple sites." }

    val plotsChangeList = mutableListOf<PlotT0DensityChangedModel>()
    dslContext.transaction { _ ->
      plotsList.forEach { model ->
        plotsChangeList.add(
            if (model.observationId == null) {
              t0Store.assignT0PlotSpeciesDensities(model.monitoringPlotId, model.densityData)
            } else {
              t0Store.assignT0PlotObservation(model.monitoringPlotId, model.observationId)
            }
        )
      }
    }

    val speciesToFetch =
        plotsChangeList.flatMap { it.speciesDensityChanges.map { it.speciesId } }.toSet()
    val speciesNames =
        with(SPECIES) {
          dslContext
              .select(ID, SCIENTIFIC_NAME)
              .from(this)
              .where(ID.`in`(speciesToFetch))
              .fetchMap(ID.asNonNullable(), SCIENTIFIC_NAME.asNonNullable())
        }

    rateLimitedEventPublisher.publishEvent(
        RateLimitedT0DataAssignedEvent(
            organizationId = orgList.first(),
            plantingSiteId = plantingSiteIds.first(),
            monitoringPlots =
                plotsChangeList
                    .map { changedModel ->
                      PlotT0DensityChangedEventModel(
                          monitoringPlotId = changedModel.monitoringPlotId,
                          monitoringPlotNumber =
                              plotMap[changedModel.monitoringPlotId]!!.plotNumber,
                          speciesDensityChanges =
                              changedModel.speciesDensityChanges
                                  .map { it ->
                                    SpeciesDensityChangedEventModel.of(
                                        it,
                                        speciesNames[it.speciesId]!!,
                                    )
                                  }
                                  .sortedBy { it.speciesScientificName },
                      )
                    }
                    .sortedBy { it.monitoringPlotNumber },
        )
    )
  }

  fun assignT0TempZoneData(zonesList: List<ZoneT0TempDataModel>) {
    dslContext.transaction { _ ->
      zonesList.forEach { model ->
        t0Store.assignT0TempZoneSpeciesDensities(model.plantingZoneId, model.densityData)
      }
    }

    // future PR: publish rate-limited event here
  }

  private fun getPlotInfo(
      plotIds: Collection<MonitoringPlotId>
  ): Map<MonitoringPlotId, PlotEventDetailsModel> {
    return with(MONITORING_PLOTS) {
      dslContext
          .select(ID, PLOT_NUMBER, ORGANIZATION_ID, PLANTING_SITE_ID)
          .from(this)
          .where(ID.`in`(plotIds.toSet()))
          .fetchMap(
              ID.asNonNullable(),
              { record ->
                PlotEventDetailsModel(
                    plotNumber = record[PLOT_NUMBER.asNonNullable()],
                    organizationId = record[ORGANIZATION_ID.asNonNullable()],
                    plantingSiteId = record[PLANTING_SITE_ID.asNonNullable()],
                )
              },
          )
    }
  }
}
