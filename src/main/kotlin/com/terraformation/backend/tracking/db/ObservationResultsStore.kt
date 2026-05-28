package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumResultsModel
import com.terraformation.backend.tracking.model.calculateStandardDeviation
import com.terraformation.backend.tracking.model.calculateSurvivalRate
import com.terraformation.backend.tracking.model.calculateWeightedStandardDeviation
import com.terraformation.backend.util.SQUARE_METERS_PER_HECTARE
import jakarta.inject.Named
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Polygon

/**
 * Retrieves the results of observations, with statistics suitable for display to end users.
 *
 * Some of the statistics are calculated at query time here; others are accumulated incrementally in
 * [ObservationStore] as observation results are recorded.
 *
 * # Survival rate calculations
 *
 * The site-level survival rate calculations here use an older formula rather than the current
 * area-weighted formula. That's intentional; this class's survival rate calculations will be going
 * away entirely once clients have been updated to use the API endpoints that call
 * ObservationResultsStoreV2. There is no need to update this class to use area-weighted rates.
 */
@Named
class ObservationResultsStore(private val dslContext: DSLContext) {
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
   * Retrieves historical summaries for a planting site by aggregating most recent observation data
   * per substratum, ordered chronologically, latest first. Each summary represents the summary data
   * after an observation is completed.
   *
   * @param limit if provided, only return this number of historical summaries.
   * @param maxCompletionTime if provided, only observations completed before will be used.
   */
  fun fetchSummariesForPlantingSite(
      plantingSiteId: PlantingSiteId,
      limit: Int? = null,
      maxCompletionTime: Instant? = null,
      depth: ObservationResultsDepth = ObservationResultsDepth.Plot,
  ): List<ObservationRollupResultsModel> {
    val queryDepth =
        if (depth == ObservationResultsDepth.Plant) {
          ObservationResultsDepth.Plant
        } else {
          ObservationResultsDepth.Plot
        }

    val completedObservations =
        fetchByPlantingSiteId(
                plantingSiteId,
                depth = queryDepth,
                maxCompletionTime = maxCompletionTime,
            )
            .filter {
              (it.state == ObservationState.Completed || it.state == ObservationState.Abandoned) &&
                  it.completedTime != null
            }

    val numObservations = completedObservations.size
    val size = max(1, limit?.let { min(it, numObservations) } ?: numObservations)

    // Remove observations one at a time, to build a historical summary
    val results =
        List(size) {
              val observations = completedObservations.takeLast(numObservations - it)
              plantingSiteSummary(plantingSiteId, observations)
            }
            .filterNotNull()

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

  private fun plantingSiteSummary(
      plantingSiteId: PlantingSiteId,
      completedObservations: List<ObservationResultsModel>,
  ): ObservationRollupResultsModel? {
    val allSubstratumIdsByStratumIds =
        dslContext
            .select(SUBSTRATA.ID, SUBSTRATA.STRATUM_ID)
            .from(SUBSTRATA)
            .where(SUBSTRATA.PLANTING_SITE_ID.eq(plantingSiteId))
            .groupBy(
                { it[SUBSTRATA.STRATUM_ID]!! },
                { it[SUBSTRATA.ID]!! },
            )

    val stratumAreasById =
        dslContext
            .select(STRATA.ID, STRATA.AREA_HA)
            .from(STRATA)
            .where(STRATA.ID.`in`(allSubstratumIdsByStratumIds.keys))
            .associate { it[STRATA.ID]!! to it[STRATA.AREA_HA]!! }

    val resultsBySubstratum =
        completedObservations
            .flatMap { it.strata }
            .flatMap { it.substrata }
            .filter { stratum ->
              stratum.monitoringPlots.any { it.status == ObservationPlotStatus.Completed }
            }
            .groupBy { it.substratumId }

    val latestPerSubstratum = resultsBySubstratum.mapValues { (_, results) ->
      results.maxBy { result ->
        // Completed time should have at least one non-nulls by filtering by Completed.
        result.monitoringPlots.maxOf { it.completedTime ?: Instant.EPOCH }
      }
    }

    val stratumResults =
        allSubstratumIdsByStratumIds
            .map {
              val stratumId = it.key

              val areaHa = stratumAreasById[stratumId]!!
              val substratumIds = it.value
              val substratumResults = substratumIds.associateWith { substratumId ->
                latestPerSubstratum[substratumId]
              }

              stratumId to
                  ObservationStratumRollupResultsModel.of(
                      areaHa,
                      stratumId,
                      substratumResults,
                  )
            }
            .toMap()

    return ObservationRollupResultsModel.of(plantingSiteId, stratumResults)
  }

  /** monitoring plots for an observation */
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
                    MONITORING_PLOTS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS,
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
            val survivalRateIncludesTempPlots =
                record[
                    MONITORING_PLOTS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
                        .asNonNullable()]
            val totalLive = species.ifEmpty { null }?.sumOf { it.totalLive }
            val totalPlants =
                species.ifEmpty { null }?.sumOf { it.totalLive + it.totalExisting + it.totalDead }
            val totalLiveSpeciesExceptUnknown =
                species
                    .ifEmpty { null }
                    ?.count {
                      it.certainty != RecordedSpeciesCertainty.Unknown &&
                          (it.totalLive + it.totalExisting) > 0
                    }

            val survivalRate = species.calculateSurvivalRate(survivalRateIncludesTempPlots)

            val areaSquareMeters = sizeMeters * sizeMeters
            val plantingDensity =
                if (totalLive != null) {
                  (totalLive * SQUARE_METERS_PER_HECTARE / areaSquareMeters).roundToInt()
                } else {
                  null
                }

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
                )
                .from(SUBSTRATUM_HISTORIES)
                .leftJoin(SUBSTRATA)
                .on(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
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

            val anyCompleted = monitoringPlots.any { it.completedTime != null }
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

            val isCompleted =
                monitoringPlots.isNotEmpty() && monitoringPlots.all { it.completedTime != null }
            val completedTime =
                if (isCompleted) {
                  monitoringPlots.maxOf { it.completedTime!! }
                } else {
                  null
                }

            val survivalRatePlots = monitoringPlots.filter {
              survivalRateIncludesTempPlots || it.isPermanent
            }
            val survivalRate =
                if (
                    survivalRatePlots.isNotEmpty() &&
                        survivalRatePlots.all { it.survivalRate != null }
                ) {
                  species.calculateSurvivalRate(survivalRateIncludesTempPlots)
                } else {
                  null
                }
            val survivalRateStdDev =
                if (survivalRate != null) {
                  monitoringPlots
                      .mapNotNull { plot ->
                        plot.survivalRate?.let { survivalRate ->
                          val sumDensity = plot.species.mapNotNull { it.t0Density }.sumOf { it }
                          survivalRate to sumDensity.toDouble()
                        }
                      }
                      .calculateWeightedStandardDeviation()
                } else {
                  null
                }

            val plantingCompleted = record[SUBSTRATA.PLANTING_COMPLETED_TIME] != null
            val completedPlotsPlantingDensities =
                monitoringPlots
                    .filter { it.status == ObservationPlotStatus.Completed }
                    .mapNotNull { it.plantingDensity }
            val plantingDensity =
                if (completedPlotsPlantingDensities.isNotEmpty()) {
                  completedPlotsPlantingDensities.average()
                } else {
                  null
                }
            val plantingDensityStdDev = completedPlotsPlantingDensities.calculateStandardDeviation()

            val estimatedPlants =
                if (plantingCompleted && plantingDensity != null) {
                  areaHa.toDouble() * plantingDensity
                } else {
                  null
                }

            ObservationSubstratumResultsModel(
                areaHa = areaHa,
                completedTime = completedTime,
                estimatedPlants = estimatedPlants?.roundToInt(),
                monitoringPlots = monitoringPlots,
                name = record[SUBSTRATUM_HISTORIES.NAME.asNonNullable()],
                plantingCompleted = plantingCompleted,
                plantingDensity = plantingDensity?.roundToInt(),
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
                )
                .from(STRATUM_HISTORIES)
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
            val survivalRateIncludesTempPlots =
                record[
                    STRATUM_HISTORIES.plantingSiteHistories.plantingSites
                        .SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
                        .asNonNullable()]

            val anyCompleted = substrata.any { substratum ->
              substratum.monitoringPlots.any { it.completedTime != null }
            }
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
                identifiedSpecies.ifEmpty { null }?.count { (it.totalLive + it.totalExisting) > 0 }

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

            val monitoringPlots = substrata.flatMap { it.monitoringPlots }

            val survivalRateSubstrata = substrata.filter { substratum ->
              substratum.monitoringPlots.any { survivalRateIncludesTempPlots || it.isPermanent }
            }
            val survivalRate =
                if (
                    survivalRateSubstrata.isNotEmpty() &&
                        survivalRateSubstrata.all { it.survivalRate != null }
                ) {
                  species.calculateSurvivalRate(survivalRateIncludesTempPlots)
                } else {
                  null
                }
            val survivalRateStdDev =
                if (survivalRate != null) {
                  monitoringPlots
                      .mapNotNull { plot ->
                        plot.survivalRate?.let { survivalRate ->
                          val sumDensity = plot.species.mapNotNull { it.t0Density }.sumOf { it }
                          survivalRate to sumDensity.toDouble()
                        }
                      }
                      .calculateWeightedStandardDeviation()
                } else {
                  null
                }

            val plantingCompleted = record[stratumPlantingCompletedField]
            val completedPlotsPlantingDensities =
                monitoringPlots
                    .filter { it.status == ObservationPlotStatus.Completed }
                    .mapNotNull { it.plantingDensity }
            val plantingDensity =
                if (completedPlotsPlantingDensities.isNotEmpty()) {
                  completedPlotsPlantingDensities.average()
                } else {
                  null
                }
            val plantingDensityStdDev = completedPlotsPlantingDensities.calculateStandardDeviation()

            val estimatedPlants =
                if (plantingCompleted && plantingDensity != null) {
                  areaHa.toDouble() * plantingDensity
                } else {
                  null
                }

            ObservationStratumResultsModel(
                areaHa = areaHa,
                completedTime = completedTime,
                estimatedPlants = estimatedPlants?.roundToInt(),
                name = record[STRATUM_HISTORIES.NAME.asNonNullable()],
                plantingCompleted = plantingCompleted,
                plantingDensity = plantingDensity?.roundToInt(),
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

  private fun fetchByCondition(
      condition: Condition,
      depth: ObservationResultsDepth = ObservationResultsDepth.Plot,
      limit: Int?,
  ): List<ObservationResultsModel> {

    // Current implementation requires plot level data to build results. Results will be pruned
    // after query
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
            )
            .from(OBSERVATIONS)
            .leftJoin(PLANTING_SITE_HISTORIES)
            .on(OBSERVATIONS.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
            .where(condition)
            .orderBy(OBSERVATIONS.COMPLETED_TIME.desc().nullsLast(), OBSERVATIONS.ID.desc())
            .let { if (limit != null) it.limit(limit) else it }
            .fetch { record ->
              // Area can be null for an observation that has not started.
              val areaHa = record[PLANTING_SITE_HISTORIES.AREA_HA]

              val strata = record[strataField]
              val monitoringPlots = strata.flatMap { it.substrata }.flatMap { it.monitoringPlots }
              val completedPlots = monitoringPlots.filter {
                it.status == ObservationPlotStatus.Completed
              }
              val species =
                  if (completedPlots.isNotEmpty()) {
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

              val completedPlotsPlantingDensities = completedPlots.mapNotNull { it.plantingDensity }
              val plantingDensity =
                  if (completedPlotsPlantingDensities.isNotEmpty()) {
                    completedPlotsPlantingDensities.average()
                  } else {
                    null
                  }
              val plantingDensityStdDev =
                  completedPlotsPlantingDensities.calculateStandardDeviation()

              val estimatedPlants =
                  if (strata.isNotEmpty() && strata.all { it.estimatedPlants != null }) {
                    strata.mapNotNull { it.estimatedPlants }.sum()
                  } else {
                    null
                  }

              val totalSpecies = if (species.isNotEmpty()) liveSpecies.size else null
              val totalPlants = species.ifEmpty { null }?.sumOf { it.totalLive + it.totalDead }

              val survivalRate =
                  if (strata.isNotEmpty() && strata.all { it.survivalRate != null }) {
                    species.calculateSurvivalRate(survivalRateIncludesTempPlots)
                  } else {
                    null
                  }
              val survivalRateStdDev =
                  if (survivalRate != null) {
                    monitoringPlots
                        .mapNotNull { plot ->
                          plot.survivalRate?.let { survivalRate ->
                            val sumDensity = plot.species.mapNotNull { it.t0Density }.sumOf { it }
                            survivalRate to sumDensity.toDouble()
                          }
                        }
                        .calculateWeightedStandardDeviation()
                  } else {
                    null
                  }

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
                  plantingDensity = plantingDensity?.roundToInt(),
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
