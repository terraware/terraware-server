package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumResultsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumResultsModel
import jakarta.inject.Named
import java.time.Instant
import kotlin.math.roundToInt
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Polygon

/**
 * Retrieves the results of observations by reading pre-computed aggregate statistics from the
 * observation results tables ([OBSERVATION_PLOT_RESULTS], [OBSERVATION_SUBSTRATUM_RESULTS],
 * [OBSERVATION_STRATUM_RESULTS], [OBSERVATION_SITE_RESULTS]).
 *
 * Species-level data still comes from the observed species totals tables.
 */
@Named
class ObservationResultsStoreV2(private val dslContext: DSLContext) {
  fun fetchOneById(
      observationId: ObservationId,
      depth: ObservationResultsDepth = ObservationResultsDepth.Plot,
  ): ObservationResultsModel {
    requirePermissions { readObservation(observationId) }

    return fetchByCondition(OBSERVATIONS.ID.eq(observationId), depth, 1).first()
  }

  fun fetchByPlantingSiteId(
      plantingSiteId: PlantingSiteId,
      depth: ObservationResultsDepth = ObservationResultsDepth.Plot,
      limit: Int? = null,
      maxCompletionTime: Instant? = null,
      isAdHoc: Boolean = false,
      states: Set<ObservationState>? = null,
  ): List<ObservationResultsModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchByCondition(
        DSL.and(
            OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId),
            OBSERVATIONS.IS_AD_HOC.eq(isAdHoc),
            maxCompletionTime?.let { OBSERVATIONS.COMPLETED_TIME.lessOrEqual(it) },
            states?.let { OBSERVATIONS.STATE_ID.`in`(states) },
        ),
        depth,
        limit,
    )
  }

  fun fetchByOrganizationId(
      organizationId: OrganizationId,
      depth: ObservationResultsDepth = ObservationResultsDepth.Plot,
      limit: Int? = null,
      isAdHoc: Boolean = false,
      states: Set<ObservationState>? = null,
  ): List<ObservationResultsModel> {
    requirePermissions { readOrganization(organizationId) }

    return fetchByCondition(
        DSL.and(
            OBSERVATIONS.plantingSites.ORGANIZATION_ID.eq(organizationId),
            OBSERVATIONS.IS_AD_HOC.eq(isAdHoc),
            states?.let { OBSERVATIONS.STATE_ID.`in`(states) },
        ),
        depth,
        limit,
    )
  }

  /**
   * Monitoring plots for an observation. Plant density and survival rate are read from
   * [OBSERVATION_PLOT_RESULTS] instead of being computed from species totals.
   */
  private fun monitoringPlotsMultiset(
      condition: Condition,
      depth: ObservationResultsDepth,
  ): Field<List<ObservationMonitoringPlotResultsModel>> {
    val recordedPlantsField =
        if (depth == ObservationResultsDepth.Plant) {
          recordedPlantsMultiset
        } else {
          null
        }

    return DSL.multiset(
            DSL.select(
                    USERS.FIRST_NAME,
                    USERS.LAST_NAME,
                    OBSERVATION_PLOTS.CLAIMED_BY,
                    OBSERVATION_PLOTS.COMPLETED_TIME,
                    OBSERVATION_PLOTS.IS_PERMANENT,
                    OBSERVATION_PLOTS.NOTES,
                    OBSERVATION_PLOTS.STATUS_ID,
                    monitoringPlotsBoundaryField,
                    MONITORING_PLOTS.ELEVATION_METERS,
                    MONITORING_PLOTS.ID,
                    MONITORING_PLOTS.IS_AD_HOC,
                    MONITORING_PLOTS.PLOT_NUMBER,
                    MONITORING_PLOTS.SIZE_METERS,
                    monitoringPlotConditionsMultiset,
                    monitoringPlotOverlappedByMultiset,
                    monitoringPlotOverlapsMultiset,
                    monitoringPlotSpeciesMultiset,
                    observationPlotCoordinatesMultiset,
                    observationMediaMultiset,
                    recordedPlantsField,
                    OBSERVATION_PLOT_RESULTS.PLANT_DENSITY,
                    OBSERVATION_PLOT_RESULTS.SURVIVAL_RATE,
                )
                .from(OBSERVATION_PLOTS)
                .join(MONITORING_PLOTS)
                .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                .join(MONITORING_PLOT_HISTORIES)
                .on(
                    OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(
                        MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID
                    )
                )
                .leftJoin(USERS)
                .on(OBSERVATION_PLOTS.CLAIMED_BY.eq(USERS.ID))
                .leftJoin(OBSERVATION_PLOT_RESULTS)
                .on(
                    OBSERVATION_PLOT_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID)
                        .and(OBSERVATION_PLOT_RESULTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                )
                .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                .and(
                    MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                        OBSERVATIONS.PLANTING_SITE_HISTORY_ID
                    )
                )
                .and(condition)
        )
        .convertFrom { results ->
          results.map { record: Record ->
            val claimedBy = record[OBSERVATION_PLOTS.CLAIMED_BY]
            val completedTime = record[OBSERVATION_PLOTS.COMPLETED_TIME]
            val isPermanent = record[OBSERVATION_PLOTS.IS_PERMANENT.asNonNullable()]
            val sizeMeters = record[MONITORING_PLOTS.SIZE_METERS]!!
            val species = record[monitoringPlotSpeciesMultiset]
            val totalPlants = species.sumOf { it.totalLive + it.totalExisting + it.totalDead }
            val totalLiveSpeciesExceptUnknown = species.count {
              it.certainty != RecordedSpeciesCertainty.Unknown &&
                  (it.totalLive + it.totalExisting) > 0
            }

            val survivalRate = record[OBSERVATION_PLOT_RESULTS.SURVIVAL_RATE]
            val plantingDensity = record[OBSERVATION_PLOT_RESULTS.PLANT_DENSITY]

            val status = record[OBSERVATION_PLOTS.STATUS_ID]!!

            ObservationMonitoringPlotResultsModel(
                boundary = record[monitoringPlotsBoundaryField] as Polygon,
                claimedByName =
                    TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME]),
                claimedByUserId = claimedBy,
                completedTime = completedTime,
                conditions = record[monitoringPlotConditionsMultiset],
                coordinates = record[observationPlotCoordinatesMultiset],
                elevationMeters = record[MONITORING_PLOTS.ELEVATION_METERS],
                isAdHoc = record[MONITORING_PLOTS.IS_AD_HOC.asNonNullable()],
                isPermanent = isPermanent,
                monitoringPlotId = record[MONITORING_PLOTS.ID]!!,
                monitoringPlotNumber = record[MONITORING_PLOTS.PLOT_NUMBER]!!,
                notes = record[OBSERVATION_PLOTS.NOTES],
                overlappedByPlotIds = record[monitoringPlotOverlappedByMultiset],
                overlapsWithPlotIds = record[monitoringPlotOverlapsMultiset],
                media = record[observationMediaMultiset],
                plantingDensity = plantingDensity,
                plants = recordedPlantsField?.let { record[it] },
                sizeMeters = sizeMeters,
                species = species,
                status = status,
                survivalRate = survivalRate,
                totalPlants = totalPlants,
                totalSpecies = totalLiveSpeciesExceptUnknown,
            )
          }
        }
  }

  private fun adHocMonitoringPlotsMultiset(depth: ObservationResultsDepth) =
      monitoringPlotsMultiset(MONITORING_PLOTS.IS_AD_HOC.isTrue(), depth)

  private fun substratumMonitoringPlotsMultiset(depth: ObservationResultsDepth) =
      monitoringPlotsMultiset(
          MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID),
          depth,
      )

  /**
   * Substratum results. Plant density, plant density std dev, survival rate, and survival rate std
   * dev are read from [OBSERVATION_SUBSTRATUM_RESULTS].
   */
  private fun substrataMultiset(
      depth: ObservationResultsDepth
  ): Field<List<ObservationSubstratumResultsModel>> {
    val plotsField = substratumMonitoringPlotsMultiset(depth)
    val substratumSpeciesMultisetField = substratumSpeciesMultiset()
    return DSL.multiset(
            DSL.select(
                    SUBSTRATUM_HISTORIES.AREA_HA,
                    SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
                    SUBSTRATUM_HISTORIES.NAME,
                    SUBSTRATA.PLANTING_COMPLETED_TIME,
                    plotsField,
                    substratumSpeciesMultisetField,
                    SUBSTRATUM_HISTORIES.stratumHistories.plantingSiteHistories.plantingSites
                        .SURVIVAL_RATE_INCLUDES_TEMP_PLOTS,
                    OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE,
                    OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE_STD_DEV,
                    OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY,
                    OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY_STD_DEV,
                )
                .from(SUBSTRATUM_HISTORIES)
                .leftJoin(SUBSTRATA)
                .on(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                .leftJoin(OBSERVATION_SUBSTRATUM_RESULTS)
                .on(
                    OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID)
                        .and(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                )
                .where(
                    SUBSTRATUM_HISTORIES.ID.`in`(
                        DSL.select(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID)
                            .from(MONITORING_PLOT_HISTORIES)
                            .join(OBSERVATION_PLOTS)
                            .on(
                                MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(
                                    OBSERVATION_PLOTS.MONITORING_PLOT_ID
                                )
                            )
                            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                            .and(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID))
                            .and(
                                MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                                    OBSERVATIONS.PLANTING_SITE_HISTORY_ID
                                )
                            )
                    )
                )
        )
        .convertFrom { results ->
          results.map { record: Record ->
            val monitoringPlots = record[plotsField]

            val areaHa = record[SUBSTRATUM_HISTORIES.AREA_HA]!!
            val survivalRateIncludesTempPlots =
                record[
                    SUBSTRATUM_HISTORIES.stratumHistories.plantingSiteHistories.plantingSites
                        .SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
                        .asNonNullable()]

            val species = record[substratumSpeciesMultisetField]
            val totalPlants = species.sumOf { it.totalLive + it.totalDead }
            val totalLiveSpeciesExceptUnknown = species.count {
              it.certainty != RecordedSpeciesCertainty.Unknown &&
                  (it.totalLive + it.totalExisting) > 0
            }

            val isCompleted =
                monitoringPlots.isNotEmpty() && monitoringPlots.all { it.completedTime != null }
            val completedTime =
                if (isCompleted) {
                  monitoringPlots.maxOf { it.completedTime!! }
                } else {
                  null
                }

            val survivalRate = record[OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE]
            val survivalRateStdDev = record[OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE_STD_DEV]
            val plantingDensity = record[OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY]
            val plantingDensityStdDev = record[OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY_STD_DEV]

            val plantingCompleted = record[SUBSTRATA.PLANTING_COMPLETED_TIME] != null
            val estimatedPlants =
                if (plantingCompleted && plantingDensity != null) {
                  (areaHa.toDouble() * plantingDensity).roundToInt()
                } else {
                  null
                }

            ObservationSubstratumResultsModel(
                areaHa = areaHa,
                completedTime = completedTime,
                estimatedPlants = estimatedPlants,
                monitoringPlots = monitoringPlots,
                name = record[SUBSTRATUM_HISTORIES.NAME.asNonNullable()],
                plantingCompleted = plantingCompleted,
                plantingDensity = plantingDensity,
                plantingDensityStdDev = plantingDensityStdDev,
                species = species,
                substratumId = record[SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.asNonNullable()],
                survivalRate = survivalRate,
                survivalRateStdDev = survivalRateStdDev,
                survivalRateIncludesTempPlots = survivalRateIncludesTempPlots,
                totalPlants = totalPlants,
                totalSpecies = totalLiveSpeciesExceptUnknown,
            )
          }
        }
  }

  /**
   * Stratum results. Plant density, plant density std dev, survival rate, and survival rate std dev
   * are read from [OBSERVATION_STRATUM_RESULTS].
   */
  private fun stratumMultiset(
      depth: ObservationResultsDepth
  ): Field<List<ObservationStratumResultsModel>> {
    val substrataField = substrataMultiset(depth)
    val stratumSpeciesMultisetField = stratumSpeciesMultiset()

    return DSL.multiset(
            DSL.select(
                    STRATUM_HISTORIES.AREA_HA,
                    STRATUM_HISTORIES.STRATUM_ID,
                    STRATUM_HISTORIES.NAME,
                    substrataField,
                    stratumSpeciesMultisetField,
                    stratumPlantingCompletedField,
                    STRATUM_HISTORIES.plantingSiteHistories.plantingSites
                        .SURVIVAL_RATE_INCLUDES_TEMP_PLOTS,
                    OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE,
                    OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE_STD_DEV,
                    OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY,
                    OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY_STD_DEV,
                )
                .from(STRATUM_HISTORIES)
                .leftJoin(OBSERVATION_STRATUM_RESULTS)
                .on(
                    OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID)
                        .and(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                )
                .where(
                    STRATUM_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                        OBSERVATIONS.PLANTING_SITE_HISTORY_ID
                    )
                )
                .and(
                    STRATUM_HISTORIES.ID.`in`(
                        DSL.select(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID)
                            .from(OBSERVATION_PLOTS)
                            .join(MONITORING_PLOT_HISTORIES)
                            .on(
                                OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(
                                    MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID
                                )
                            )
                            .join(SUBSTRATUM_HISTORIES)
                            .on(
                                MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(
                                    SUBSTRATUM_HISTORIES.ID
                                )
                            )
                            .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                            .and(
                                MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                                    OBSERVATIONS.PLANTING_SITE_HISTORY_ID
                                )
                            )
                    )
                )
        )
        .convertFrom { results ->
          results.map { record: Record ->
            val areaHa = record[STRATUM_HISTORIES.AREA_HA]!!
            val species = record[stratumSpeciesMultisetField]
            val substrata = record[substrataField]

            val identifiedSpecies = species.filter {
              it.certainty != RecordedSpeciesCertainty.Unknown
            }
            val totalPlants = species.sumOf { it.totalLive + it.totalDead }
            val totalLiveSpeciesExceptUnknown = identifiedSpecies.count {
              (it.totalLive + it.totalExisting) > 0
            }

            val isCompleted =
                substrata.isNotEmpty() &&
                    substrata.all { substratum ->
                      substratum.monitoringPlots.all { it.completedTime != null }
                    }
            val completedTime =
                if (isCompleted) {
                  substrata.maxOf { substratum ->
                    substratum.monitoringPlots.maxOf { it.completedTime!! }
                  }
                } else {
                  null
                }

            val survivalRate = record[OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE]
            val survivalRateStdDev = record[OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE_STD_DEV]
            val plantingDensity = record[OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY]
            val plantingDensityStdDev = record[OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY_STD_DEV]

            val plantingCompleted = record[stratumPlantingCompletedField]
            val estimatedPlants =
                if (plantingCompleted && plantingDensity != null) {
                  (areaHa.toDouble() * plantingDensity).roundToInt()
                } else {
                  null
                }

            ObservationStratumResultsModel(
                areaHa = areaHa,
                completedTime = completedTime,
                estimatedPlants = estimatedPlants,
                name = record[STRATUM_HISTORIES.NAME.asNonNullable()],
                plantingCompleted = plantingCompleted,
                plantingDensity = plantingDensity,
                plantingDensityStdDev = plantingDensityStdDev,
                species = identifiedSpecies,
                stratumId = record[STRATUM_HISTORIES.STRATUM_ID.asNonNullable()],
                substrata = substrata,
                survivalRate = survivalRate,
                survivalRateStdDev = survivalRateStdDev,
                totalSpecies = totalLiveSpeciesExceptUnknown,
                totalPlants = totalPlants,
            )
          }
        }
  }

  /**
   * Fetches observation results. Site-level plant density, plant density std dev, survival rate,
   * and survival rate std dev are read from [OBSERVATION_SITE_RESULTS].
   */
  private fun fetchByCondition(
      condition: Condition,
      depth: ObservationResultsDepth = ObservationResultsDepth.Plot,
      limit: Int?,
  ): List<ObservationResultsModel> {
    val queryDepth =
        if (depth == ObservationResultsDepth.Plant) {
          ObservationResultsDepth.Plant
        } else {
          ObservationResultsDepth.Plot
        }

    val adHocPlotsField = adHocMonitoringPlotsMultiset(queryDepth)
    val strataField = stratumMultiset(queryDepth)
    val plantingSiteSpeciesMultisetField = plantingSiteSpeciesMultiset()

    val results =
        dslContext
            .select(
                adHocPlotsField,
                biomassDetailsMultiset,
                OBSERVATIONS.COMPLETED_TIME,
                OBSERVATIONS.ID,
                OBSERVATIONS.IS_AD_HOC,
                OBSERVATIONS.OBSERVATION_TYPE_ID,
                OBSERVATIONS.PLANTING_SITE_ID,
                OBSERVATIONS.START_DATE,
                OBSERVATIONS.STATE_ID,
                PLANTING_SITE_HISTORIES.AREA_HA,
                PLANTING_SITE_HISTORIES.ID,
                plantingSiteSpeciesMultisetField,
                strataField,
                OBSERVATIONS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS,
                OBSERVATION_SITE_RESULTS.SURVIVAL_RATE,
                OBSERVATION_SITE_RESULTS.SURVIVAL_RATE_STD_DEV,
                OBSERVATION_SITE_RESULTS.PLANT_DENSITY,
                OBSERVATION_SITE_RESULTS.PLANT_DENSITY_STD_DEV,
            )
            .from(OBSERVATIONS)
            .leftJoin(PLANTING_SITE_HISTORIES)
            .on(OBSERVATIONS.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
            .leftJoin(OBSERVATION_SITE_RESULTS)
            .on(OBSERVATION_SITE_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
            .where(condition)
            .orderBy(OBSERVATIONS.COMPLETED_TIME.desc().nullsLast(), OBSERVATIONS.ID.desc())
            .let { if (limit != null) it.limit(limit) else it }
            .fetch { record ->
              val areaHa = record[PLANTING_SITE_HISTORIES.AREA_HA]

              val strata = record[strataField]
              val species = record[plantingSiteSpeciesMultisetField]
              val survivalRateIncludesTempPlots =
                  record[
                      OBSERVATIONS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.asNonNullable()]

              val knownSpecies = species.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              val liveSpecies = knownSpecies.filter { it.totalLive > 0 || it.totalExisting > 0 }

              val plantingCompleted = strata.isNotEmpty() && strata.all { it.plantingCompleted }

              val estimatedPlants =
                  if (strata.isNotEmpty() && strata.all { it.estimatedPlants != null }) {
                    strata.mapNotNull { it.estimatedPlants }.sum()
                  } else {
                    null
                  }

              val totalSpecies = liveSpecies.size
              val totalPlants = species.sumOf { it.totalLive + it.totalDead }

              val survivalRate = record[OBSERVATION_SITE_RESULTS.SURVIVAL_RATE]
              val survivalRateStdDev = record[OBSERVATION_SITE_RESULTS.SURVIVAL_RATE_STD_DEV]
              val plantingDensity = record[OBSERVATION_SITE_RESULTS.PLANT_DENSITY]
              val plantingDensityStdDev = record[OBSERVATION_SITE_RESULTS.PLANT_DENSITY_STD_DEV]

              ObservationResultsModel(
                  adHocPlot = record[adHocPlotsField].firstOrNull(),
                  areaHa = areaHa,
                  biomassDetails = record[biomassDetailsMultiset].firstOrNull(),
                  completedTime = record[OBSERVATIONS.COMPLETED_TIME],
                  estimatedPlants = estimatedPlants,
                  isAdHoc = record[OBSERVATIONS.IS_AD_HOC.asNonNullable()],
                  observationId = record[OBSERVATIONS.ID.asNonNullable()],
                  observationType = record[OBSERVATIONS.OBSERVATION_TYPE_ID.asNonNullable()],
                  plantingCompleted = plantingCompleted,
                  plantingDensity = plantingDensity,
                  plantingDensityStdDev = plantingDensityStdDev,
                  plantingSiteHistoryId = record[PLANTING_SITE_HISTORIES.ID],
                  plantingSiteId = record[OBSERVATIONS.PLANTING_SITE_ID.asNonNullable()],
                  species = knownSpecies,
                  startDate = record[OBSERVATIONS.START_DATE.asNonNullable()],
                  state = record[OBSERVATIONS.STATE_ID.asNonNullable()],
                  strata = strata,
                  survivalRate = survivalRate,
                  survivalRateIncludesTempPlots = survivalRateIncludesTempPlots,
                  survivalRateStdDev = survivalRateStdDev,
                  totalPlants = totalPlants,
                  totalSpecies = totalSpecies,
              )
            }

    return when (depth) {
      ObservationResultsDepth.Site -> results.map { site -> site.copy(strata = emptyList()) }
      ObservationResultsDepth.Stratum ->
          results.map { site ->
            site.copy(
                strata =
                    site.strata.map { stratum ->
                      stratum.copy(
                          substrata = emptyList(),
                      )
                    },
            )
          }
      ObservationResultsDepth.Substratum ->
          results.map { site ->
            site.copy(
                strata =
                    site.strata.map { stratum ->
                      stratum.copy(
                          substrata =
                              stratum.substrata.map { substratum ->
                                substratum.copy(monitoringPlots = emptyList())
                              }
                      )
                    },
            )
          }
      ObservationResultsDepth.Plot,
      ObservationResultsDepth.Plant -> results
    }
  }
}
