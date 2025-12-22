package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesIdConverter
import com.terraformation.backend.db.default_schema.tables.references.FILES
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSiteIdConverter
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumIdConverter
import com.terraformation.backend.db.tracking.SubstratumIdConverter
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_OVERLAPS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_MEDIA_FILES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_CONDITIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_COORDINATES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingBiomassDetailsModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotMediaModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumResultsModel
import com.terraformation.backend.tracking.model.ObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.RecordedPlantModel
import com.terraformation.backend.tracking.model.RecordedTreeModel
import com.terraformation.backend.tracking.model.calculateMortalityRate
import com.terraformation.backend.tracking.model.calculateStandardDeviation
import com.terraformation.backend.tracking.model.calculateSurvivalRate
import com.terraformation.backend.tracking.model.calculateWeightedStandardDeviation
import com.terraformation.backend.util.SQUARE_METERS_PER_HECTARE
import jakarta.inject.Named
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Record12
import org.jooq.Select
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

/**
 * Retrieves the results of observations, with statistics suitable for display to end users.
 *
 * Some of the statistics are calculated at query time here; others are accumulated incrementally in
 * [ObservationStore] as observation results are recorded.
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
  ): List<ObservationResultsModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchByCondition(
        DSL.and(
            OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId),
            OBSERVATIONS.IS_AD_HOC.eq(isAdHoc),
            maxCompletionTime?.let { OBSERVATIONS.COMPLETED_TIME.lessOrEqual(it) },
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
  ): List<ObservationResultsModel> {
    requirePermissions { readOrganization(organizationId) }

    return fetchByCondition(
        DSL.and(
            OBSERVATIONS.plantingSites.ORGANIZATION_ID.eq(organizationId),
            OBSERVATIONS.IS_AD_HOC.eq(isAdHoc),
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
  ): List<ObservationRollupResultsModel> {
    val completedObservations =
        fetchByPlantingSiteId(plantingSiteId, maxCompletionTime = maxCompletionTime).filter {
          (it.state == ObservationState.Completed || it.state == ObservationState.Abandoned) &&
              it.completedTime != null
        }

    val numObservations = completedObservations.size
    val depth = max(1, limit?.let { min(it, numObservations) } ?: numObservations)

    // Remove observations one at a time, to build a historical summary
    return List(depth) {
          val observations = completedObservations.takeLast(numObservations - it)
          plantingSiteSummary(plantingSiteId, observations)
        }
        .filterNotNull()
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

    val latestPerSubstratum =
        resultsBySubstratum.mapValues { (_, results) ->
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
              val substratumResults =
                  substratumIds.associateWith { substratumId -> latestPerSubstratum[substratumId] }

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

  private val biomassSpeciesMultiset =
      with(OBSERVATION_BIOMASS_SPECIES) {
        DSL.multiset(
                DSL.select(
                        SPECIES_ID,
                        SCIENTIFIC_NAME,
                        COMMON_NAME,
                        IS_INVASIVE,
                        IS_THREATENED,
                    )
                    .from(this)
                    .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
            )
            .convertFrom { result ->
              result
                  .map {
                    BiomassSpeciesModel(
                        commonName = it[COMMON_NAME],
                        scientificName = it[SCIENTIFIC_NAME],
                        speciesId = it[SPECIES_ID],
                        isInvasive = it[IS_INVASIVE]!!,
                        isThreatened = it[IS_THREATENED]!!,
                    )
                  }
                  .toSet()
            }
      }

  private val biomassQuadratDetailsMultiset =
      with(OBSERVATION_BIOMASS_QUADRAT_DETAILS) {
        DSL.multiset(
                DSL.select(
                        POSITION_ID,
                        DESCRIPTION,
                    )
                    .from(this)
                    .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
            )
            .convertFrom { result -> result.associate { it[POSITION_ID]!! to it[DESCRIPTION] } }
      }

  private val biomassQuadratSpeciesMultiset =
      with(OBSERVATION_BIOMASS_QUADRAT_SPECIES) {
        DSL.multiset(
                DSL.select(
                        POSITION_ID,
                        ABUNDANCE_PERCENT,
                        OBSERVATION_BIOMASS_SPECIES.SPECIES_ID,
                        OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME,
                    )
                    .from(this)
                    .join(OBSERVATION_BIOMASS_SPECIES)
                    .on(BIOMASS_SPECIES_ID.eq(OBSERVATION_BIOMASS_SPECIES.ID))
                    .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
            )
            .convertFrom { result ->
              result
                  .groupBy { it[POSITION_ID]!! }
                  .mapValues { (_, records) ->
                    records
                        .map {
                          BiomassQuadratSpeciesModel(
                              abundancePercent = it[ABUNDANCE_PERCENT]!!,
                              speciesId = it[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                              speciesName = it[OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME],
                          )
                        }
                        .toSet()
                  }
            }
      }

  private val recordedTreesGpsCoordinatesField = RECORDED_TREES.GPS_COORDINATES.forMultiset()

  private val recordedTreesMultiset =
      with(RECORDED_TREES) {
        DSL.multiset(
                DSL.select(
                        ID,
                        DESCRIPTION,
                        DIAMETER_AT_BREAST_HEIGHT_CM,
                        HEIGHT_M,
                        IS_DEAD,
                        POINT_OF_MEASUREMENT_M,
                        SHRUB_DIAMETER_CM,
                        recordedTreesBiomassSpeciesIdFkey.SPECIES_ID,
                        recordedTreesBiomassSpeciesIdFkey.SCIENTIFIC_NAME,
                        TREE_GROWTH_FORM_ID,
                        TREE_NUMBER,
                        TRUNK_NUMBER,
                        recordedTreesGpsCoordinatesField,
                    )
                    .from(this)
                    .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
                    .orderBy(TREE_NUMBER, TRUNK_NUMBER)
            )
            .convertFrom { result ->
              result.map { RecordedTreeModel.of(it, recordedTreesGpsCoordinatesField) }
            }
      }

  private val biomassDetailsMultiset =
      with(OBSERVATION_BIOMASS_DETAILS) {
        DSL.multiset(
                DSL.select(
                        biomassSpeciesMultiset,
                        biomassQuadratDetailsMultiset,
                        biomassQuadratSpeciesMultiset,
                        DESCRIPTION,
                        FOREST_TYPE_ID,
                        HERBACEOUS_COVER_PERCENT,
                        OBSERVATION_ID,
                        PH,
                        recordedTreesMultiset,
                        SALINITY_PPT,
                        SMALL_TREES_COUNT_LOW,
                        SMALL_TREES_COUNT_HIGH,
                        SOIL_ASSESSMENT,
                        MONITORING_PLOT_ID,
                        TIDE_ID,
                        TIDE_TIME,
                        WATER_DEPTH_CM,
                    )
                    .from(this)
                    .where(OBSERVATION_ID.eq(OBSERVATIONS.ID))
                    .orderBy(MONITORING_PLOT_ID)
            )
            .convertFrom { result ->
              result.map { record ->
                val quadratDescriptions = record[biomassQuadratDetailsMultiset]
                val quadratSpecies = record[biomassQuadratSpeciesMultiset]
                val quadrats =
                    ObservationPlotPosition.entries.associateWith {
                      BiomassQuadratModel(
                          description = quadratDescriptions[it],
                          species = quadratSpecies[it] ?: emptySet(),
                      )
                    }

                ExistingBiomassDetailsModel(
                    description = record[DESCRIPTION],
                    forestType = record[FOREST_TYPE_ID]!!,
                    herbaceousCoverPercent = record[HERBACEOUS_COVER_PERCENT]!!,
                    observationId = record[OBSERVATION_ID]!!,
                    ph = record[PH],
                    quadrats = quadrats,
                    salinityPpt = record[SALINITY_PPT],
                    smallTreeCountRange =
                        record[SMALL_TREES_COUNT_LOW]!! to record[SMALL_TREES_COUNT_HIGH]!!,
                    soilAssessment = record[SOIL_ASSESSMENT]!!,
                    species = record[biomassSpeciesMultiset],
                    plotId = record[MONITORING_PLOT_ID]!!,
                    tide = record[TIDE_ID],
                    tideTime = record[TIDE_TIME],
                    trees = record[recordedTreesMultiset],
                    waterDepthCm = record[WATER_DEPTH_CM],
                )
              }
            }
      }

  private val coordinatesGpsField = OBSERVED_PLOT_COORDINATES.GPS_COORDINATES.forMultiset()

  private val coordinatesMultiset =
      DSL.multiset(
              DSL.select(
                      OBSERVED_PLOT_COORDINATES.ID,
                      coordinatesGpsField,
                      OBSERVED_PLOT_COORDINATES.POSITION_ID,
                  )
                  .from(OBSERVED_PLOT_COORDINATES)
                  .where(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .and(OBSERVED_PLOT_COORDINATES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(OBSERVED_PLOT_COORDINATES.POSITION_ID)
          )
          .convertFrom { result ->
            result.map { record ->
              ObservedPlotCoordinatesModel(
                  id = record[OBSERVED_PLOT_COORDINATES.ID.asNonNullable()],
                  gpsCoordinates = record[coordinatesGpsField.asNonNullable()].centroid,
                  position = record[OBSERVED_PLOT_COORDINATES.POSITION_ID.asNonNullable()],
              )
            }
          }

  private val filesGeolocationField = FILES.GEOLOCATION.forMultiset()

  private val mediaMultiset =
      DSL.multiset(
              DSL.select(
                      FILES.CONTENT_TYPE,
                      filesGeolocationField,
                      OBSERVATION_MEDIA_FILES.CAPTION,
                      OBSERVATION_MEDIA_FILES.FILE_ID,
                      OBSERVATION_MEDIA_FILES.IS_ORIGINAL,
                      OBSERVATION_MEDIA_FILES.POSITION_ID,
                      OBSERVATION_MEDIA_FILES.TYPE_ID,
                  )
                  .from(OBSERVATION_MEDIA_FILES)
                  .join(FILES)
                  .on(OBSERVATION_MEDIA_FILES.FILE_ID.eq(FILES.ID))
                  .where(OBSERVATION_MEDIA_FILES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .and(OBSERVATION_MEDIA_FILES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(OBSERVATION_MEDIA_FILES.FILE_ID)
          )
          .convertFrom { result ->
            result.map { record ->
              ObservationMonitoringPlotMediaModel(
                  caption = record[OBSERVATION_MEDIA_FILES.CAPTION],
                  contentType = record[FILES.CONTENT_TYPE.asNonNullable()],
                  fileId = record[OBSERVATION_MEDIA_FILES.FILE_ID.asNonNullable()],
                  gpsCoordinates = record[filesGeolocationField]?.centroid,
                  isOriginal = record[OBSERVATION_MEDIA_FILES.IS_ORIGINAL.asNonNullable()],
                  position = record[OBSERVATION_MEDIA_FILES.POSITION_ID],
                  type = record[OBSERVATION_MEDIA_FILES.TYPE_ID.asNonNullable()],
              )
            }
          }

  private fun plotHasCompletedObservations(
      monitoringPlotIdField: Field<MonitoringPlotId?>,
      isPermanent: Boolean,
  ): Condition =
      DSL.exists(
          DSL.selectOne()
              .from(
                  DSL.select(OBSERVATION_PLOTS.IS_PERMANENT)
                      .from(OBSERVATION_PLOTS)
                      .where(
                          OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(monitoringPlotIdField)
                              .and(OBSERVATION_PLOTS.COMPLETED_TIME.isNotNull)
                      )
                      .and(OBSERVATION_PLOTS.COMPLETED_TIME.le(OBSERVATIONS.COMPLETED_TIME))
                      .orderBy(
                          OBSERVATION_PLOTS.COMPLETED_TIME.desc(),
                          OBSERVATION_PLOTS.OBSERVATION_ID.desc(),
                      )
                      .limit(1)
                      .asTable("most_recent")
              )
              .where(DSL.field("most_recent.IS_PERMANENT", Boolean::class.java).eq(isPermanent))
      )

  /**
   * Returns a field that converts query results with per-species totals to
   * [ObservationSpeciesResultsModel] objects.
   *
   * @param query A query with the following fields in the following order:
   *     1. Certainty (non-nullable)
   *     2. Mortality rate (nullable)
   *     3. Species ID (nullable)
   *     4. Species name (nullable)
   *     5. Total live (non-nullable)
   *     6. Total dead (non-nullable)
   *     7. Total existing (non-nullable)
   *     8. Cumulative dead in permanent plots (non-nullable)
   *     9. Total live in permanent plots (non-nullable)
   *     10. Survival Rate (nullable)
   *     11. T0 Density (nullable)
   *     12. Latest Live from latest observation (or across multiple observations if not all
   *         substrata are in the latest observation) (non-nullable)
   */
  private fun speciesMultiset(
      query:
          Select<
              Record12<
                  RecordedSpeciesCertainty?,
                  Int?,
                  SpeciesId?,
                  String?,
                  Int?,
                  Int?,
                  Int?,
                  Int?,
                  Int?,
                  Int?,
                  BigDecimal?,
                  Int?,
              >
          >
  ): Field<List<ObservationSpeciesResultsModel>> {
    return DSL.multiset(query).convertFrom { results ->
      results.map { record ->
        val certainty = record.value1()!!
        val mortalityRate = record.value2()
        val speciesId = record.value3()
        val speciesName = record.value4()
        val totalLive = record.value5()!!
        val totalDead = record.value6()!!
        val totalExisting = record.value7()!!
        val cumulativeDead = record.value8()!!
        val permanentLive = record.value9()!!
        val survivalRate = record.value10()
        val t0Density = record.value11()
        val latestLive = record.value12()!!

        ObservationSpeciesResultsModel(
            certainty = certainty,
            cumulativeDead = cumulativeDead,
            latestLive = latestLive,
            mortalityRate = mortalityRate,
            permanentLive = permanentLive,
            speciesId = speciesId,
            speciesName = speciesName,
            survivalRate = survivalRate,
            t0Density = t0Density,
            totalDead = totalDead,
            totalExisting = totalExisting,
            totalLive = totalLive,
            totalPlants = totalLive + totalExisting + totalDead,
        )
      }
    }
  }

  private val monitoringPlotConditionsMultiset =
      DSL.multiset(
              DSL.select(OBSERVATION_PLOT_CONDITIONS.CONDITION_ID)
                  .from(OBSERVATION_PLOT_CONDITIONS)
                  .where(
                      OBSERVATION_PLOT_CONDITIONS.OBSERVATION_ID.eq(
                          OBSERVATION_PLOTS.OBSERVATION_ID
                      )
                  )
                  .and(
                      OBSERVATION_PLOT_CONDITIONS.MONITORING_PLOT_ID.eq(
                          OBSERVATION_PLOTS.MONITORING_PLOT_ID
                      )
                  )
          )
          .convertFrom { results ->
            results.map { record -> record[OBSERVATION_PLOT_CONDITIONS.CONDITION_ID]!! }.toSet()
          }

  private val monitoringPlotOverlappedByMultiset =
      DSL.multiset(
              DSL.select(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID)
                  .from(MONITORING_PLOT_OVERLAPS)
                  .where(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID)
          )
          .convertFrom { results ->
            results.map { record -> record[MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID]!! }.toSet()
          }

  private val monitoringPlotOverlapsMultiset =
      DSL.multiset(
              DSL.select(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID)
                  .from(MONITORING_PLOT_OVERLAPS)
                  .where(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID)
          )
          .convertFrom { results ->
            results.map { record -> record[MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID]!! }.toSet()
          }

  private val monitoringPlotSpeciesMultiset =
      with(OBSERVED_PLOT_SPECIES_TOTALS) {
        speciesMultiset(
            DSL.select(
                    DSL.coalesce(CERTAINTY_ID, RecordedSpeciesCertainty.Known),
                    DSL.case_()
                        .`when`(OBSERVATION_PLOTS.IS_PERMANENT, MORTALITY_RATE)
                        .else_(null as Int?),
                    DSL.coalesce(
                        SPECIES_ID,
                        PLOT_T0_DENSITIES.SPECIES_ID,
                        STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
                    ),
                    SPECIES_NAME,
                    DSL.coalesce(TOTAL_LIVE, 0),
                    DSL.coalesce(TOTAL_DEAD, 0),
                    DSL.coalesce(TOTAL_EXISTING, 0),
                    DSL.coalesce(CUMULATIVE_DEAD, 0),
                    DSL.coalesce(PERMANENT_LIVE, 0),
                    DSL.coalesce(
                        SURVIVAL_RATE,
                        DSL.`when`(
                            PLOT_T0_DENSITIES.PLOT_DENSITY.isNotNull.or(
                                STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY.isNotNull
                            ),
                            DSL.inline(BigDecimal.ZERO),
                        ),
                    ),
                    DSL.case_()
                        .`when`(
                            OBSERVATION_PLOTS.IS_PERMANENT,
                            PLOT_T0_DENSITIES.PLOT_DENSITY,
                        )
                        .`when`(
                            MONITORING_PLOTS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(
                                    true
                                )
                                .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(false)),
                            STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY,
                        )
                        .else_(null as BigDecimal?),
                    DSL.coalesce(TOTAL_LIVE, 0),
                )
                .from(OBSERVED_PLOT_SPECIES_TOTALS)
                // full outer join because we want survival rate to be 0 if a species wasn't
                // observed but has t0 density data set
                .fullOuterJoin(PLOT_T0_DENSITIES)
                .on(
                    PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID)
                        .and(PLOT_T0_DENSITIES.SPECIES_ID.eq(SPECIES_ID))
                        .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(true))
                        .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
                )
                .fullOuterJoin(STRATUM_T0_TEMP_DENSITIES)
                .on(
                    STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                            OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories
                                .stratumHistories
                                .STRATUM_ID
                        )
                        .and(STRATUM_T0_TEMP_DENSITIES.SPECIES_ID.eq(SPECIES_ID))
                        .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(false))
                        .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
                )
                .where(
                    MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID)
                        .or(
                            OBSERVATION_PLOTS.IS_PERMANENT.eq(true)
                                .and(PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                        )
                )
                .or(
                    MONITORING_PLOT_ID.isNull
                        .and(OBSERVATION_PLOTS.IS_PERMANENT.eq(false))
                        .and(
                            STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                                OBSERVATION_PLOTS.monitoringPlotHistories.substratumHistories
                                    .stratumHistories
                                    .STRATUM_ID
                            )
                        )
                    //                        )
                )
                .and(OBSERVATION_ID.eq(OBSERVATIONS.ID).or(OBSERVATION_ID.isNull))
                .orderBy(SPECIES_ID, SPECIES_NAME)
        )
      }

  private val recordedPlantsGpsField = RECORDED_PLANTS.GPS_COORDINATES.forMultiset()

  private val recordedPlantsMultiset =
      DSL.multiset(
              DSL.select(
                      RECORDED_PLANTS.ID,
                      RECORDED_PLANTS.CERTAINTY_ID,
                      RECORDED_PLANTS.SPECIES_ID,
                      RECORDED_PLANTS.SPECIES_NAME,
                      RECORDED_PLANTS.STATUS_ID,
                      recordedPlantsGpsField,
                  )
                  .from(RECORDED_PLANTS)
                  .where(RECORDED_PLANTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .and(RECORDED_PLANTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(RECORDED_PLANTS.ID)
          )
          .convertFrom { results ->
            results.map { record ->
              RecordedPlantModel(
                  certainty = record[RECORDED_PLANTS.CERTAINTY_ID.asNonNullable()],
                  gpsCoordinates = record[recordedPlantsGpsField.asNonNullable()] as Point,
                  id = record[RECORDED_PLANTS.ID.asNonNullable()],
                  speciesId = record[RECORDED_PLANTS.SPECIES_ID],
                  speciesName = record[RECORDED_PLANTS.SPECIES_NAME],
                  status = record[RECORDED_PLANTS.STATUS_ID.asNonNullable()],
              )
            }
          }

  private val monitoringPlotsBoundaryField = MONITORING_PLOTS.BOUNDARY.forMultiset()

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
                    coordinatesMultiset,
                    mediaMultiset,
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
            val totalLive = species.sumOf { it.totalLive }
            val totalPlants = species.sumOf { it.totalLive + it.totalExisting + it.totalDead }
            val totalLiveSpeciesExceptUnknown =
                species.count {
                  it.certainty != RecordedSpeciesCertainty.Unknown &&
                      (it.totalLive + it.totalExisting) > 0
                }

            val mortalityRate = if (isPermanent) species.calculateMortalityRate() else null
            val survivalRate = species.calculateSurvivalRate(survivalRateIncludesTempPlots)

            val areaSquareMeters = sizeMeters * sizeMeters
            val plantingDensity =
                (totalLive * SQUARE_METERS_PER_HECTARE / areaSquareMeters).roundToInt()

            val status = record[OBSERVATION_PLOTS.STATUS_ID]!!

            ObservationMonitoringPlotResultsModel(
                boundary = record[monitoringPlotsBoundaryField] as Polygon,
                claimedByName =
                    TerrawareUser.makeFullName(record[USERS.FIRST_NAME], record[USERS.LAST_NAME]),
                claimedByUserId = claimedBy,
                completedTime = completedTime,
                conditions = record[monitoringPlotConditionsMultiset],
                coordinates = record[coordinatesMultiset],
                elevationMeters = record[MONITORING_PLOTS.ELEVATION_METERS],
                isAdHoc = record[MONITORING_PLOTS.IS_AD_HOC.asNonNullable()],
                isPermanent = isPermanent,
                monitoringPlotId = record[MONITORING_PLOTS.ID]!!,
                monitoringPlotNumber = record[MONITORING_PLOTS.PLOT_NUMBER]!!,
                mortalityRate = mortalityRate,
                notes = record[OBSERVATION_PLOTS.NOTES],
                overlappedByPlotIds = record[monitoringPlotOverlappedByMultiset],
                overlapsWithPlotIds = record[monitoringPlotOverlapsMultiset],
                media = record[mediaMultiset],
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

  private fun substratumSpeciesMultiset(): Field<List<ObservationSpeciesResultsModel>> {
    val permanentSubstratumT0 =
        with(PLOT_T0_DENSITIES) {
          DSL.select(
                  MONITORING_PLOT_HISTORIES.SUBSTRATUM_ID,
                  SPECIES_ID,
                  DSL.sum(PLOT_DENSITY).`as`("plot_density"),
              )
              .from(this)
              .join(MONITORING_PLOT_HISTORIES)
              .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
              .where(plotHasCompletedObservations(MONITORING_PLOT_ID, true))
              .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, true).isNotNull)
              .and(
                  MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID)
              )
              .groupBy(MONITORING_PLOT_HISTORIES.SUBSTRATUM_ID, SPECIES_ID)
              .asTable()
        }

    val tempSubstratumT0 =
        with(MONITORING_PLOT_HISTORIES) {
          DSL.select(
                  SUBSTRATUM_ID,
                  STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
                  DSL.sum(STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY).`as`("plot_density"),
              )
              .from(this)
              .join(STRATUM_T0_TEMP_DENSITIES)
              .on(
                  STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                      substratumHistories.stratumHistories.STRATUM_ID
                  )
              )
              .where(plotHasCompletedObservations(MONITORING_PLOT_ID, false))
              .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, false).isNotNull)
              .and(PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
              .and(plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
              .groupBy(SUBSTRATUM_ID, STRATUM_T0_TEMP_DENSITIES.SPECIES_ID)
              .asTable()
        }

    val permSubstratumCol =
        permanentSubstratumT0.field(
            "substratum_id",
            SQLDataType.BIGINT.asConvertedDataType(SubstratumIdConverter()),
        )!!
    val permSpeciesCol =
        permanentSubstratumT0.field(
            "species_id",
            SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
        )!!
    val permDensityCol = permanentSubstratumT0.field("plot_density", BigDecimal::class.java)!!

    val tempSubstratumCol =
        tempSubstratumT0.field(
            "substratum_id",
            SQLDataType.BIGINT.asConvertedDataType(SubstratumIdConverter()),
        )!!
    val tempSpeciesCol =
        tempSubstratumT0.field(
            "species_id",
            SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
        )!!
    val tempDensityCol = tempSubstratumT0.field("plot_density", BigDecimal::class.java)!!

    return with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
      speciesMultiset(
          DSL.select(
                  DSL.coalesce(CERTAINTY_ID, RecordedSpeciesCertainty.Known),
                  MORTALITY_RATE,
                  DSL.coalesce(SPECIES_ID, permSpeciesCol, tempSpeciesCol),
                  SPECIES_NAME,
                  DSL.coalesce(TOTAL_LIVE, 0),
                  DSL.coalesce(TOTAL_DEAD, 0),
                  DSL.coalesce(TOTAL_EXISTING, 0),
                  DSL.coalesce(CUMULATIVE_DEAD, 0),
                  DSL.coalesce(PERMANENT_LIVE, 0),
                  DSL.coalesce(
                      SURVIVAL_RATE,
                      DSL.`when`(
                          DSL.coalesce(permDensityCol, BigDecimal.ZERO)
                              .plus(DSL.coalesce(tempDensityCol, BigDecimal.ZERO))
                              .gt(BigDecimal.ZERO),
                          BigDecimal.ZERO,
                      ),
                  ),
                  DSL.coalesce(permDensityCol.plus(tempDensityCol), permDensityCol, tempDensityCol),
                  DSL.coalesce(TOTAL_LIVE, 0),
              )
              .from(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
              // full outer join because we want survival rate to be 0 if a species wasn't observed
              // but has t0 density data set
              .fullOuterJoin(permanentSubstratumT0)
              .on(
                  permSubstratumCol
                      .eq(SUBSTRATUM_ID)
                      .and(permSpeciesCol.eq(SPECIES_ID))
                      .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .fullOuterJoin(tempSubstratumT0)
              .on(
                  tempSubstratumCol
                      .eq(SUBSTRATUM_ID)
                      .and(tempSpeciesCol.eq(SPECIES_ID))
                      .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .where(
                  SUBSTRATUM_ID.eq(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID)
                      .or(permSubstratumCol.eq(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID))
                      .or(tempSubstratumCol.eq(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID))
              )
              .and(OBSERVATION_ID.eq(OBSERVATIONS.ID).or(OBSERVATION_ID.isNull))
              .orderBy(SPECIES_ID, SPECIES_NAME)
      )
    }
  }

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

            val species = record[substratumSpeciesMultisetField]
            val totalPlants = species.sumOf { it.totalLive + it.totalDead }
            val totalLiveSpeciesExceptUnknown =
                species.count {
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

            val mortalityRate = species.calculateMortalityRate()
            val mortalityRateStdDev =
                monitoringPlots
                    .mapNotNull { plot ->
                      plot.mortalityRate?.let { mortalityRate ->
                        val permanentPlants =
                            plot.species.sumOf { species ->
                              species.permanentLive + species.cumulativeDead
                            }
                        mortalityRate to permanentPlants.toDouble()
                      }
                    }
                    .calculateWeightedStandardDeviation()
            val survivalRatePlots =
                monitoringPlots.filter { survivalRateIncludesTempPlots || it.isPermanent }
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
                    .map { it.plantingDensity }
            val plantingDensity =
                if (completedPlotsPlantingDensities.isNotEmpty()) {
                  completedPlotsPlantingDensities.average()
                } else {
                  0.0
                }
            val plantingDensityStdDev = completedPlotsPlantingDensities.calculateStandardDeviation()

            val estimatedPlants =
                if (plantingCompleted) {
                  areaHa.toDouble() * plantingDensity
                } else {
                  null
                }

            ObservationSubstratumResultsModel(
                areaHa = areaHa,
                completedTime = completedTime,
                estimatedPlants = estimatedPlants?.roundToInt(),
                monitoringPlots = monitoringPlots,
                mortalityRate = mortalityRate,
                mortalityRateStdDev = mortalityRateStdDev,
                name = record[SUBSTRATUM_HISTORIES.NAME.asNonNullable()],
                plantingCompleted = plantingCompleted,
                plantingDensity = plantingDensity.roundToInt(),
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

  private fun stratumSpeciesMultiset(): Field<List<ObservationSpeciesResultsModel>> {
    val stratumHistoryAlias = STRATUM_HISTORIES.`as`("stratum_histories_2")
    val permStratumT0 =
        with(PLOT_T0_DENSITIES) {
          DSL.select(
                  stratumHistoryAlias.STRATUM_ID,
                  SPECIES_ID,
                  DSL.sum(PLOT_DENSITY).`as`("plot_density"),
              )
              .from(this)
              .join(MONITORING_PLOT_HISTORIES)
              .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
              .join(SUBSTRATUM_HISTORIES)
              .on(SUBSTRATUM_HISTORIES.ID.eq(MONITORING_PLOT_HISTORIES.SUBSTRATUM_HISTORY_ID))
              .join(stratumHistoryAlias)
              .on(stratumHistoryAlias.ID.eq(SUBSTRATUM_HISTORIES.STRATUM_HISTORY_ID))
              .where(plotHasCompletedObservations(MONITORING_PLOT_ID, true))
              .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, true).isNotNull)
              .and(
                  MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID)
              )
              .groupBy(stratumHistoryAlias.STRATUM_ID, SPECIES_ID)
              .asTable()
        }

    val tempStratumT0 =
        with(MONITORING_PLOT_HISTORIES) {
          DSL.select(
                  substratumHistories.stratumHistories.STRATUM_ID,
                  STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
                  DSL.sum(STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY).`as`("plot_density"),
              )
              .from(this)
              .join(SUBSTRATUM_HISTORIES)
              .on(SUBSTRATUM_HISTORIES.ID.eq(SUBSTRATUM_HISTORY_ID))
              .join(STRATUM_T0_TEMP_DENSITIES)
              .on(
                  STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                      substratumHistories.stratumHistories.STRATUM_ID
                  )
              )
              .where(plotHasCompletedObservations(MONITORING_PLOT_ID, false))
              .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, false).isNotNull)
              .and(PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
              .and(plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
              .groupBy(
                  substratumHistories.stratumHistories.STRATUM_ID,
                  STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
              )
              .asTable()
        }

    val permStratumCol =
        permStratumT0.field(
            "stratum_id",
            SQLDataType.BIGINT.asConvertedDataType(StratumIdConverter()),
        )!!
    val permSpeciesCol =
        permStratumT0.field(
            "species_id",
            SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
        )!!
    val permDensityCol = permStratumT0.field("plot_density", BigDecimal::class.java)!!

    val tempStratumCol =
        tempStratumT0.field(
            "stratum_id",
            SQLDataType.BIGINT.asConvertedDataType(StratumIdConverter()),
        )!!
    val tempSpeciesCol =
        tempStratumT0.field(
            "species_id",
            SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
        )!!
    val tempDensityCol = tempStratumT0.field("plot_density", BigDecimal::class.java)!!

    val latestLiveField =
        with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
          DSL.field(
              DSL.select(
                      DSL.sum(DSL.coalesce(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_LIVE, 0))
                          .cast(SQLDataType.INTEGER)
                  )
                  .from(SUBSTRATA)
                  .join(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
                  .on(SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                  .where(
                      SUBSTRATA.STRATUM_ID.eq(OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_ID)
                          .and(SPECIES_ID.eq(OBSERVED_STRATUM_SPECIES_TOTALS.SPECIES_ID))
                  )
                  .and(
                      OBSERVATION_ID.eq(
                          latestObservationForSubstratumField(
                              OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID
                          )
                      )
                  )
          )
        }

    return with(OBSERVED_STRATUM_SPECIES_TOTALS) {
      speciesMultiset(
          DSL.select(
                  DSL.coalesce(CERTAINTY_ID, RecordedSpeciesCertainty.Known),
                  MORTALITY_RATE,
                  DSL.coalesce(SPECIES_ID, permSpeciesCol, tempSpeciesCol),
                  SPECIES_NAME,
                  DSL.coalesce(TOTAL_LIVE, 0),
                  DSL.coalesce(TOTAL_DEAD, 0),
                  DSL.coalesce(TOTAL_EXISTING, 0),
                  DSL.coalesce(CUMULATIVE_DEAD, 0),
                  DSL.coalesce(PERMANENT_LIVE, 0),
                  DSL.coalesce(
                      SURVIVAL_RATE,
                      DSL.`when`(
                          DSL.coalesce(permDensityCol, BigDecimal.ZERO)
                              .plus(DSL.coalesce(tempDensityCol, BigDecimal.ZERO))
                              .gt(BigDecimal.ZERO),
                          BigDecimal.ZERO,
                      ),
                  ),
                  DSL.coalesce(permDensityCol.plus(tempDensityCol), permDensityCol, tempDensityCol),
                  DSL.coalesce(latestLiveField, 0),
              )
              .from(OBSERVED_STRATUM_SPECIES_TOTALS)
              // full outer join because we want survival rate to be 0 if a species wasn't observed
              // but has t0 density data set
              .fullOuterJoin(permStratumT0)
              .on(
                  permStratumCol
                      .eq(STRATUM_ID)
                      .and(permSpeciesCol.eq(SPECIES_ID))
                      .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .fullOuterJoin(tempStratumT0)
              .on(
                  tempStratumCol
                      .eq(STRATUM_ID)
                      .and(tempSpeciesCol.eq(SPECIES_ID))
                      .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .where(
                  STRATUM_ID.eq(STRATUM_HISTORIES.STRATUM_ID)
                      .or(permStratumCol.eq(STRATUM_HISTORIES.STRATUM_ID))
                      .or(tempStratumCol.eq(STRATUM_HISTORIES.STRATUM_ID))
              )
              .and(OBSERVATION_ID.eq(OBSERVATIONS.ID).or(OBSERVATION_ID.isNull))
              .orderBy(SPECIES_ID, SPECIES_NAME)
      )
    }
  }

  private val stratumPlantingCompletedField =
      DSL.field(
          DSL.notExists(
              DSL.selectOne()
                  .from(SUBSTRATA)
                  .where(SUBSTRATA.STRATUM_ID.eq(STRATUM_HISTORIES.STRATUM_ID))
                  .and(
                      SUBSTRATA.PLANTING_COMPLETED_TIME.gt(OBSERVATIONS.COMPLETED_TIME)
                          .or(SUBSTRATA.PLANTING_COMPLETED_TIME.isNull)
                  )
          )
      )

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
            val species = record[stratumSpeciesMultisetField]
            val substrata = record[substrataField]
            val survivalRateIncludesTempPlots =
                record[
                    STRATUM_HISTORIES.plantingSiteHistories.plantingSites
                        .SURVIVAL_RATE_INCLUDES_TEMP_PLOTS
                        .asNonNullable()]

            val identifiedSpecies =
                species.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
            val totalPlants = species.sumOf { it.totalLive + it.totalDead }
            val totalLiveSpeciesExceptUnknown =
                identifiedSpecies.count { (it.totalLive + it.totalExisting) > 0 }

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

            val mortalityRate = species.calculateMortalityRate()
            val mortalityRateStdDev =
                monitoringPlots
                    .mapNotNull { plot ->
                      plot.mortalityRate?.let { mortalityRate ->
                        val permanentPlants =
                            plot.species.sumOf { species ->
                              species.permanentLive + species.cumulativeDead
                            }
                        mortalityRate to permanentPlants.toDouble()
                      }
                    }
                    .calculateWeightedStandardDeviation()
            val survivalRateSubstrata =
                substrata.filter { substratum ->
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
                    .map { it.plantingDensity }
            val plantingDensity =
                if (completedPlotsPlantingDensities.isNotEmpty()) {
                  completedPlotsPlantingDensities.average()
                } else {
                  0.0
                }
            val plantingDensityStdDev = completedPlotsPlantingDensities.calculateStandardDeviation()

            val estimatedPlants =
                if (plantingCompleted) {
                  areaHa.toDouble() * plantingDensity
                } else {
                  null
                }

            ObservationStratumResultsModel(
                areaHa = areaHa,
                completedTime = completedTime,
                estimatedPlants = estimatedPlants?.roundToInt(),
                mortalityRate = mortalityRate,
                mortalityRateStdDev = mortalityRateStdDev,
                name = record[STRATUM_HISTORIES.NAME.asNonNullable()],
                plantingCompleted = plantingCompleted,
                plantingDensity = plantingDensity.roundToInt(),
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

  private fun plantingSiteSpeciesMultiset(): Field<List<ObservationSpeciesResultsModel>> {
    val permSiteT0 =
        with(PLOT_T0_DENSITIES) {
          DSL.select(
                  MONITORING_PLOT_HISTORIES.PLANTING_SITE_ID,
                  SPECIES_ID,
                  DSL.sum(PLOT_DENSITY).`as`("plot_density"),
              )
              .from(this)
              .join(MONITORING_PLOT_HISTORIES)
              .on(MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(MONITORING_PLOT_ID))
              .where(plotHasCompletedObservations(MONITORING_PLOT_ID, true))
              .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, true).isNotNull)
              .and(
                  MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID)
              )
              .groupBy(MONITORING_PLOT_HISTORIES.PLANTING_SITE_ID, SPECIES_ID)
              .asTable()
        }

    val tempSiteT0 =
        with(MONITORING_PLOT_HISTORIES) {
          DSL.select(
                  plantingSiteHistories.plantingSites.ID.`as`("planting_site_id"),
                  STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
                  DSL.sum(STRATUM_T0_TEMP_DENSITIES.STRATUM_DENSITY).`as`("plot_density"),
              )
              .from(this)
              .join(STRATUM_T0_TEMP_DENSITIES)
              .on(
                  STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(
                      substratumHistories.stratumHistories.STRATUM_ID
                  )
              )
              .where(plotHasCompletedObservations(MONITORING_PLOT_ID, false))
              .and(observationIdForPlot(MONITORING_PLOT_ID, OBSERVATIONS.ID, false).isNotNull)
              .and(PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
              .and(plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.eq(true))
              .groupBy(
                  plantingSiteHistories.plantingSites.ID,
                  STRATUM_T0_TEMP_DENSITIES.SPECIES_ID,
              )
              .asTable()
        }

    val permSiteCol =
        permSiteT0.field(
            "planting_site_id",
            SQLDataType.BIGINT.asConvertedDataType(PlantingSiteIdConverter()),
        )!!
    val permSpeciesCol =
        permSiteT0.field(
            "species_id",
            SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
        )!!
    val permDensityCol = permSiteT0.field("plot_density", BigDecimal::class.java)!!

    val tempSiteCol =
        tempSiteT0.field(
            "planting_site_id",
            SQLDataType.BIGINT.asConvertedDataType(PlantingSiteIdConverter()),
        )!!
    val tempSpeciesCol =
        tempSiteT0.field(
            "species_id",
            SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()),
        )!!
    val tempDensityCol = tempSiteT0.field("plot_density", BigDecimal::class.java)!!

    val latestLiveField =
        with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
          DSL.field(
              DSL.select(DSL.sum(DSL.coalesce(TOTAL_LIVE, 0)).cast(SQLDataType.INTEGER))
                  .from(SUBSTRATA)
                  .join(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
                  .on(SUBSTRATUM_ID.eq(SUBSTRATA.ID))
                  .where(
                      SUBSTRATA.PLANTING_SITE_ID.eq(OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID)
                          .and(
                              SPECIES_ID.eq(OBSERVED_SITE_SPECIES_TOTALS.SPECIES_ID)
                                  .or(
                                      SPECIES_ID.isNull
                                          .and(OBSERVED_SITE_SPECIES_TOTALS.SPECIES_ID.isNull)
                                          .and(CERTAINTY_ID.eq(RecordedSpeciesCertainty.Other))
                                          .and(
                                              OBSERVED_SITE_SPECIES_TOTALS.CERTAINTY_ID.eq(
                                                  RecordedSpeciesCertainty.Other
                                              )
                                          )
                                  )
                          )
                  )
                  .and(
                      OBSERVATION_ID.eq(
                          latestObservationForSubstratumField(
                              OBSERVED_SITE_SPECIES_TOTALS.OBSERVATION_ID
                          )
                      )
                  )
          )
        }

    return with(OBSERVED_SITE_SPECIES_TOTALS) {
      speciesMultiset(
          DSL.select(
                  DSL.coalesce(CERTAINTY_ID, RecordedSpeciesCertainty.Known),
                  MORTALITY_RATE,
                  DSL.coalesce(SPECIES_ID, permSpeciesCol, tempSpeciesCol),
                  SPECIES_NAME,
                  DSL.coalesce(TOTAL_LIVE, 0),
                  DSL.coalesce(TOTAL_DEAD, 0),
                  DSL.coalesce(TOTAL_EXISTING, 0),
                  DSL.coalesce(CUMULATIVE_DEAD, 0),
                  DSL.coalesce(PERMANENT_LIVE, 0),
                  DSL.coalesce(
                      SURVIVAL_RATE,
                      DSL.`when`(
                          DSL.coalesce(permDensityCol, BigDecimal.ZERO)
                              .plus(DSL.coalesce(tempDensityCol, BigDecimal.ZERO))
                              .gt(BigDecimal.ZERO),
                          BigDecimal.ZERO,
                      ),
                  ),
                  DSL.coalesce(permDensityCol.plus(tempDensityCol), permDensityCol, tempDensityCol),
                  DSL.coalesce(latestLiveField, 0),
              )
              .from(OBSERVED_SITE_SPECIES_TOTALS)
              // full outer join because we want survival rate to be 0 if a species wasn't observed
              // but has t0 density data set
              .fullOuterJoin(permSiteT0)
              .on(
                  permSiteCol
                      .eq(PLANTING_SITE_ID)
                      .and(permSpeciesCol.eq(SPECIES_ID))
                      .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .fullOuterJoin(tempSiteT0)
              .on(
                  tempSiteCol
                      .eq(PLANTING_SITE_ID)
                      .and(tempSpeciesCol.eq(SPECIES_ID))
                      .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
              )
              .where(
                  PLANTING_SITE_ID.eq(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID)
                      .or(permSiteCol.eq(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID))
                      .or(tempSiteCol.eq(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID))
              )
              .and(OBSERVATION_ID.eq(OBSERVATIONS.ID).or(OBSERVATION_ID.isNull))
              .orderBy(SPECIES_ID, SPECIES_NAME)
      )
    }
  }

  private fun fetchByCondition(
      condition: Condition,
      depth: ObservationResultsDepth = ObservationResultsDepth.Plot,
      limit: Int?,
  ): List<ObservationResultsModel> {
    val adHocPlotsField = adHocMonitoringPlotsMultiset(depth)
    val strataField = stratumMultiset(depth)
    val plantingSiteSpeciesMultisetField = plantingSiteSpeciesMultiset()

    return dslContext
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
          val species = record[plantingSiteSpeciesMultisetField]
          val survivalRateIncludesTempPlots =
              record[OBSERVATIONS.plantingSites.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS.asNonNullable()]

          val knownSpecies = species.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
          val liveSpecies = knownSpecies.filter { it.totalLive > 0 || it.totalExisting > 0 }

          val plantingCompleted = strata.isNotEmpty() && strata.all { it.plantingCompleted }

          val monitoringPlots = strata.flatMap { it.substrata }.flatMap { it.monitoringPlots }
          val completedPlotsPlantingDensities =
              monitoringPlots
                  .filter { it.status == ObservationPlotStatus.Completed }
                  .map { it.plantingDensity }
          val plantingDensity =
              if (completedPlotsPlantingDensities.isNotEmpty()) {
                completedPlotsPlantingDensities.average()
              } else {
                0.0
              }
          val plantingDensityStdDev = completedPlotsPlantingDensities.calculateStandardDeviation()

          val estimatedPlants =
              if (strata.isNotEmpty() && strata.all { it.estimatedPlants != null }) {
                strata.mapNotNull { it.estimatedPlants }.sum()
              } else {
                null
              }

          val totalSpecies = liveSpecies.size
          val totalPlants = species.sumOf { it.totalLive + it.totalDead }

          val mortalityRate = species.calculateMortalityRate()
          val mortalityRateStdDev =
              monitoringPlots
                  .mapNotNull { plot ->
                    plot.mortalityRate?.let { mortalityRate ->
                      val permanentPlants =
                          plot.species.sumOf { species ->
                            species.permanentLive + species.cumulativeDead
                          }
                      mortalityRate to permanentPlants.toDouble()
                    }
                  }
                  .calculateWeightedStandardDeviation()
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
              mortalityRate = mortalityRate,
              mortalityRateStdDev = mortalityRateStdDev,
              observationId = record[OBSERVATIONS.ID.asNonNullable()],
              observationType = record[OBSERVATIONS.OBSERVATION_TYPE_ID.asNonNullable()],
              plantingCompleted = plantingCompleted,
              plantingDensity = plantingDensity.roundToInt(),
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
  }
}
