package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedModel
import com.terraformation.backend.tracking.model.SiteT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import com.terraformation.backend.tracking.model.ZoneT0TempDataModel
import com.terraformation.backend.util.toPlantsPerHectare
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.InstantSource
import kotlin.collections.Map
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher

@Named
class T0PlotStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
) {
  fun fetchT0SiteData(plantingSiteId: PlantingSiteId): SiteT0DataModel {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val plotMultiset = plotT0Multiset(plantingSiteId)
    val zoneMultiset = zoneT0TempMultiset(plantingSiteId)

    return with(PLANTING_SITES) {
      dslContext
          .select(
              SURVIVAL_RATE_INCLUDES_TEMP_PLOTS,
              plotMultiset,
              zoneMultiset,
          )
          .from(this)
          .where(ID.eq(plantingSiteId))
          .fetchOne { record ->
            SiteT0DataModel(
                plantingSiteId = plantingSiteId,
                survivalRateIncludesTempPlots = record[SURVIVAL_RATE_INCLUDES_TEMP_PLOTS]!!,
                plots = record[plotMultiset]!!,
                zones = record[zoneMultiset]!!,
            )
          }
    } ?: throw PlantingSiteNotFoundException(plantingSiteId)
  }

  fun assignT0PlotObservation(
      monitoringPlotId: MonitoringPlotId,
      observationId: ObservationId,
  ): PlotT0DensityChangedModel {
    requirePermissions { updateT0(monitoringPlotId) }

    val now = clock.instant()
    val currentUserId = currentUser().userId

    val existingDensities = fetchExistingDensities(monitoringPlotId)
    val observationDensities: Map<SpeciesId, Int> =
        with(OBSERVED_PLOT_SPECIES_TOTALS) {
          dslContext
              .select(
                  SPECIES_ID,
                  TOTAL_LIVE.plus(TOTAL_DEAD),
              )
              .from(this)
              .where(
                  OBSERVATION_ID.eq(observationId)
                      .and(MONITORING_PLOT_ID.eq(monitoringPlotId))
                      .and(SPECIES_ID.isNotNull)
              )
              .fetchMap(SPECIES_ID.asNonNullable(), TOTAL_LIVE.plus(TOTAL_DEAD).asNonNullable())
        }

    dslContext.transaction { _ ->
      with(PLOT_T0_OBSERVATIONS) {
        dslContext
            .insertInto(this)
            .set(MONITORING_PLOT_ID, monitoringPlotId)
            .set(OBSERVATION_ID, observationId)
            .set(CREATED_BY, currentUserId)
            .set(CREATED_TIME, now)
            .set(MODIFIED_BY, currentUserId)
            .set(MODIFIED_TIME, now)
            .onDuplicateKeyUpdate()
            .set(OBSERVATION_ID, observationId)
            .set(MODIFIED_BY, currentUserId)
            .set(MODIFIED_TIME, now)
            .execute()
      }

      with(PLOT_T0_DENSITIES) {
        // ensure no leftover densities for species that are not in this observation
        dslContext
            .deleteFrom(this)
            .where(MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(
                SPECIES_ID.notIn(
                    DSL.select(OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID)
                        .from(OBSERVED_PLOT_SPECIES_TOTALS)
                        .where(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(observationId))
                        .and(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(monitoringPlotId))
                        .and(OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID.isNotNull)
                )
            )
            .execute()

        var insertQuery =
            dslContext.insertInto(
                this,
                MONITORING_PLOT_ID,
                SPECIES_ID,
                PLOT_DENSITY,
                CREATED_BY,
                CREATED_TIME,
                MODIFIED_BY,
                MODIFIED_TIME,
            )

        observationDensities.forEach { (speciesId, observationDensity) ->
          insertQuery =
              insertQuery.values(
                  monitoringPlotId,
                  speciesId,
                  observationDensity.toBigDecimal().toPlantsPerHectare(),
                  currentUserId,
                  now,
                  currentUserId,
                  now,
              )
        }

        insertQuery
            .onDuplicateKeyUpdate()
            .set(PLOT_DENSITY, DSL.excluded(PLOT_DENSITY))
            .set(MODIFIED_BY, currentUserId)
            .set(MODIFIED_TIME, now)
            .execute()

        eventPublisher.publishEvent(
            T0PlotDataAssignedEvent(
                monitoringPlotId = monitoringPlotId,
                observationId = observationId,
            )
        )
      }
    }

    val newDensities =
        observationDensities.mapValues { it.value.toBigDecimal().toPlantsPerHectare() }
    val speciesDensityChanges = buildSpeciesDensityChangeList(existingDensities, newDensities)

    return PlotT0DensityChangedModel(
        monitoringPlotId = monitoringPlotId,
        speciesDensityChanges = speciesDensityChanges,
    )
  }

  fun assignT0PlotSpeciesDensities(
      monitoringPlotId: MonitoringPlotId,
      densities: List<SpeciesDensityModel>,
  ): PlotT0DensityChangedModel {
    requirePermissions { updateT0(monitoringPlotId) }

    val now = clock.instant()
    val currentUserId = currentUser().userId

    val existingDensities = fetchExistingDensities(monitoringPlotId)

    dslContext.transaction { _ ->
      with(PLOT_T0_OBSERVATIONS) {
        // delete t0 observations associated with this plot so that retrieval doesn't return
        // incorrect data
        dslContext.deleteFrom(this).where(MONITORING_PLOT_ID.eq(monitoringPlotId)).execute()
      }

      with(PLOT_T0_DENSITIES) {
        // ensure no leftover densities for species that are not in this request
        dslContext
            .deleteFrom(this)
            .where(MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(SPECIES_ID.notIn(densities.map { it.speciesId }))
            .execute()

        var insertQuery =
            dslContext.insertInto(
                this,
                MONITORING_PLOT_ID,
                SPECIES_ID,
                PLOT_DENSITY,
                CREATED_BY,
                CREATED_TIME,
                MODIFIED_BY,
                MODIFIED_TIME,
            )

        densities.forEach {
          if (it.density < BigDecimal.ZERO) {
            throw IllegalArgumentException("Plot density must not be negative")
          }
          insertQuery =
              insertQuery.values(
                  monitoringPlotId,
                  it.speciesId,
                  it.density,
                  currentUserId,
                  now,
                  currentUserId,
                  now,
              )
        }

        insertQuery
            .onDuplicateKeyUpdate()
            .set(PLOT_DENSITY, DSL.excluded(PLOT_DENSITY))
            .set(MODIFIED_BY, currentUserId)
            .set(MODIFIED_TIME, now)
            .execute()
      }

      eventPublisher.publishEvent(T0PlotDataAssignedEvent(monitoringPlotId = monitoringPlotId))
    }

    val newDensities = densities.associate { it.speciesId to it.density }
    val speciesDensityChanges = buildSpeciesDensityChangeList(existingDensities, newDensities)

    return PlotT0DensityChangedModel(
        monitoringPlotId = monitoringPlotId,
        speciesDensityChanges = speciesDensityChanges,
    )
  }

  fun assignT0TempZoneSpeciesDensities(
      plantingZoneId: PlantingZoneId,
      densities: List<SpeciesDensityModel>,
  ) {
    requirePermissions { updateT0(plantingZoneId) }

    val now = clock.instant()
    val currentUserId = currentUser().userId

    dslContext.transaction { _ ->
      with(PLANTING_ZONE_T0_TEMP_DENSITIES) {
        dslContext
            .deleteFrom(this)
            .where(PLANTING_ZONE_ID.eq(plantingZoneId))
            .and(SPECIES_ID.notIn(densities.map { it.speciesId }))
            .execute()

        var insertQuery =
            dslContext.insertInto(
                PLANTING_ZONE_T0_TEMP_DENSITIES,
                PLANTING_ZONE_ID,
                SPECIES_ID,
                ZONE_DENSITY,
                CREATED_BY,
                CREATED_TIME,
                MODIFIED_BY,
                MODIFIED_TIME,
            )

        densities.forEach { model ->
          if (model.density < BigDecimal.ZERO) {
            throw IllegalArgumentException("Zone density must not be negative")
          }
          insertQuery =
              insertQuery.values(
                  plantingZoneId,
                  model.speciesId,
                  model.density,
                  currentUserId,
                  now,
                  currentUserId,
                  now,
              )
        }

        insertQuery
            .onDuplicateKeyUpdate()
            .set(ZONE_DENSITY, DSL.excluded(ZONE_DENSITY))
            .set(MODIFIED_BY, currentUserId)
            .set(MODIFIED_TIME, now)
            .execute()
      }

      // future PR: publish event here
    }

    // future PR: return changed model
  }

  private fun plotT0Multiset(plantingSiteId: PlantingSiteId): Field<List<PlotT0DataModel>> {
    return with(PLOT_T0_DENSITIES) {
      DSL.multiset(
              DSL.select(
                      MONITORING_PLOT_ID,
                      SPECIES_ID,
                      PLOT_DENSITY,
                      PLOT_T0_OBSERVATIONS.OBSERVATION_ID,
                  )
                  .from(this)
                  .leftJoin(PLOT_T0_OBSERVATIONS)
                  .on(MONITORING_PLOT_ID.eq(PLOT_T0_OBSERVATIONS.MONITORING_PLOT_ID))
                  .where(monitoringPlots.PLANTING_SITE_ID.eq(plantingSiteId))
                  .orderBy(monitoringPlots.PLOT_NUMBER)
          )
          .convertFrom { records ->
            records
                .groupBy { it[MONITORING_PLOT_ID.asNonNullable()] }
                .map { (monitoringPlotId, results) ->
                  PlotT0DataModel(
                      monitoringPlotId = monitoringPlotId,
                      observationId = results.first()[PLOT_T0_OBSERVATIONS.OBSERVATION_ID],
                      densityData =
                          results.map { record ->
                            SpeciesDensityModel(
                                speciesId = record[SPECIES_ID]!!,
                                density = record[PLOT_DENSITY]!!,
                            )
                          },
                  )
                }
          }
    }
  }

  private fun zoneT0TempMultiset(plantingSiteId: PlantingSiteId): Field<List<ZoneT0TempDataModel>> {
    return with(PLANTING_ZONE_T0_TEMP_DENSITIES) {
      DSL.multiset(
              DSL.select(PLANTING_ZONE_ID, SPECIES_ID, ZONE_DENSITY)
                  .from(this)
                  .where(plantingZones.PLANTING_SITE_ID.eq(plantingSiteId))
                  .orderBy(plantingZones.NAME)
          )
          .convertFrom { records ->
            records
                .groupBy { it[PLANTING_ZONE_ID.asNonNullable()] }
                .map { (plantingZoneId, results) ->
                  ZoneT0TempDataModel(
                      plantingZoneId = plantingZoneId,
                      densityData =
                          results.map { record ->
                            SpeciesDensityModel(
                                speciesId = record[SPECIES_ID]!!,
                                density = record[ZONE_DENSITY]!!,
                            )
                          },
                  )
                }
          }
    }
  }

  private fun fetchExistingDensities(
      monitoringPlotId: MonitoringPlotId
  ): Map<SpeciesId, BigDecimal> =
      with(PLOT_T0_DENSITIES) {
        dslContext
            .select(SPECIES_ID, PLOT_DENSITY)
            .from(this)
            .where(MONITORING_PLOT_ID.eq(monitoringPlotId))
            .fetchMap(SPECIES_ID.asNonNullable(), PLOT_DENSITY.asNonNullable())
      }

  private fun buildSpeciesDensityChangeList(
      existingDensities: Map<SpeciesId, BigDecimal>,
      newDensities: Map<SpeciesId, BigDecimal>,
  ): Set<SpeciesDensityChangedModel> {
    val changeList = mutableMapOf<SpeciesId, SpeciesDensityChangedModel>()
    for ((speciesId, newDensity) in newDensities) {
      val previous = existingDensities[speciesId]
      if (previous != newDensity) {
        changeList[speciesId] =
            SpeciesDensityChangedModel(
                speciesId,
                previousPlotDensity = previous,
                newPlotDensity = newDensity,
            )
      }
    }

    existingDensities.keys
        .filter { it !in newDensities }
        .forEach { speciesId ->
          changeList[speciesId] =
              SpeciesDensityChangedModel(
                  speciesId,
                  previousPlotDensity = existingDensities[speciesId],
              )
        }

    return changeList.values.toSet()
  }
}
