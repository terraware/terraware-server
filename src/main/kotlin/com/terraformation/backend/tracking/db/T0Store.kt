package com.terraformation.backend.tracking.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_OBSERVATIONS
import com.terraformation.backend.tracking.event.ObservationStateUpdatedEvent
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.event.T0ZoneDataAssignedEvent
import com.terraformation.backend.tracking.model.OptionalSpeciesDensityModel
import com.terraformation.backend.tracking.model.PlotSpeciesModel
import com.terraformation.backend.tracking.model.PlotT0DataModel
import com.terraformation.backend.tracking.model.PlotT0DensityChangedModel
import com.terraformation.backend.tracking.model.SiteT0DataModel
import com.terraformation.backend.tracking.model.SpeciesDensityChangedModel
import com.terraformation.backend.tracking.model.SpeciesDensityModel
import com.terraformation.backend.tracking.model.ZoneT0TempDataModel
import com.terraformation.backend.tracking.model.ZoneT0TempDensityChangedModel
import com.terraformation.backend.util.toPlantsPerHectare
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.InstantSource
import kotlin.collections.Map
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

@Named
class T0Store(
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

  fun fetchAllT0SiteDataSet(plantingSiteId: PlantingSiteId): Boolean {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return isAllPermT0DataSet(plantingSiteId) &&
        (!survivalRateIncludesTempPlots(plantingSiteId) || isAllTempT0DataSet(plantingSiteId))
  }

  fun fetchSiteSpeciesByPlot(plantingSiteId: PlantingSiteId): List<PlotSpeciesModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    val densityField =
        DSL.round(
            PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS.cast(SQLDataType.NUMERIC) /
                PLANTING_SUBZONES.AREA_HA,
            1,
        )

    val withdrawnSpecies =
        DSL.select(
                MONITORING_PLOTS.ID,
                PLANTING_SUBZONE_POPULATIONS.SPECIES_ID.`as`("species_id"),
                densityField.`as`("density"),
            )
            .from(MONITORING_PLOTS)
            .join(PLANTING_SUBZONE_POPULATIONS)
            .on(
                MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(
                    PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID
                )
            )
            .join(PLANTING_SUBZONES)
            .on(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
            .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
            .and(densityField.ge(BigDecimal.valueOf(0.05)))

    val observedNotWithdrawnSpecies =
        DSL.select(
                MONITORING_PLOTS.ID,
                OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID.`as`("species_id"),
                DSL.castNull(SQLDataType.NUMERIC).`as`("density"),
            )
            .from(MONITORING_PLOTS)
            .join(OBSERVED_PLOT_SPECIES_TOTALS)
            .on(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
            .join(OBSERVATIONS)
            .on(OBSERVATIONS.ID.eq(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID))
            .where(MONITORING_PLOTS.PLANTING_SITE_ID.eq(plantingSiteId))
            .and(
                OBSERVATIONS.STATE_ID.`in`(
                    listOf(ObservationState.Completed, ObservationState.Abandoned)
                )
            )
            .and(
                DSL.or(
                    OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE.gt(0),
                    OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD.gt(0),
                )
            )
            .andNotExists(
                DSL.selectOne()
                    .from(PLANTING_SUBZONE_POPULATIONS)
                    .join(PLANTING_SUBZONES)
                    .on(PLANTING_SUBZONES.ID.eq(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID))
                    .where(
                        PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(
                                MONITORING_PLOTS.PLANTING_SUBZONE_ID
                            )
                            .and(
                                PLANTING_SUBZONE_POPULATIONS.SPECIES_ID.eq(
                                    OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID
                                )
                            )
                            .and(
                                DSL.round(
                                        PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS.cast(
                                            SQLDataType.NUMERIC
                                        ) / PLANTING_SUBZONES.AREA_HA,
                                        1,
                                    )
                                    .ge(BigDecimal.valueOf(0.05))
                            )
                    )
            )

    val combined = withdrawnSpecies.unionAll(observedNotWithdrawnSpecies).asTable("combined")

    val plotIdField = combined.field(MONITORING_PLOTS.ID)
    val speciesIdField = combined.field("species_id")!!
    val densityResultField = combined.field("density")

    return dslContext.selectFrom(combined).fetchGroups(plotIdField).map { (plotId, records) ->
      PlotSpeciesModel(
          monitoringPlotId = plotId as MonitoringPlotId,
          species =
              records.map { record ->
                OptionalSpeciesDensityModel(
                    speciesId = record[speciesIdField] as SpeciesId,
                    density = record[densityResultField] as BigDecimal?,
                )
              },
      )
    }
  }

  fun assignT0PlotObservation(
      monitoringPlotId: MonitoringPlotId,
      observationId: ObservationId,
  ): PlotT0DensityChangedModel {
    requirePermissions { updateT0(monitoringPlotId) }

    require(isMonitoringPlotPermanent(monitoringPlotId)) {
      "Cannot assign T0 data to non-permanent plot $monitoringPlotId."
    }

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

    // Get species withdrawn to this monitoring plot's subzone that weren't in the observation
    val withdrawnSpeciesNotInObservation: Set<SpeciesId> =
        with(PLANTING_SUBZONE_POPULATIONS) {
          dslContext
              .select(SPECIES_ID)
              .from(this)
              .join(MONITORING_PLOTS)
              .on(PLANTING_SUBZONE_ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID))
              .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
              .and(TOTAL_PLANTS.gt(0))
              .and(SPECIES_ID.notIn(observationDensities.keys))
              .fetchSet(SPECIES_ID.asNonNullable())
        }
    // Get species from later observation that were not included in the first observation
    val observedSpeciesNotInObservation: Set<SpeciesId> =
        with(OBSERVED_PLOT_SPECIES_TOTALS) {
          dslContext
              .select(SPECIES_ID)
              .from(this)
              .join(MONITORING_PLOTS)
              .on(MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
              .join(OBSERVATIONS)
              .on(OBSERVATIONS.ID.eq(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID))
              .where(MONITORING_PLOTS.ID.eq(monitoringPlotId))
              .and(OBSERVATION_ID.ne(observationId))
              .and(
                  OBSERVATIONS.STATE_ID.`in`(
                      listOf(ObservationState.Completed, ObservationState.Abandoned)
                  )
              )
              .and(SPECIES_ID.notIn(observationDensities.keys))
              .and(DSL.or(TOTAL_LIVE.gt(0), TOTAL_DEAD.gt(0)))
              .fetchSet(SPECIES_ID.asNonNullable())
        }
    val speciesNotInObservation =
        withdrawnSpeciesNotInObservation.plus(observedSpeciesNotInObservation)

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

        // Any species withdrawn to the subzone that aren't in the observation need a density of 0
        speciesNotInObservation.forEach { speciesId ->
          insertQuery =
              insertQuery.values(
                  monitoringPlotId,
                  speciesId,
                  BigDecimal.ZERO,
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
        observationDensities.mapValues { it.value.toBigDecimal().toPlantsPerHectare() } +
            speciesNotInObservation.associateWith { BigDecimal.ZERO }
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

    require(isMonitoringPlotPermanent(monitoringPlotId)) {
      "Cannot assign T0 data to non-permanent plot $monitoringPlotId."
    }

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
  ): ZoneT0TempDensityChangedModel {
    requirePermissions { updateT0(plantingZoneId) }

    val now = clock.instant()
    val currentUserId = currentUser().userId

    val existingDensities = fetchExistingDensities(plantingZoneId)

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

      eventPublisher.publishEvent(T0ZoneDataAssignedEvent(plantingZoneId = plantingZoneId))
    }

    val newDensities = densities.associate { it.speciesId to it.density }
    val speciesDensityChanges = buildSpeciesDensityChangeList(existingDensities, newDensities)

    return ZoneT0TempDensityChangedModel(
        plantingZoneId = plantingZoneId,
        speciesDensityChanges = speciesDensityChanges,
    )
  }

  @EventListener
  fun on(event: ObservationStateUpdatedEvent) {
    if (event.newState in listOf(ObservationState.Completed, ObservationState.Abandoned)) {
      assignNewObservationSpeciesZero(event.observationId)
    }
  }

  // only public for testability
  fun assignNewObservationSpeciesZero(observationId: ObservationId) {
    val plantingSiteId =
        with(OBSERVATIONS) {
          dslContext
              .select(PLANTING_SITE_ID)
              .from(this)
              .where(ID.eq(observationId))
              .fetchOne(PLANTING_SITE_ID.asNonNullable())
        }
    val plotsWithT0Observations =
        with(PLOT_T0_OBSERVATIONS) {
          dslContext
              .select(MONITORING_PLOT_ID)
              .from(this)
              .where(monitoringPlots.PLANTING_SITE_ID.eq(plantingSiteId))
              .fetchSet(MONITORING_PLOT_ID.asNonNullable())
        }

    // TODO fix this n+1
    plotsWithT0Observations.forEach { monitoringPlotId ->
      with(OBSERVED_PLOT_SPECIES_TOTALS) {
        dslContext
            .insertInto(
                PLOT_T0_DENSITIES,
                PLOT_T0_DENSITIES.MONITORING_PLOT_ID,
                PLOT_T0_DENSITIES.SPECIES_ID,
                PLOT_T0_DENSITIES.PLOT_DENSITY,
                PLOT_T0_DENSITIES.CREATED_BY,
                PLOT_T0_DENSITIES.CREATED_TIME,
                PLOT_T0_DENSITIES.MODIFIED_BY,
                PLOT_T0_DENSITIES.MODIFIED_TIME,
            )
            .select(
                DSL.select(
                        DSL.value(monitoringPlotId),
                        SPECIES_ID,
                        DSL.value(BigDecimal.ZERO),
                        DSL.value(currentUser().userId),
                        DSL.value(clock.instant()),
                        DSL.value(currentUser().userId),
                        DSL.value(clock.instant()),
                    )
                    .from(OBSERVED_PLOT_SPECIES_TOTALS)
                    .where(MONITORING_PLOT_ID.eq(monitoringPlotId))
                    .and(OBSERVATION_ID.eq(observationId))
                    .and(
                        SPECIES_ID.notIn(
                            DSL.select(PLOT_T0_DENSITIES.SPECIES_ID)
                                .from(PLOT_T0_DENSITIES)
                                .where(MONITORING_PLOT_ID.eq(monitoringPlotId))
                        )
                    )
            )
            .execute()
      }
    }
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
                  .orderBy(monitoringPlots.PLOT_NUMBER, SPECIES_ID)
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
                  .orderBy(plantingZones.NAME, SPECIES_ID)
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

  private fun fetchExistingDensities(plantingZoneId: PlantingZoneId): Map<SpeciesId, BigDecimal> =
      with(PLANTING_ZONE_T0_TEMP_DENSITIES) {
        dslContext
            .select(SPECIES_ID, ZONE_DENSITY)
            .from(this)
            .where(PLANTING_ZONE_ID.eq(plantingZoneId))
            .fetchMap(SPECIES_ID.asNonNullable(), ZONE_DENSITY.asNonNullable())
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
                previousDensity = previous,
                newDensity = newDensity,
            )
      }
    }

    existingDensities.keys
        .filter { it !in newDensities }
        .forEach { speciesId ->
          changeList[speciesId] =
              SpeciesDensityChangedModel(
                  speciesId,
                  previousDensity = existingDensities[speciesId],
              )
        }

    return changeList.values.toSet()
  }

  private fun isMonitoringPlotPermanent(monitoringPlotId: MonitoringPlotId): Boolean {
    return with(MONITORING_PLOTS) {
      dslContext.fetchExists(
          this,
          ID.eq(monitoringPlotId).and(PERMANENT_INDEX.isNotNull),
      )
    }
  }

  private fun isAllPermT0DataSet(plantingSiteId: PlantingSiteId): Boolean {

    val permanentPlotSpecies =
        with(MONITORING_PLOTS) {
          plotSpeciesWithObservations(plantingSiteId)
              .unionAll(recordedObservationSpecies(plantingSiteId))
              .unionAll(
                  DSL.selectDistinct(
                          ID.`as`("plot_id"),
                          PLOT_T0_DENSITIES.SPECIES_ID.`as`("species_id"),
                      )
                      .from(MONITORING_PLOTS)
                      .join(PLOT_T0_DENSITIES)
                      .on(ID.eq(PLOT_T0_DENSITIES.MONITORING_PLOT_ID))
                      .where(PLANTING_SITE_ID.eq(plantingSiteId))
              )
              .asTable("permanent_plot_species")
        }

    val permPlotIdField = permanentPlotSpecies.field("plot_id", MONITORING_PLOTS.ID.dataType)
    val permSpeciesIdField = permanentPlotSpecies.field("species_id", SpeciesId::class.java)!!

    return !with(MONITORING_PLOTS) {
      dslContext.fetchExists(
          DSL.selectOne()
              .from(MONITORING_PLOTS)
              .leftJoin(permanentPlotSpecies)
              .on(ID.eq(permPlotIdField))
              .leftJoin(PLOT_T0_DENSITIES)
              .on(
                  ID.eq(PLOT_T0_DENSITIES.MONITORING_PLOT_ID)
                      .and(permSpeciesIdField.eq(PLOT_T0_DENSITIES.SPECIES_ID))
              )
              .leftJoin(PLANTING_SUBZONE_POPULATIONS)
              .on(
                  PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID)
                      .and(permSpeciesIdField.eq(PLANTING_SUBZONE_POPULATIONS.SPECIES_ID))
              )
              .leftJoin(PLANTING_SUBZONES)
              .on(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
              .join(OBSERVATION_PLOTS)
              .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(ID))
              .join(OBSERVATIONS)
              .on(OBSERVATIONS.ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID))
              .where(PLANTING_SITE_ID.eq(plantingSiteId))
              .and(IS_AD_HOC.eq(false))
              .and(PERMANENT_INDEX.isNotNull)
              .and(PLOT_T0_DENSITIES.PLOT_DENSITY.isNull)
              .and(
                  OBSERVATIONS.STATE_ID.`in`(
                      listOf(ObservationState.Completed, ObservationState.Abandoned)
                  )
              )
      )
    }
  }

  private fun isAllTempT0DataSet(plantingSiteId: PlantingSiteId): Boolean {
    val tempPlotSpecies =
        with(MONITORING_PLOTS) {
          plotSpeciesWithObservations(plantingSiteId)
              .unionAll(recordedObservationSpecies(plantingSiteId))
              .unionAll(
                  DSL.selectDistinct(
                          ID.`as`("plot_id"),
                          PLANTING_ZONE_T0_TEMP_DENSITIES.SPECIES_ID.`as`("species_id"),
                      )
                      .from(MONITORING_PLOTS)
                      .join(plantingSubzones)
                      .on(PLANTING_SUBZONE_ID.eq(plantingSubzones.ID))
                      .join(PLANTING_ZONE_T0_TEMP_DENSITIES)
                      .on(
                          plantingSubzones.PLANTING_ZONE_ID.eq(
                              PLANTING_ZONE_T0_TEMP_DENSITIES.PLANTING_ZONE_ID
                          )
                      )
                      .join(OBSERVATION_PLOTS)
                      .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(ID))
                      .join(OBSERVATIONS)
                      .on(OBSERVATIONS.ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID))
                      .where(PLANTING_SITE_ID.eq(plantingSiteId))
                      .and(
                          OBSERVATIONS.STATE_ID.`in`(
                              listOf(ObservationState.Completed, ObservationState.Abandoned)
                          )
                      )
              )
              .asTable("plot_species")
        }

    val tempPlotIdField = tempPlotSpecies.field("plot_id", MONITORING_PLOTS.ID.dataType)
    val tempSpeciesIdField = tempPlotSpecies.field("species_id", SpeciesId::class.java)!!

    return !with(MONITORING_PLOTS) {
      dslContext.fetchExists(
          DSL.selectOne()
              .from(MONITORING_PLOTS)
              .leftJoin(tempPlotSpecies)
              .on(ID.eq(tempPlotIdField))
              .leftJoin(PLANTING_ZONE_T0_TEMP_DENSITIES)
              .on(
                  plantingSubzones.PLANTING_ZONE_ID.eq(
                          PLANTING_ZONE_T0_TEMP_DENSITIES.PLANTING_ZONE_ID
                      )
                      .and(tempSpeciesIdField.eq(PLANTING_ZONE_T0_TEMP_DENSITIES.SPECIES_ID))
              )
              .leftJoin(PLANTING_SUBZONE_POPULATIONS)
              .on(
                  PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID)
                      .and(tempSpeciesIdField.eq(PLANTING_SUBZONE_POPULATIONS.SPECIES_ID))
              )
              .leftJoin(PLANTING_SUBZONES)
              .on(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
              .leftJoin(OBSERVATION_PLOTS)
              .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(ID))
              .leftJoin(OBSERVATIONS)
              .on(OBSERVATIONS.ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID))
              .where(PLANTING_SITE_ID.eq(plantingSiteId))
              .and(IS_AD_HOC.eq(false))
              .and(PERMANENT_INDEX.isNull)
              .and(PLANTING_ZONE_T0_TEMP_DENSITIES.ZONE_DENSITY.isNull)
              .and(
                  OBSERVATIONS.STATE_ID.`in`(
                      listOf(ObservationState.Completed, ObservationState.Abandoned)
                  )
              )
      )
    }
  }

  private fun plotSpeciesWithObservations(plantingSiteId: PlantingSiteId) =
      with(MONITORING_PLOTS) {
        DSL.selectDistinct(
                ID.`as`("plot_id"),
                PLANTING_SUBZONE_POPULATIONS.SPECIES_ID.`as`("species_id"),
            )
            .from(MONITORING_PLOTS)
            .join(PLANTING_SUBZONE_POPULATIONS)
            .on(PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID))
            .where(PLANTING_SITE_ID.eq(plantingSiteId))
            .and(
                DSL.exists(
                    DSL.selectOne()
                        .from(MONITORING_PLOTS)
                        .join(OBSERVATION_PLOTS)
                        .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(ID))
                        .join(OBSERVATIONS)
                        .on(OBSERVATIONS.ID.eq(OBSERVATION_PLOTS.OBSERVATION_ID))
                        .where(
                            OBSERVATIONS.STATE_ID.`in`(
                                listOf(ObservationState.Completed, ObservationState.Abandoned)
                            )
                        )
                )
            )
      }

  private fun recordedObservationSpecies(plantingSiteId: PlantingSiteId) =
      with(MONITORING_PLOTS) {
        DSL.selectDistinct(
                ID.`as`("plot_id"),
                OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID.`as`("species_id"),
            )
            .from(MONITORING_PLOTS)
            .join(OBSERVED_PLOT_SPECIES_TOTALS)
            .on(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(ID))
            .join(OBSERVATIONS)
            .on(OBSERVATIONS.ID.eq(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID))
            .where(PLANTING_SITE_ID.eq(plantingSiteId))
            .and(
                OBSERVATIONS.STATE_ID.`in`(
                    listOf(ObservationState.Completed, ObservationState.Abandoned)
                )
            )
            .and(
                DSL.or(
                    OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE.gt(0),
                    OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD.gt(0),
                )
            )
      }

  private fun survivalRateIncludesTempPlots(plantingSiteId: PlantingSiteId): Boolean =
      dslContext
          .select(PLANTING_SITES.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS)
          .from(PLANTING_SITES)
          .where(PLANTING_SITES.ID.eq(plantingSiteId))
          .fetchOne(PLANTING_SITES.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.asNonNullable())!!
}
