package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.emptyMultiset
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
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationSiteStatsModel
import com.terraformation.backend.tracking.model.ObservationStratumResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumStatsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumResultsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumStatsModel
import jakarta.inject.Named
import java.time.Instant
import kotlin.math.roundToInt
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Record3
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
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

    val isAdHoc =
        dslContext.fetchValue(OBSERVATIONS.IS_AD_HOC, OBSERVATIONS.ID.eq(observationId))
            ?: throw ObservationNotFoundException(observationId)

    return fetchByCondition(OBSERVATIONS.ID.eq(observationId), depth, 1, isAdHoc).first()
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
        isAdHoc,
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
        isAdHoc,
    )
  }

  /**
   * Statistics for a planting site and each of its strata and substrata. Each area resolves to its
   * own observation: the most recent one that completed at least one monitoring plot in that area.
   * Areas that have never been observed are included with no statistics.
   */
  fun fetchStatsForSite(plantingSiteId: PlantingSiteId): List<ObservationSiteStatsModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchStats(PLANTING_SITES.ID.eq(plantingSiteId))
  }

  private fun fetchStats(condition: Condition): List<ObservationSiteStatsModel> =
      dslContext
          .select(
              PLANTING_SITES.ID,
              OBSERVATION_SITE_RESULTS.OBSERVATION_ID,
              OBSERVATION_SITE_RESULTS.PLANT_DENSITY,
              OBSERVATION_SITE_RESULTS.SURVIVAL_RATE,
              siteObservations.COMPLETED_TIME,
              siteTotalPlantsField,
              siteTotalSpeciesField,
              strataStatsMultiset,
          )
          .from(PLANTING_SITES)
          .leftJoin(OBSERVATION_SITE_RESULTS)
          .on(
              OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID.eq(PLANTING_SITES.ID)
                  .and(OBSERVATION_SITE_RESULTS.OBSERVATION_ID.eq(latestSiteObservationId()))
          )
          .leftJoin(siteObservations)
          .on(siteObservations.ID.eq(OBSERVATION_SITE_RESULTS.OBSERVATION_ID))
          .where(condition)
          .orderBy(PLANTING_SITES.NAME, PLANTING_SITES.ID)
          .fetch { record ->
            ObservationSiteStatsModel(
                completedTime = record[siteObservations.COMPLETED_TIME],
                observationId = record[OBSERVATION_SITE_RESULTS.OBSERVATION_ID],
                plantingDensity = record[OBSERVATION_SITE_RESULTS.PLANT_DENSITY],
                plantingSiteId = record[PLANTING_SITES.ID.asNonNullable()],
                strata = record[strataStatsMultiset],
                survivalRate = record[OBSERVATION_SITE_RESULTS.SURVIVAL_RATE],
                totalPlants = record[siteTotalPlantsField],
                totalSpecies = record[siteTotalSpeciesField],
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
            val totalPlants =
                species.ifEmpty { null }?.sumOf { it.totalLive + it.totalExisting + it.totalDead }
            val totalLiveSpeciesExceptUnknown =
                species
                    .ifEmpty { null }
                    ?.count {
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

  private data class AreaCompletionResults(
      val anyPlotsCompleted: Boolean,
      val areaCompletedTime: Instant?,
  ) {
    companion object {
      fun of(record: Record3<Boolean?, Boolean?, Instant?>): AreaCompletionResults {
        val (anyCompleted, allCompleted, maxCompletedTime) = record
        return AreaCompletionResults(
            anyCompleted == true,
            if (allCompleted == true) maxCompletedTime else null,
        )
      }
    }
  }

  /**
   * Queries the completion status of a substratum in an observation. This is used when we aren't
   * fetching plot-level results.
   */
  private val substratumCompletionMultiset: Field<List<AreaCompletionResults>> =
      DSL.multiset(
              DSL.select(
                      DSL.boolOr(OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull),
                      DSL.boolAnd(OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull),
                      DSL.max(OBSERVATION_PLOTS.COMPLETED_TIME),
                  )
                  .from(OBSERVATION_PLOTS)
                  .join(MONITORING_PLOT_HISTORIES)
                  .on(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.eq(MONITORING_PLOT_HISTORIES.ID))
                  .where(
                      MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID)
                  )
                  .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
          )
          .convertFrom { result -> result.map { AreaCompletionResults.of(it) } }

  /**
   * Substratum results. Plant density, plant density std dev, survival rate, and survival rate std
   * dev are read from [OBSERVATION_SUBSTRATUM_RESULTS].
   */
  private fun substrataMultiset(
      depth: ObservationResultsDepth
  ): Field<List<ObservationSubstratumResultsModel>> {
    val plotsField =
        when (depth) {
          ObservationResultsDepth.Plot,
          ObservationResultsDepth.Plant -> substratumMonitoringPlotsMultiset(depth)
          else -> emptyMultiset()
        }
    val substratumSpeciesMultisetField = substratumSpeciesMultiset()
    val substratumCompletionMultisetField =
        when (depth) {
          ObservationResultsDepth.Plot,
          ObservationResultsDepth.Plant -> emptyMultiset()
          else -> substratumCompletionMultiset
        }
    return DSL.multiset(
            DSL.select(
                    SUBSTRATUM_HISTORIES.AREA_HA,
                    SUBSTRATUM_HISTORIES.SUBSTRATUM_ID,
                    SUBSTRATUM_HISTORIES.NAME,
                    SUBSTRATA.PLANTING_COMPLETED_TIME,
                    plotsField,
                    substratumCompletionMultisetField,
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
            val completionResults = record[substratumCompletionMultisetField]?.firstOrNull()

            val areaHa = record[SUBSTRATUM_HISTORIES.AREA_HA]!!
            val survivalRateIncludesTempPlots =
                record[
                    SUBSTRATUM_HISTORIES.stratumHistories.plantingSiteHistories.plantingSites
                        .SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
                        .asNonNullable()]

            val anyCompleted =
                completionResults?.let { it.anyPlotsCompleted }
                    ?: monitoringPlots.any { it.completedTime != null }
            val species =
                if (anyCompleted) {
                  record[substratumSpeciesMultisetField]
                } else {
                  emptyList()
                }
            val totalPlants = species.ifEmpty { null }?.sumOf { it.totalLive + it.totalDead }
            val totalLiveSpeciesExceptUnknown =
                species
                    .ifEmpty { null }
                    ?.count {
                      it.certainty != RecordedSpeciesCertainty.Unknown &&
                          (it.totalLive + it.totalExisting) > 0
                    }

            val completedTime =
                completionResults?.areaCompletedTime
                    ?: if (monitoringPlots.all { it.completedTime != null }) {
                      monitoringPlots.maxOfOrNull { it.completedTime!! }
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
                anyPlotsCompleted = anyCompleted,
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
   * Queries the completion status of a stratum in an observation. This is used when we aren't
   * fetching plot-level results.
   */
  private val stratumCompletionMultiset: Field<List<AreaCompletionResults>> =
      DSL.multiset(
              DSL.select(
                      DSL.boolOr(OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull),
                      DSL.boolAnd(OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull),
                      DSL.max(OBSERVATION_PLOTS.COMPLETED_TIME),
                  )
                  .from(OBSERVATION_PLOTS)
                  .join(MONITORING_PLOT_HISTORIES)
                  .on(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.eq(MONITORING_PLOT_HISTORIES.ID))
                  .join(SUBSTRATUM_HISTORIES)
                  .on(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(SUBSTRATUM_HISTORIES.ID))
                  .where(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(STRATUM_HISTORIES.ID))
                  .and(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
          )
          .convertFrom { result -> result.map { AreaCompletionResults.of(it) } }

  /**
   * Stratum results. Plant density, plant density std dev, survival rate, and survival rate std dev
   * are read from [OBSERVATION_STRATUM_RESULTS].
   */
  private fun stratumMultiset(
      depth: ObservationResultsDepth
  ): Field<List<ObservationStratumResultsModel>> {
    val substrataField =
        when (depth) {
          ObservationResultsDepth.Plot,
          ObservationResultsDepth.Plant,
          ObservationResultsDepth.Substratum -> substrataMultiset(depth)
          else -> emptyMultiset()
        }

    val stratumSpeciesMultisetField = stratumSpeciesMultiset()
    val stratumCompletionMultisetField =
        when (depth) {
          ObservationResultsDepth.Plot,
          ObservationResultsDepth.Plant,
          ObservationResultsDepth.Substratum -> emptyMultiset()
          else -> stratumCompletionMultiset
        }

    return DSL.multiset(
            DSL.select(
                    STRATUM_HISTORIES.AREA_HA,
                    STRATUM_HISTORIES.STRATUM_ID,
                    STRATUM_HISTORIES.NAME,
                    substrataField,
                    stratumCompletionMultisetField,
                    stratumSpeciesMultisetField,
                    stratumPlantingCompletedField,
                    STRATUM_HISTORIES.plantingSiteHistories.plantingSites
                        .SURVIVAL_RATE_INCLUDES_TEMP_PLOTS,
                    OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE,
                    OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE_STD_DEV,
                    OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY,
                    OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY_STD_DEV,
                    OBSERVATION_STRATUM_RESULTS.OBSERVED_DENSITY,
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
            val substrata = record[substrataField]
            val completionResults = record[stratumCompletionMultisetField]?.firstOrNull()
            val anyCompleted =
                completionResults?.anyPlotsCompleted ?: substrata.any { it.anyPlotsCompleted }
            val species =
                if (anyCompleted) {
                  record[stratumSpeciesMultisetField]
                } else {
                  emptyList()
                }

            val identifiedSpecies = species.filter {
              it.certainty != RecordedSpeciesCertainty.Unknown
            }
            val totalPlants = species.ifEmpty { null }?.sumOf { it.totalLive + it.totalDead }
            val totalLiveSpeciesExceptUnknown =
                if (species.isNotEmpty()) {
                  identifiedSpecies.count { (it.totalLive + it.totalExisting) > 0 }
                } else {
                  null
                }

            val completedTime =
                completionResults?.areaCompletedTime
                    ?: if (substrata.all { it.completedTime != null }) {
                      substrata.maxOfOrNull { it.completedTime!! }
                    } else {
                      null
                    }

            val survivalRate = record[OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE]
            val survivalRateStdDev = record[OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE_STD_DEV]
            val plantingDensity = record[OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY]
            val plantingDensityStdDev = record[OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY_STD_DEV]
            val observedDensity = record[OBSERVATION_STRATUM_RESULTS.OBSERVED_DENSITY]

            val plantingCompleted = record[stratumPlantingCompletedField]
            val estimatedPlants =
                if (plantingCompleted && plantingDensity != null) {
                  (areaHa.toDouble() * plantingDensity).roundToInt()
                } else {
                  null
                }

            ObservationStratumResultsModel(
                anyPlotsCompleted = anyCompleted,
                areaHa = areaHa,
                completedTime = completedTime,
                estimatedPlants = estimatedPlants,
                name = record[STRATUM_HISTORIES.NAME.asNonNullable()],
                observedDensity = observedDensity,
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
      isAdHoc: Boolean,
  ): List<ObservationResultsModel> {
    if (isAdHoc) {
      return fetchAdHocByCondition(condition, depth, limit)
    }

    val strataField = stratumMultiset(depth)
    val plantingSiteSpeciesMultisetField = plantingSiteSpeciesMultiset()

    val results =
        dslContext
            .select(
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
                OBSERVATION_SITE_RESULTS.OBSERVED_DENSITY,
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
              val anyCompleted = strata.any { stratum -> stratum.anyPlotsCompleted }
              val species =
                  if (anyCompleted) {
                    record[plantingSiteSpeciesMultisetField]
                  } else {
                    emptyList()
                  }
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

              val totalSpecies = if (species.isNotEmpty()) liveSpecies.size else null
              val totalPlants = species.ifEmpty { null }?.sumOf { it.totalLive + it.totalDead }

              val survivalRate = record[OBSERVATION_SITE_RESULTS.SURVIVAL_RATE]
              val survivalRateStdDev = record[OBSERVATION_SITE_RESULTS.SURVIVAL_RATE_STD_DEV]
              val plantingDensity = record[OBSERVATION_SITE_RESULTS.PLANT_DENSITY]
              val plantingDensityStdDev = record[OBSERVATION_SITE_RESULTS.PLANT_DENSITY_STD_DEV]
              val observedDensity = record[OBSERVATION_SITE_RESULTS.OBSERVED_DENSITY]

              ObservationResultsModel(
                  adHocPlot = null,
                  anyPlotsCompleted = anyCompleted,
                  areaHa = areaHa,
                  biomassDetails = record[biomassDetailsMultiset].firstOrNull(),
                  completedTime = record[OBSERVATIONS.COMPLETED_TIME],
                  estimatedPlants = estimatedPlants,
                  isAdHoc = record[OBSERVATIONS.IS_AD_HOC.asNonNullable()],
                  observationId = record[OBSERVATIONS.ID.asNonNullable()],
                  observationType = record[OBSERVATIONS.OBSERVATION_TYPE_ID.asNonNullable()],
                  observedDensity = observedDensity,
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

  private fun fetchAdHocByCondition(
      condition: Condition,
      depth: ObservationResultsDepth = ObservationResultsDepth.Plot,
      limit: Int?,
  ): List<ObservationResultsModel> {
    val adHocDepth =
        if (depth == ObservationResultsDepth.Plant) {
          ObservationResultsDepth.Plant
        } else {
          ObservationResultsDepth.Plot
        }

    val adHocPlotsField = adHocMonitoringPlotsMultiset(adHocDepth)

    val results =
        dslContext
            .select(
                adHocPlotsField,
                biomassDetailsMultiset,
                OBSERVATIONS.COMPLETED_TIME,
                OBSERVATIONS.ID,
                OBSERVATIONS.OBSERVATION_TYPE_ID,
                OBSERVATIONS.PLANTING_SITE_ID,
                OBSERVATIONS.START_DATE,
                OBSERVATIONS.STATE_ID,
                PLANTING_SITE_HISTORIES.AREA_HA,
                PLANTING_SITE_HISTORIES.ID,
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
              ObservationResultsModel(
                  adHocPlot = record[adHocPlotsField].firstOrNull(),
                  anyPlotsCompleted = false,
                  areaHa = record[PLANTING_SITE_HISTORIES.AREA_HA],
                  biomassDetails = record[biomassDetailsMultiset].firstOrNull(),
                  completedTime = record[OBSERVATIONS.COMPLETED_TIME],
                  estimatedPlants = null,
                  isAdHoc = true,
                  observationId = record[OBSERVATIONS.ID.asNonNullable()],
                  observationType = record[OBSERVATIONS.OBSERVATION_TYPE_ID.asNonNullable()],
                  observedDensity = null,
                  plantingCompleted = false,
                  plantingDensity = null,
                  plantingDensityStdDev = null,
                  plantingSiteHistoryId = record[PLANTING_SITE_HISTORIES.ID],
                  plantingSiteId = record[OBSERVATIONS.PLANTING_SITE_ID.asNonNullable()],
                  species = emptyList(),
                  startDate = record[OBSERVATIONS.START_DATE.asNonNullable()],
                  state = record[OBSERVATIONS.STATE_ID.asNonNullable()],
                  strata = emptyList(),
                  survivalRate = null,
                  survivalRateIncludesTempPlots = false,
                  survivalRateStdDev = null,
                  totalPlants = null,
                  totalSpecies = null,
              )
            }

    return results
  }

  private val substratumSpeciesTotalsCondition: Condition =
      OBSERVED_SUBSTRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(
              OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID
          )
          .and(
              OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SUBSTRATUM_HISTORY_ID.eq(
                  OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_HISTORY_ID
              )
          )

  private val stratumSpeciesTotalsCondition: Condition =
      OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID.eq(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID)
          .and(
              OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_HISTORY_ID.eq(
                  OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID
              )
          )

  private val siteSpeciesTotalsCondition: Condition =
      OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID.eq(OBSERVATION_SITE_RESULTS.OBSERVATION_ID)
          .and(
              OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_HISTORY_ID.eq(
                  OBSERVATION_SITE_RESULTS.PLANTING_SITE_HISTORY_ID
              )
          )

  private fun totalPlantsField(
      speciesTotals: Table<*>,
      totalLive: Field<Int?>,
      totalDead: Field<Int?>,
      condition: Condition,
  ): Field<Int?> =
      DSL.field(
          DSL.select(DSL.sum(totalLive.plus(totalDead)).cast(SQLDataType.INTEGER))
              .from(speciesTotals)
              .where(condition)
      )

  private fun totalSpeciesField(
      speciesTotals: Table<*>,
      certainty: Field<RecordedSpeciesCertainty?>,
      totalLive: Field<Int?>,
      totalExisting: Field<Int?>,
      condition: Condition,
  ): Field<Int?> =
      DSL.field(
          DSL.select(
                  DSL.case_()
                      .`when`(DSL.count().eq(0), DSL.castNull(SQLDataType.INTEGER))
                      .otherwise(
                          DSL.count()
                              .filterWhere(
                                  certainty
                                      .ne(RecordedSpeciesCertainty.Unknown)
                                      .and(totalLive.plus(totalExisting).gt(0))
                              )
                      )
              )
              .from(speciesTotals)
              .where(condition)
      )

  private fun anyPlotCompletedCondition(
      observationIdField: Field<ObservationId?>,
      plotsInAreaCondition: Condition,
  ): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(OBSERVATION_PLOTS)
              .join(MONITORING_PLOT_HISTORIES)
              .on(OBSERVATION_PLOTS.MONITORING_PLOT_HISTORY_ID.eq(MONITORING_PLOT_HISTORIES.ID))
              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(observationIdField))
              .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull)
              .and(plotsInAreaCondition)
      )

  // Each level joins its own alias of OBSERVATIONS so the nested queries don't shadow each other.
  private val substratumObservations = OBSERVATIONS.`as`("substratum_observations")
  private val stratumObservations = OBSERVATIONS.`as`("stratum_observations")
  private val siteObservations = OBSERVATIONS.`as`("site_observations")

  private fun latestSubstratumObservationId(): Field<ObservationId?> {
    val results = OBSERVATION_SUBSTRATUM_RESULTS.`as`("latest_substratum_results")
    val observations = OBSERVATIONS.`as`("latest_substratum_observations")

    return DSL.field(
        DSL.select(results.OBSERVATION_ID)
            .from(results)
            .join(observations)
            .on(observations.ID.eq(results.OBSERVATION_ID))
            .where(results.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
            .and(observations.COMPLETED_TIME.isNotNull)
            .and(observations.IS_AD_HOC.isFalse)
            .and(
                anyPlotCompletedCondition(
                    results.OBSERVATION_ID,
                    MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.eq(
                        results.SUBSTRATUM_HISTORY_ID
                    ),
                )
            )
            .orderBy(observations.COMPLETED_TIME.desc(), observations.ID.desc())
            .limit(1)
    )
  }

  private fun latestStratumObservationId(): Field<ObservationId?> {
    val results = OBSERVATION_STRATUM_RESULTS.`as`("latest_stratum_results")
    val observations = OBSERVATIONS.`as`("latest_stratum_observations")

    return DSL.field(
        DSL.select(results.OBSERVATION_ID)
            .from(results)
            .join(observations)
            .on(observations.ID.eq(results.OBSERVATION_ID))
            .where(results.STRATUM_ID.eq(STRATA.ID))
            .and(observations.COMPLETED_TIME.isNotNull)
            .and(observations.IS_AD_HOC.isFalse)
            .and(
                anyPlotCompletedCondition(
                    results.OBSERVATION_ID,
                    MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID.`in`(
                        DSL.select(SUBSTRATUM_HISTORIES.ID)
                            .from(SUBSTRATUM_HISTORIES)
                            .where(
                                SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID.eq(
                                    results.STRATUM_HISTORY_ID
                                )
                            )
                    ),
                )
            )
            .orderBy(observations.COMPLETED_TIME.desc(), observations.ID.desc())
            .limit(1)
    )
  }

  private fun latestSiteObservationId(): Field<ObservationId?> {
    val results = OBSERVATION_SITE_RESULTS.`as`("latest_site_results")
    val observations = OBSERVATIONS.`as`("latest_site_observations")

    return DSL.field(
        DSL.select(results.OBSERVATION_ID)
            .from(results)
            .join(observations)
            .on(observations.ID.eq(results.OBSERVATION_ID))
            .where(results.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
            .and(observations.COMPLETED_TIME.isNotNull)
            .and(observations.IS_AD_HOC.isFalse)
            .and(anyPlotCompletedCondition(results.OBSERVATION_ID, DSL.trueCondition()))
            .orderBy(observations.COMPLETED_TIME.desc(), observations.ID.desc())
            .limit(1)
    )
  }

  private val substratumTotalPlantsField: Field<Int?> =
      totalPlantsField(
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS,
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_LIVE,
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_DEAD,
          substratumSpeciesTotalsCondition,
      )

  private val substratumTotalSpeciesField: Field<Int?> =
      totalSpeciesField(
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS,
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS.CERTAINTY_ID,
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_LIVE,
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_EXISTING,
          substratumSpeciesTotalsCondition,
      )

  private val stratumTotalPlantsField: Field<Int?> =
      totalPlantsField(
          OBSERVED_STRATUM_SPECIES_TOTALS,
          OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_LIVE,
          OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_DEAD,
          stratumSpeciesTotalsCondition,
      )

  private val stratumTotalSpeciesField: Field<Int?> =
      totalSpeciesField(
          OBSERVED_STRATUM_SPECIES_TOTALS,
          OBSERVED_STRATUM_SPECIES_TOTALS.CERTAINTY_ID,
          OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_LIVE,
          OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_EXISTING,
          stratumSpeciesTotalsCondition,
      )

  private val siteTotalPlantsField: Field<Int?> =
      totalPlantsField(
          OBSERVED_SITE_SPECIES_TOTALS,
          OBSERVED_SITE_SPECIES_TOTALS.TOTAL_LIVE,
          OBSERVED_SITE_SPECIES_TOTALS.TOTAL_DEAD,
          siteSpeciesTotalsCondition,
      )

  private val siteTotalSpeciesField: Field<Int?> =
      totalSpeciesField(
          OBSERVED_SITE_SPECIES_TOTALS,
          OBSERVED_SITE_SPECIES_TOTALS.CERTAINTY_ID,
          OBSERVED_SITE_SPECIES_TOTALS.TOTAL_LIVE,
          OBSERVED_SITE_SPECIES_TOTALS.TOTAL_EXISTING,
          siteSpeciesTotalsCondition,
      )

  private val substrataStatsMultiset: Field<List<ObservationSubstratumStatsModel>> =
      DSL.multiset(
              DSL.select(
                      SUBSTRATA.ID,
                      OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID,
                      OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY,
                      OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE,
                      substratumObservations.COMPLETED_TIME,
                      substratumTotalPlantsField,
                      substratumTotalSpeciesField,
                  )
                  .from(SUBSTRATA)
                  .leftJoin(OBSERVATION_SUBSTRATUM_RESULTS)
                  .on(
                      OBSERVATION_SUBSTRATUM_RESULTS.SUBSTRATUM_ID.eq(SUBSTRATA.ID)
                          .and(
                              OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID.eq(
                                  latestSubstratumObservationId()
                              )
                          )
                  )
                  .leftJoin(substratumObservations)
                  .on(substratumObservations.ID.eq(OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID))
                  .where(SUBSTRATA.STRATUM_ID.eq(STRATA.ID))
                  .orderBy(SUBSTRATA.NAME, SUBSTRATA.ID)
          )
          .convertFrom { result ->
            result.map { record ->
              ObservationSubstratumStatsModel(
                  completedTime = record[substratumObservations.COMPLETED_TIME],
                  observationId = record[OBSERVATION_SUBSTRATUM_RESULTS.OBSERVATION_ID],
                  plantingDensity = record[OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY],
                  substratumId = record[SUBSTRATA.ID.asNonNullable()],
                  survivalRate = record[OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE],
                  totalPlants = record[substratumTotalPlantsField],
                  totalSpecies = record[substratumTotalSpeciesField],
              )
            }
          }

  private val strataStatsMultiset: Field<List<ObservationStratumStatsModel>> =
      DSL.multiset(
              DSL.select(
                      STRATA.ID,
                      OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID,
                      OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY,
                      OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE,
                      stratumObservations.COMPLETED_TIME,
                      stratumTotalPlantsField,
                      stratumTotalSpeciesField,
                      substrataStatsMultiset,
                  )
                  .from(STRATA)
                  .leftJoin(OBSERVATION_STRATUM_RESULTS)
                  .on(
                      OBSERVATION_STRATUM_RESULTS.STRATUM_ID.eq(STRATA.ID)
                          .and(
                              OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(
                                  latestStratumObservationId()
                              )
                          )
                  )
                  .leftJoin(stratumObservations)
                  .on(stratumObservations.ID.eq(OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID))
                  .where(STRATA.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
                  .orderBy(STRATA.NAME, STRATA.ID)
          )
          .convertFrom { result ->
            result.map { record ->
              ObservationStratumStatsModel(
                  completedTime = record[stratumObservations.COMPLETED_TIME],
                  observationId = record[OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID],
                  plantingDensity = record[OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY],
                  stratumId = record[STRATA.ID.asNonNullable()],
                  substrata = record[substrataStatsMultiset],
                  survivalRate = record[OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE],
                  totalPlants = record[stratumTotalPlantsField],
                  totalSpecies = record[stratumTotalSpeciesField],
              )
            }
          }
}
