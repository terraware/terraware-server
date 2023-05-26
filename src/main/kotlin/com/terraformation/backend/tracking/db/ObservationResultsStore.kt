package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PHOTOS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.tracking.model.MONITORING_PLOTS_PER_HECTARE
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotPhotoPayload
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsPayload
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotStatus
import com.terraformation.backend.tracking.model.ObservationPlantingSubzoneResultsPayload
import com.terraformation.backend.tracking.model.ObservationPlantingZoneResultsPayload
import com.terraformation.backend.tracking.model.ObservationResultsPayload
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsPayload
import java.math.BigDecimal
import javax.inject.Named
import kotlin.math.roundToInt
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Polygon

@Named
class ObservationResultsStore(private val dslContext: DSLContext) {
  private val photosMultiset =
      DSL.multiset(
              DSL.select(OBSERVATION_PHOTOS.FILE_ID)
                  .from(OBSERVATION_PHOTOS)
                  .where(OBSERVATION_PHOTOS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .and(OBSERVATION_PHOTOS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID)))
          .convertFrom { result ->
            result.map { record ->
              ObservationMonitoringPlotPhotoPayload(
                  fileId = record[OBSERVATION_PHOTOS.FILE_ID.asNonNullable()])
            }
          }

  private val monitoringPlotSpeciesMultiset =
      DSL.multiset(
              DSL.select(
                      OBSERVED_PLOT_SPECIES_TOTALS.MORTALITY_RATE,
                      OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                      OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD,
                      OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_PLANTS)
                  .from(OBSERVED_PLOT_SPECIES_TOTALS)
                  .where(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .and(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .orderBy(OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID))
          .convertFrom { results ->
            results.map { record ->
              ObservationSpeciesResultsPayload(
                  mortalityRate =
                      record[OBSERVED_PLOT_SPECIES_TOTALS.MORTALITY_RATE.asNonNullable()],
                  speciesId = record[OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID.asNonNullable()],
                  totalDead = record[OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD.asNonNullable()],
                  totalPlants = record[OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_PLANTS.asNonNullable()],
              )
            }
          }

  private val monitoringPlotsBoundaryField = MONITORING_PLOTS.BOUNDARY.forMultiset()

  private val monitoringPlotMultiset =
      DSL.multiset(
              DSL.select(
                      USERS.FIRST_NAME,
                      USERS.LAST_NAME,
                      OBSERVATION_PLOTS.CLAIMED_BY,
                      OBSERVATION_PLOTS.COMPLETED_TIME,
                      OBSERVATION_PLOTS.IS_PERMANENT,
                      OBSERVATION_PLOTS.NOTES,
                      monitoringPlotsBoundaryField,
                      MONITORING_PLOTS.ID,
                      MONITORING_PLOTS.FULL_NAME,
                      monitoringPlotSpeciesMultiset,
                      photosMultiset)
                  .from(OBSERVATION_PLOTS)
                  .join(MONITORING_PLOTS)
                  .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .leftJoin(USERS)
                  .on(OBSERVATION_PLOTS.CLAIMED_BY.eq(USERS.ID))
                  .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .and(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID)))
          .convertFrom { results ->
            results.map { record ->
              val claimedBy = record[OBSERVATION_PLOTS.CLAIMED_BY]
              val completedTime = record[OBSERVATION_PLOTS.COMPLETED_TIME]
              val species = record[monitoringPlotSpeciesMultiset]
              val totalIdentifiedPlants = species.sumOf { it.totalPlants }
              val totalSpecies = species.distinctBy { it.speciesId }.count()

              val mortalityRate =
                  if (totalIdentifiedPlants > 0) {
                    species.sumOf { it.totalDead } * 100 / totalIdentifiedPlants
                  } else {
                    0
                  }

              // TODO: These need to count unidentified and existing plants too
              val totalPlants = totalIdentifiedPlants
              val plantingDensity = (totalPlants * MONITORING_PLOTS_PER_HECTARE).roundToInt()

              val status =
                  when {
                    completedTime != null -> ObservationMonitoringPlotStatus.Completed
                    claimedBy != null -> ObservationMonitoringPlotStatus.InProgress
                    else -> ObservationMonitoringPlotStatus.Outstanding
                  }

              ObservationMonitoringPlotResultsPayload(
                  boundary = record[monitoringPlotsBoundaryField] as Polygon,
                  claimedByName =
                      IndividualUser.makeFullName(
                          record[USERS.FIRST_NAME], record[USERS.LAST_NAME]),
                  claimedByUserId = claimedBy,
                  completedTime = completedTime,
                  isPermanent = record[OBSERVATION_PLOTS.IS_PERMANENT.asNonNullable()],
                  monitoringPlotId = record[MONITORING_PLOTS.ID.asNonNullable()],
                  monitoringPlotName = record[MONITORING_PLOTS.FULL_NAME.asNonNullable()],
                  mortalityRate = mortalityRate,
                  notes = record[OBSERVATION_PLOTS.NOTES],
                  photos = record[photosMultiset],
                  plantingDensity = plantingDensity,
                  species = species,
                  status = status,
                  totalPlants = totalPlants,
                  totalSpecies = totalSpecies)
            }
          }

  private val plantingSubzoneMultiset =
      DSL.multiset(
              DSL.select(PLANTING_SUBZONES.ID, monitoringPlotMultiset)
                  .from(PLANTING_SUBZONES)
                  .where(
                      PLANTING_SUBZONES.ID.`in`(
                          DSL.select(MONITORING_PLOTS.PLANTING_SUBZONE_ID)
                              .from(MONITORING_PLOTS)
                              .join(OBSERVATION_PLOTS)
                              .on(MONITORING_PLOTS.ID.eq(OBSERVATION_PLOTS.MONITORING_PLOT_ID))
                              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID)))))
          .convertFrom { results ->
            results.map { record ->
              ObservationPlantingSubzoneResultsPayload(
                  monitoringPlots = record[monitoringPlotMultiset],
                  plantingSubzoneId = record[PLANTING_SUBZONES.ID.asNonNullable()],
              )
            }
          }

  private val plantingZoneSpeciesMultiset =
      DSL.multiset(
              DSL.select(
                      OBSERVED_ZONE_SPECIES_TOTALS.MORTALITY_RATE,
                      OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID,
                      OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_DEAD,
                      OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_PLANTS)
                  .from(OBSERVED_ZONE_SPECIES_TOTALS)
                  .where(OBSERVED_ZONE_SPECIES_TOTALS.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
                  .and(OBSERVED_ZONE_SPECIES_TOTALS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .orderBy(OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID))
          .convertFrom { results ->
            results.map { record ->
              ObservationSpeciesResultsPayload(
                  mortalityRate =
                      record[OBSERVED_ZONE_SPECIES_TOTALS.MORTALITY_RATE.asNonNullable()],
                  speciesId = record[OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID.asNonNullable()],
                  totalDead = record[OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_DEAD.asNonNullable()],
                  totalPlants = record[OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_PLANTS.asNonNullable()],
              )
            }
          }

  private val zoneFinishedPlantingField =
      DSL.field(
          DSL.notExists(
              DSL.selectOne()
                  .from(PLANTING_SUBZONES)
                  .where(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
                  .and(PLANTING_SUBZONES.FINISHED_PLANTING.isFalse)))

  private val plantingZoneMultiset =
      DSL.multiset(
              DSL.select(
                      PLANTING_ZONES.AREA_HA,
                      PLANTING_ZONES.ID,
                      plantingSubzoneMultiset,
                      plantingZoneSpeciesMultiset,
                      zoneFinishedPlantingField,
                  )
                  .from(PLANTING_ZONES)
                  .where(PLANTING_ZONES.PLANTING_SITE_ID.eq(OBSERVATIONS.PLANTING_SITE_ID))
                  .and(
                      PLANTING_ZONES.ID.`in`(
                          DSL.select(PLANTING_SUBZONES.PLANTING_ZONE_ID)
                              .from(OBSERVATION_PLOTS)
                              .join(MONITORING_PLOTS)
                              .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                              .join(PLANTING_SUBZONES)
                              .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID)))))
          .convertFrom { results ->
            results.map { record ->
              val species = record[plantingZoneSpeciesMultiset]
              val subzones = record[plantingSubzoneMultiset]

              val isCompleted =
                  subzones.all { subzone ->
                    subzone.monitoringPlots.all { it.completedTime != null }
                  }
              val completedTime =
                  if (isCompleted) {
                    subzones.maxOf { subzone ->
                      subzone.monitoringPlots.maxOf { it.completedTime!! }
                    }
                  } else {
                    null
                  }

              val totalIdentifiedPlants =
                  subzones.sumOf { subzone -> subzone.monitoringPlots.sumOf { it.totalPlants } }
              val totalSpecies =
                  subzones
                      .flatMap { subzone ->
                        subzone.monitoringPlots.flatMap { plot ->
                          plot.species.map { it.speciesId }
                        }
                      }
                      .distinct()
                      .count()

              val mortalityRate =
                  if (totalIdentifiedPlants > 0) {
                    species.sumOf { it.totalDead } * 100 / totalIdentifiedPlants
                  } else {
                    0
                  }

              // TODO: These need to count unidentified and existing plants too
              val totalPlants = totalIdentifiedPlants
              val plantingDensity =
                  if (record[zoneFinishedPlantingField]) {
                    (BigDecimal(totalPlants) / record[PLANTING_ZONES.AREA_HA.asNonNullable()])
                        .toInt()
                  } else {
                    null
                  }

              ObservationPlantingZoneResultsPayload(
                  completedTime = completedTime,
                  mortalityRate = mortalityRate,
                  plantingDensity = plantingDensity,
                  plantingSubzones = subzones,
                  plantingZoneId = record[PLANTING_ZONES.ID.asNonNullable()],
                  species = species,
                  totalPlants = totalPlants,
                  totalSpecies = totalSpecies)
            }
          }

  private fun fetchByCondition(condition: Condition): List<ObservationResultsPayload> {
    return dslContext
        .select(
            OBSERVATIONS.ID,
            OBSERVATIONS.PLANTING_SITE_ID,
            OBSERVATIONS.START_DATE,
            OBSERVATIONS.STATE_ID,
            plantingZoneMultiset)
        .from(OBSERVATIONS)
        .where(condition)
        .orderBy(OBSERVATIONS.ID)
        .fetch { record ->
          val zones = record[plantingZoneMultiset]

          val isCompleted = zones.all { it.completedTime != null }
          val completedTime =
              if (isCompleted) {
                zones.maxOf { it.completedTime!! }
              } else {
                null
              }

          val totalIdentifiedPlants = zones.sumOf { it.totalPlants }
          val totalSpecies =
              zones.flatMap { zone -> zone.species.map { it.speciesId } }.distinct().count()

          val totalDead =
              zones.sumOf { zone ->
                zone.plantingSubzones.sumOf { subzone ->
                  subzone.monitoringPlots.sumOf { plot -> plot.species.sumOf { it.totalDead } }
                }
              }
          val mortalityRate =
              if (totalIdentifiedPlants > 0) {
                totalDead * 100 / totalIdentifiedPlants
              } else {
                0
              }

          // TODO: These need to count unidentified and existing plants too
          val totalPlants = totalIdentifiedPlants

          ObservationResultsPayload(
              completedTime = completedTime,
              mortalityRate = mortalityRate,
              observationId = record[OBSERVATIONS.ID.asNonNullable()],
              plantingSiteId = record[OBSERVATIONS.PLANTING_SITE_ID.asNonNullable()],
              plantingZones = zones,
              startDate = record[OBSERVATIONS.START_DATE.asNonNullable()],
              state = record[OBSERVATIONS.STATE_ID.asNonNullable()],
              totalPlants = totalPlants,
              totalSpecies = totalSpecies,
          )
        }
  }

  fun fetchOneById(observationId: ObservationId): ObservationResultsPayload {
    requirePermissions { readObservation(observationId) }

    return fetchByCondition(OBSERVATIONS.ID.eq(observationId)).first()
  }

  fun fetchByPlantingSiteId(plantingSiteId: PlantingSiteId): List<ObservationResultsPayload> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchByCondition(OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId))
  }

  fun fetchByOrganizationId(organizationId: OrganizationId): List<ObservationResultsPayload> {
    requirePermissions { readOrganization(organizationId) }

    return fetchByCondition(OBSERVATIONS.plantingSites.ORGANIZATION_ID.eq(organizationId))
  }
}
