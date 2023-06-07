package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
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
                      OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID,
                      OBSERVED_PLOT_SPECIES_TOTALS.MORTALITY_RATE,
                      OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                      OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_NAME,
                      OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD,
                      OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_EXISTING,
                      OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE)
                  .from(OBSERVED_PLOT_SPECIES_TOTALS)
                  .where(OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .and(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .orderBy(
                      OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
                      OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_NAME))
          .convertFrom { results ->
            results.map { record ->
              val certainty = record[OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID.asNonNullable()]
              val totalLive = record[OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE.asNonNullable()]
              val totalDead = record[OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_DEAD.asNonNullable()]
              val totalExisting =
                  record[OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_EXISTING.asNonNullable()]
              val totalPlants = totalLive + totalExisting
              val mortalityRate =
                  if (certainty == RecordedSpeciesCertainty.Known) {
                    record[OBSERVED_PLOT_SPECIES_TOTALS.MORTALITY_RATE.asNonNullable()]
                  } else {
                    null
                  }

              ObservationSpeciesResultsPayload(
                  certainty = certainty,
                  mortalityRate = mortalityRate,
                  speciesId = record[OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID],
                  speciesName = record[OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_NAME],
                  totalDead = totalDead,
                  totalExisting = totalExisting,
                  totalLive = totalLive,
                  totalPlants = totalPlants,
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
              val totalLive = species.sumOf { it.totalLive }
              val totalPlants = species.sumOf { it.totalPlants + it.totalDead }
              val totalLiveSpeciesExceptUnknown =
                  species.count {
                    it.certainty != RecordedSpeciesCertainty.Unknown && it.totalPlants > 0
                  }

              val mortalityRate = calculateMortalityRate(species)

              val plantingDensity = (totalLive * MONITORING_PLOTS_PER_HECTARE).roundToInt()

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
                  totalSpecies = totalLiveSpeciesExceptUnknown,
              )
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
                              .join(PLANTING_SUBZONES)
                              .on(MONITORING_PLOTS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                              .and(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID)))))
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
                      OBSERVED_ZONE_SPECIES_TOTALS.CERTAINTY_ID,
                      OBSERVED_ZONE_SPECIES_TOTALS.MORTALITY_RATE,
                      OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID,
                      OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_NAME,
                      OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_DEAD,
                      OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_EXISTING,
                      OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_LIVE)
                  .from(OBSERVED_ZONE_SPECIES_TOTALS)
                  .where(OBSERVED_ZONE_SPECIES_TOTALS.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
                  .and(OBSERVED_ZONE_SPECIES_TOTALS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .orderBy(OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID))
          .convertFrom { results ->
            results.map { record ->
              val certainty = record[OBSERVED_ZONE_SPECIES_TOTALS.CERTAINTY_ID.asNonNullable()]
              val totalLive = record[OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_LIVE.asNonNullable()]
              val totalDead = record[OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_DEAD.asNonNullable()]
              val totalExisting =
                  record[OBSERVED_ZONE_SPECIES_TOTALS.TOTAL_EXISTING.asNonNullable()]
              val totalPlants = totalLive + totalExisting
              val mortalityRate =
                  if (certainty == RecordedSpeciesCertainty.Known) {
                    record[OBSERVED_ZONE_SPECIES_TOTALS.MORTALITY_RATE.asNonNullable()]
                  } else {
                    null
                  }
              ObservationSpeciesResultsPayload(
                  certainty = certainty,
                  mortalityRate = mortalityRate,
                  speciesId = record[OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_ID],
                  speciesName = record[OBSERVED_ZONE_SPECIES_TOTALS.SPECIES_NAME],
                  totalDead = totalDead,
                  totalExisting = totalExisting,
                  totalLive = totalLive,
                  totalPlants = totalPlants,
              )
            }
          }

  // TODO: This needs to be temporally aware (what we want is whether or not the zone was finished
  //       planting at the time of the observation, not at the present time).
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
              val areaHa = record[PLANTING_ZONES.AREA_HA.asNonNullable()]
              val species = record[plantingZoneSpeciesMultiset]
              val subzones = record[plantingSubzoneMultiset]
              val identifiedSpecies =
                  species.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              val liveSpecies = identifiedSpecies.filter { it.totalPlants > 0 }
              val totalPlants = species.sumOf { it.totalLive + it.totalDead }
              val totalLiveSpeciesExceptUnknown =
                  species.count {
                    it.certainty != RecordedSpeciesCertainty.Unknown && it.totalPlants > 0
                  }

              val isCompleted =
                  subzones.isNotEmpty() &&
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

              val mortalityRate = calculateMortalityRate(species)

              val plantingDensity =
                  if (record[zoneFinishedPlantingField]) {
                    val plotDensities =
                        subzones.flatMap { subzone ->
                          subzone.monitoringPlots.map { it.plantingDensity }
                        }
                    if (plotDensities.isNotEmpty()) {
                      plotDensities.average().roundToInt()
                    } else {
                      null
                    }
                  } else {
                    null
                  }

              ObservationPlantingZoneResultsPayload(
                  areaHa = areaHa,
                  completedTime = completedTime,
                  mortalityRate = mortalityRate,
                  plantingDensity = plantingDensity,
                  plantingSubzones = subzones,
                  plantingZoneId = record[PLANTING_ZONES.ID.asNonNullable()],
                  species = liveSpecies,
                  totalSpecies = totalLiveSpeciesExceptUnknown,
                  totalPlants = totalPlants,
              )
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
          val species = zones.flatMap { zone -> zone.species }

          var plantingDensity: BigDecimal? = null
          var totalPlants: BigDecimal? = null

          if (zones.isNotEmpty() && zones.all { it.plantingDensity != null }) {
            val totalArea = zones.sumOf { it.areaHa }

            if (totalArea > BigDecimal.ZERO) {
              totalPlants = zones.sumOf { it.areaHa * it.plantingDensity!!.toBigDecimal() }
              plantingDensity = totalPlants / totalArea
            }
          }

          val isCompleted = zones.isNotEmpty() && zones.all { it.completedTime != null }
          val completedTime =
              if (isCompleted) {
                zones.maxOf { it.completedTime!! }
              } else {
                null
              }

          val totalSpecies =
              zones
                  .flatMap { zone ->
                    zone.species
                        .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
                        .map { it.speciesId to it.speciesName }
                  }
                  .distinct()
                  .count()

          val mortalityRate = calculateMortalityRate(species)

          ObservationResultsPayload(
              completedTime = completedTime,
              mortalityRate = mortalityRate,
              observationId = record[OBSERVATIONS.ID.asNonNullable()],
              plantingDensity = plantingDensity?.toInt(),
              plantingSiteId = record[OBSERVATIONS.PLANTING_SITE_ID.asNonNullable()],
              plantingZones = zones,
              startDate = record[OBSERVATIONS.START_DATE.asNonNullable()],
              state = record[OBSERVATIONS.STATE_ID.asNonNullable()],
              totalPlants = totalPlants?.toInt(),
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

  private fun calculateMortalityRate(species: List<ObservationSpeciesResultsPayload>): Int {
    val knownSpecies = species.filter { it.certainty == RecordedSpeciesCertainty.Known }
    val numNonExistingPlants = knownSpecies.sumOf { it.totalLive + it.totalDead }
    val numDeadPlants = knownSpecies.sumOf { it.totalDead }

    return if (numNonExistingPlants > 0) {
      (numDeadPlants * 100.0 / numNonExistingPlants).roundToInt()
    } else {
      0
    }
  }
}
