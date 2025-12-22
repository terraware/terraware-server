package com.terraformation.backend.tracking

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.ratelimit.RateLimitedEventPublisher
import com.terraformation.backend.tracking.db.T0Store
import com.terraformation.backend.tracking.event.RateLimitedT0DataAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedEventModel
import com.terraformation.backend.tracking.model.StratumT0DensityChangedEventModel
import com.terraformation.backend.tracking.model.StratumT0TempDataModel
import com.terraformation.backend.tracking.model.StratumT0TempDensityChangedModel
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

  private data class StratumEventDetailsModel(
      val stratumName: String,
      val organizationId: OrganizationId,
      val plantingSiteId: PlantingSiteId,
  )

  fun assignT0PlotsData(plotsList: List<PlotT0DataModel>) {
    val plotIds = plotsList.map { it.monitoringPlotId }
    val plotMap = getPlotInfo(plotIds)
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
    val speciesNames = getSpeciesNames(speciesToFetch)
    val orgIds = plotMap.values.map { it.organizationId }.toSet()

    rateLimitedEventPublisher.publishEvent(
        RateLimitedT0DataAssignedEvent(
            organizationId = orgIds.first(),
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
                                  .map {
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

  fun assignT0TempStratumData(strataList: List<StratumT0TempDataModel>) {
    val stratumIds = strataList.map { it.stratumId }
    val stratumMap = getStratumInfo(stratumIds)
    val plantingSiteIds = stratumMap.values.map { it.plantingSiteId }.toSet()
    require(plantingSiteIds.size == 1) { "Cannot assign T0 data to strata from multiple sites." }

    val strataChangeList = mutableListOf<StratumT0TempDensityChangedModel>()
    dslContext.transaction { _ ->
      strataList.forEach { model ->
        strataChangeList.add(
            t0Store.assignT0TempStratumSpeciesDensities(model.stratumId, model.densityData)
        )
      }
    }

    val speciesToFetch =
        strataChangeList.flatMap { it.speciesDensityChanges.map { it.speciesId } }.toSet()
    val speciesNames = getSpeciesNames(speciesToFetch)
    val orgIds = stratumMap.values.map { it.organizationId }.toSet()

    rateLimitedEventPublisher.publishEvent(
        RateLimitedT0DataAssignedEvent(
            organizationId = orgIds.first(),
            plantingSiteId = plantingSiteIds.first(),
            strata =
                strataChangeList
                    .map { changedModel ->
                      StratumT0DensityChangedEventModel(
                          stratumId = changedModel.stratumId,
                          stratumName = stratumMap[changedModel.stratumId]!!.stratumName,
                          speciesDensityChanges =
                              changedModel.speciesDensityChanges
                                  .map {
                                    SpeciesDensityChangedEventModel.of(
                                        it,
                                        speciesNames[it.speciesId]!!,
                                    )
                                  }
                                  .sortedBy { it.speciesScientificName },
                      )
                    }
                    .sortedBy { it.stratumName },
        )
    )
  }

  private fun getPlotInfo(
      plotIds: Collection<MonitoringPlotId>
  ): Map<MonitoringPlotId, PlotEventDetailsModel> =
      with(MONITORING_PLOTS) {
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

  private fun getStratumInfo(
      stratumIds: Collection<StratumId>
  ): Map<StratumId, StratumEventDetailsModel> =
      with(STRATA) {
        dslContext
            .select(ID, NAME, plantingSites.ORGANIZATION_ID, PLANTING_SITE_ID)
            .from(this)
            .where(ID.`in`(stratumIds.toSet()))
            .fetchMap(
                ID.asNonNullable(),
                { record ->
                  StratumEventDetailsModel(
                      stratumName = record[NAME.asNonNullable()],
                      organizationId = record[plantingSites.ORGANIZATION_ID.asNonNullable()],
                      plantingSiteId = record[PLANTING_SITE_ID.asNonNullable()],
                  )
                },
            )
      }

  private fun getSpeciesNames(speciesToFetch: Collection<SpeciesId>): Map<SpeciesId, String> =
      with(SPECIES) {
        dslContext
            .select(ID, SCIENTIFIC_NAME)
            .from(this)
            .where(ID.`in`(speciesToFetch))
            .fetchMap(ID.asNonNullable(), SCIENTIFIC_NAME.asNonNullable())
      }
}
