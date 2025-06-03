package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.forMultiset
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_OVERLAPS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_DETAILS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_QUADRAT_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_BIOMASS_SPECIES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PHOTOS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_CONDITIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_COORDINATES
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.RECORDED_TREES
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingBiomassDetailsModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotPhotoModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingSubzoneResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingZoneResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingZoneRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.tracking.model.ObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.calculateMortalityRate
import com.terraformation.backend.tracking.model.calculateStandardDeviation
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
import org.jooq.Record9
import org.jooq.Select
import org.jooq.impl.DSL
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
  fun fetchOneById(observationId: ObservationId): ObservationResultsModel {
    requirePermissions { readObservation(observationId) }

    return fetchByCondition(OBSERVATIONS.ID.eq(observationId), 1).first()
  }

  fun fetchByPlantingSiteId(
      plantingSiteId: PlantingSiteId,
      limit: Int? = null,
      maxCompletionTime: Instant? = null,
      isAdHoc: Boolean = false,
  ): List<ObservationResultsModel> {
    requirePermissions { readPlantingSite(plantingSiteId) }

    return fetchByCondition(
        DSL.and(
            OBSERVATIONS.PLANTING_SITE_ID.eq(plantingSiteId),
            OBSERVATIONS.IS_AD_HOC.eq(isAdHoc),
            maxCompletionTime?.let { OBSERVATIONS.COMPLETED_TIME.lessOrEqual(it) }),
        limit)
  }

  fun fetchByOrganizationId(
      organizationId: OrganizationId,
      limit: Int? = null,
      isAdHoc: Boolean = false,
  ): List<ObservationResultsModel> {
    requirePermissions { readOrganization(organizationId) }

    return fetchByCondition(
        DSL.and(
            OBSERVATIONS.plantingSites.ORGANIZATION_ID.eq(organizationId),
            OBSERVATIONS.IS_AD_HOC.eq(isAdHoc)),
        limit)
  }

  /**
   * Retrieves historical summaries for a planting site by aggregating most recent observation data
   * per subzone, ordered chronologically, latest first. Each summary represents the summary data
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
      completedObservations: List<ObservationResultsModel>
  ): ObservationRollupResultsModel? {
    val allSubzoneIdsByZoneIds =
        dslContext
            .select(PLANTING_SUBZONES.ID, PLANTING_SUBZONES.PLANTING_ZONE_ID)
            .from(PLANTING_SUBZONES)
            .where(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(plantingSiteId))
            .groupBy({ it[PLANTING_SUBZONES.PLANTING_ZONE_ID]!! }, { it[PLANTING_SUBZONES.ID]!! })

    val zoneAreasById =
        dslContext
            .select(PLANTING_ZONES.ID, PLANTING_ZONES.AREA_HA)
            .from(PLANTING_ZONES)
            .where(PLANTING_ZONES.ID.`in`(allSubzoneIdsByZoneIds.keys))
            .associate { it[PLANTING_ZONES.ID]!! to it[PLANTING_ZONES.AREA_HA]!! }

    val resultsBySubzone =
        completedObservations
            .flatMap { it.plantingZones }
            .flatMap { it.plantingSubzones }
            .filter { subzone ->
              subzone.monitoringPlots.any { it.status == ObservationPlotStatus.Completed }
            }
            .groupBy { it.plantingSubzoneId }

    val latestPerSubzone =
        resultsBySubzone.mapValues { (_, results) ->
          results.maxBy { result ->
            // Completed time should have at least one non-nulls by filtering by Completed.
            result.monitoringPlots.maxOf { it.completedTime ?: Instant.EPOCH }
          }
        }

    val plantingZoneResults =
        allSubzoneIdsByZoneIds
            .map {
              val zoneId = it.key

              val areaHa = zoneAreasById[zoneId]!!
              val subzoneIds = it.value
              val subzoneResults =
                  subzoneIds.associateWith { subzoneId -> latestPerSubzone[subzoneId] }

              zoneId to ObservationPlantingZoneRollupResultsModel.of(areaHa, zoneId, subzoneResults)
            }
            .toMap()

    return ObservationRollupResultsModel.of(plantingSiteId, plantingZoneResults)
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
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID)))
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
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID)))
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
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID)))
            .convertFrom { result ->
              result
                  .groupBy { it[POSITION_ID]!! }
                  .mapValues { (_, records) ->
                    records
                        .map {
                          BiomassQuadratSpeciesModel(
                              abundancePercent = it[ABUNDANCE_PERCENT]!!,
                              speciesId = it[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                              speciesName = it[OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME])
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
                        OBSERVATION_BIOMASS_SPECIES.SPECIES_ID,
                        OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME,
                        TREE_GROWTH_FORM_ID,
                        TREE_NUMBER,
                        TRUNK_NUMBER,
                        recordedTreesGpsCoordinatesField,
                    )
                    .from(this)
                    .join(OBSERVATION_BIOMASS_SPECIES)
                    .on(BIOMASS_SPECIES_ID.eq(OBSERVATION_BIOMASS_SPECIES.ID))
                    .where(OBSERVATION_ID.eq(OBSERVATION_BIOMASS_DETAILS.OBSERVATION_ID))
                    .and(MONITORING_PLOT_ID.eq(OBSERVATION_BIOMASS_DETAILS.MONITORING_PLOT_ID))
                    .orderBy(TREE_NUMBER, TRUNK_NUMBER))
            .convertFrom { result ->
              result.map {
                ExistingRecordedTreeModel(
                    id = it[ID]!!,
                    description = it[DESCRIPTION],
                    diameterAtBreastHeightCm = it[DIAMETER_AT_BREAST_HEIGHT_CM],
                    gpsCoordinates = it[recordedTreesGpsCoordinatesField] as? Point,
                    heightM = it[HEIGHT_M],
                    isDead = it[IS_DEAD]!!,
                    pointOfMeasurementM = it[POINT_OF_MEASUREMENT_M],
                    shrubDiameterCm = it[SHRUB_DIAMETER_CM],
                    speciesId = it[OBSERVATION_BIOMASS_SPECIES.SPECIES_ID],
                    speciesName = it[OBSERVATION_BIOMASS_SPECIES.SCIENTIFIC_NAME],
                    treeGrowthForm = it[TREE_GROWTH_FORM_ID]!!,
                    treeNumber = it[TREE_NUMBER]!!,
                    trunkNumber = it[TRUNK_NUMBER]!!,
                )
              }
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
                    .orderBy(MONITORING_PLOT_ID))
            .convertFrom { result ->
              result.map { record ->
                val quadratDescriptions = record[biomassQuadratDetailsMultiset]
                val quadratSpecies = record[biomassQuadratSpeciesMultiset]
                val quadrats =
                    ObservationPlotPosition.entries.associateWith {
                      BiomassQuadratModel(
                          description = quadratDescriptions[it],
                          species = quadratSpecies[it] ?: emptySet())
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
                      OBSERVED_PLOT_COORDINATES.POSITION_ID)
                  .from(OBSERVED_PLOT_COORDINATES)
                  .where(OBSERVED_PLOT_COORDINATES.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .and(OBSERVED_PLOT_COORDINATES.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(OBSERVED_PLOT_COORDINATES.POSITION_ID))
          .convertFrom { result ->
            result.map { record ->
              ObservedPlotCoordinatesModel(
                  id = record[OBSERVED_PLOT_COORDINATES.ID.asNonNullable()],
                  gpsCoordinates = record[coordinatesGpsField.asNonNullable()].centroid,
                  position = record[OBSERVED_PLOT_COORDINATES.POSITION_ID.asNonNullable()],
              )
            }
          }

  private val photosGpsField = OBSERVATION_PHOTOS.GPS_COORDINATES.forMultiset()

  private val photosMultiset =
      DSL.multiset(
              DSL.select(
                      OBSERVATION_PHOTOS.FILE_ID,
                      photosGpsField,
                      OBSERVATION_PHOTOS.POSITION_ID,
                      OBSERVATION_PHOTOS.TYPE_ID,
                  )
                  .from(OBSERVATION_PHOTOS)
                  .where(OBSERVATION_PHOTOS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .and(OBSERVATION_PHOTOS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(OBSERVATION_PHOTOS.FILE_ID))
          .convertFrom { result ->
            result.map { record ->
              ObservationMonitoringPlotPhotoModel(
                  fileId = record[OBSERVATION_PHOTOS.FILE_ID.asNonNullable()],
                  gpsCoordinates = record[photosGpsField.asNonNullable()] as Point,
                  position = record[OBSERVATION_PHOTOS.POSITION_ID],
                  type = record[OBSERVATION_PHOTOS.TYPE_ID.asNonNullable()],
              )
            }
          }

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
   */
  private fun speciesMultiset(
      query:
          Select<
              Record9<
                  RecordedSpeciesCertainty?,
                  Int?,
                  SpeciesId?,
                  String?,
                  Int?,
                  Int?,
                  Int?,
                  Int?,
                  Int?>>
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

        ObservationSpeciesResultsModel(
            certainty = certainty,
            cumulativeDead = cumulativeDead,
            mortalityRate = mortalityRate,
            permanentLive = permanentLive,
            speciesId = speciesId,
            speciesName = speciesName,
            totalDead = totalDead,
            totalExisting = totalExisting,
            totalLive = totalLive,
            totalPlants = totalLive + totalExisting,
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
                          OBSERVATION_PLOTS.OBSERVATION_ID))
                  .and(
                      OBSERVATION_PLOT_CONDITIONS.MONITORING_PLOT_ID.eq(
                          OBSERVATION_PLOTS.MONITORING_PLOT_ID)))
          .convertFrom { results ->
            results.map { record -> record[OBSERVATION_PLOT_CONDITIONS.CONDITION_ID]!! }.toSet()
          }

  private val monitoringPlotOverlappedByMultiset =
      DSL.multiset(
              DSL.select(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID)
                  .from(MONITORING_PLOT_OVERLAPS)
                  .where(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID))
          .convertFrom { results ->
            results.map { record -> record[MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID]!! }.toSet()
          }

  private val monitoringPlotOverlapsMultiset =
      DSL.multiset(
              DSL.select(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID)
                  .from(MONITORING_PLOT_OVERLAPS)
                  .where(MONITORING_PLOT_OVERLAPS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .orderBy(MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID))
          .convertFrom { results ->
            results.map { record -> record[MONITORING_PLOT_OVERLAPS.OVERLAPS_PLOT_ID]!! }.toSet()
          }

  private val monitoringPlotSpeciesMultiset =
      with(OBSERVED_PLOT_SPECIES_TOTALS) {
        speciesMultiset(
            DSL.select(
                    CERTAINTY_ID,
                    DSL.case_()
                        .`when`(OBSERVATION_PLOTS.IS_PERMANENT, MORTALITY_RATE)
                        .else_(null as Int?),
                    SPECIES_ID,
                    SPECIES_NAME,
                    TOTAL_LIVE,
                    TOTAL_DEAD,
                    TOTAL_EXISTING,
                    CUMULATIVE_DEAD,
                    PERMANENT_LIVE,
                )
                .from(OBSERVED_PLOT_SPECIES_TOTALS)
                .where(MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
                .orderBy(SPECIES_ID, SPECIES_NAME))
      }

  private val monitoringPlotsBoundaryField = MONITORING_PLOTS.BOUNDARY.forMultiset()

  /** monitoring plots for an observation */
  private fun monitoringPlotMultiset(condition: Condition) =
      DSL.multiset(
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
                      photosMultiset)
                  .from(OBSERVATION_PLOTS)
                  .join(MONITORING_PLOTS)
                  .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(MONITORING_PLOTS.ID))
                  .join(MONITORING_PLOT_HISTORIES)
                  .on(
                      OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(
                          MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID))
                  .leftJoin(USERS)
                  .on(OBSERVATION_PLOTS.CLAIMED_BY.eq(USERS.ID))
                  .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                  .and(
                      MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                          OBSERVATIONS.PLANTING_SITE_HISTORY_ID))
                  .and(condition))
          .convertFrom { results ->
            results.map { record ->
              val claimedBy = record[OBSERVATION_PLOTS.CLAIMED_BY]
              val completedTime = record[OBSERVATION_PLOTS.COMPLETED_TIME]
              val isPermanent = record[OBSERVATION_PLOTS.IS_PERMANENT.asNonNullable()]
              val sizeMeters = record[MONITORING_PLOTS.SIZE_METERS]!!
              val species = record[monitoringPlotSpeciesMultiset]
              val totalLive = species.sumOf { it.totalLive }
              val totalPlants = species.sumOf { it.totalLive + it.totalExisting + it.totalDead }
              val totalLiveSpeciesExceptUnknown =
                  species.count {
                    it.certainty != RecordedSpeciesCertainty.Unknown &&
                        (it.totalLive + it.totalExisting) > 0
                  }

              val mortalityRate = if (isPermanent) species.calculateMortalityRate() else null

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
                  photos = record[photosMultiset],
                  plantingDensity = plantingDensity,
                  sizeMeters = sizeMeters,
                  species = species,
                  status = status,
                  totalPlants = totalPlants,
                  totalSpecies = totalLiveSpeciesExceptUnknown,
              )
            }
          }

  private val adHocMonitoringPlotMultiset =
      monitoringPlotMultiset(MONITORING_PLOTS.IS_AD_HOC.isTrue())

  private val plantingSubzoneMonitoringPlotMultiset =
      monitoringPlotMultiset(
          MONITORING_PLOT_HISTORIES.PLANTING_SUBZONE_HISTORY_ID.eq(PLANTING_SUBZONE_HISTORIES.ID))

  private val plantingSubzoneSpeciesMultiset =
      with(OBSERVED_SUBZONE_SPECIES_TOTALS) {
        speciesMultiset(
            DSL.select(
                    CERTAINTY_ID,
                    MORTALITY_RATE,
                    SPECIES_ID,
                    SPECIES_NAME,
                    TOTAL_LIVE,
                    TOTAL_DEAD,
                    TOTAL_EXISTING,
                    CUMULATIVE_DEAD,
                    PERMANENT_LIVE,
                )
                .from(OBSERVED_SUBZONE_SPECIES_TOTALS)
                .where(PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID))
                .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
                .orderBy(SPECIES_ID, SPECIES_NAME))
      }

  private val plantingSubzoneMultiset =
      DSL.multiset(
              DSL.select(
                      PLANTING_SUBZONE_HISTORIES.AREA_HA,
                      PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID,
                      PLANTING_SUBZONE_HISTORIES.NAME,
                      PLANTING_SUBZONES.PLANTING_COMPLETED_TIME,
                      plantingSubzoneMonitoringPlotMultiset,
                      plantingSubzoneSpeciesMultiset,
                  )
                  .from(PLANTING_SUBZONE_HISTORIES)
                  .leftJoin(PLANTING_SUBZONES)
                  .on(PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
                  .where(
                      PLANTING_SUBZONE_HISTORIES.ID.`in`(
                          DSL.select(MONITORING_PLOT_HISTORIES.PLANTING_SUBZONE_HISTORY_ID)
                              .from(MONITORING_PLOT_HISTORIES)
                              .join(OBSERVATION_PLOTS)
                              .on(
                                  MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID.eq(
                                      OBSERVATION_PLOTS.MONITORING_PLOT_ID))
                              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                              .and(
                                  PLANTING_SUBZONE_HISTORIES.PLANTING_ZONE_HISTORY_ID.eq(
                                      PLANTING_ZONE_HISTORIES.ID))
                              .and(
                                  MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                                      OBSERVATIONS.PLANTING_SITE_HISTORY_ID)))))
          .convertFrom { results ->
            results.map { record ->
              val monitoringPlots = record[plantingSubzoneMonitoringPlotMultiset]

              val areaHa = record[PLANTING_SUBZONE_HISTORIES.AREA_HA]!!

              val species = record[plantingSubzoneSpeciesMultiset]
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
                          mortalityRate to permanentPlants
                        }
                      }
                      .calculateWeightedStandardDeviation()

              val plantingCompleted = record[PLANTING_SUBZONES.PLANTING_COMPLETED_TIME] != null
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
              val plantingDensityStdDev =
                  completedPlotsPlantingDensities.calculateStandardDeviation()

              val estimatedPlants =
                  if (plantingCompleted) {
                    areaHa.toDouble() * plantingDensity
                  } else {
                    null
                  }

              ObservationPlantingSubzoneResultsModel(
                  areaHa = areaHa,
                  completedTime = completedTime,
                  estimatedPlants = estimatedPlants?.roundToInt(),
                  monitoringPlots = monitoringPlots,
                  mortalityRate = mortalityRate,
                  mortalityRateStdDev = mortalityRateStdDev,
                  name = record[PLANTING_SUBZONE_HISTORIES.NAME.asNonNullable()],
                  plantingCompleted = plantingCompleted,
                  plantingDensity = plantingDensity.roundToInt(),
                  plantingDensityStdDev = plantingDensityStdDev,
                  plantingSubzoneId =
                      record[PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID.asNonNullable()],
                  species = species,
                  totalPlants = totalPlants,
                  totalSpecies = totalLiveSpeciesExceptUnknown,
              )
            }
          }

  private val plantingZoneSpeciesMultiset =
      with(OBSERVED_ZONE_SPECIES_TOTALS) {
        speciesMultiset(
            DSL.select(
                    CERTAINTY_ID,
                    MORTALITY_RATE,
                    SPECIES_ID,
                    SPECIES_NAME,
                    TOTAL_LIVE,
                    TOTAL_DEAD,
                    TOTAL_EXISTING,
                    CUMULATIVE_DEAD,
                    PERMANENT_LIVE,
                )
                .from(OBSERVED_ZONE_SPECIES_TOTALS)
                .where(PLANTING_ZONE_ID.eq(PLANTING_ZONE_HISTORIES.PLANTING_ZONE_ID))
                .and(OBSERVATION_ID.eq(OBSERVATIONS.ID))
                .orderBy(SPECIES_ID, SPECIES_NAME))
      }

  private val zonePlantingCompletedField =
      DSL.field(
          DSL.notExists(
              DSL.selectOne()
                  .from(PLANTING_SUBZONES)
                  .where(
                      PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(
                          PLANTING_ZONE_HISTORIES.PLANTING_ZONE_ID))
                  .and(
                      PLANTING_SUBZONES.PLANTING_COMPLETED_TIME.gt(OBSERVATIONS.COMPLETED_TIME)
                          .or(PLANTING_SUBZONES.PLANTING_COMPLETED_TIME.isNull))))

  private val plantingZoneMultiset =
      DSL.multiset(
              DSL.select(
                      PLANTING_ZONE_HISTORIES.AREA_HA,
                      PLANTING_ZONE_HISTORIES.PLANTING_ZONE_ID,
                      PLANTING_ZONE_HISTORIES.NAME,
                      plantingSubzoneMultiset,
                      plantingZoneSpeciesMultiset,
                      zonePlantingCompletedField,
                  )
                  .from(PLANTING_ZONE_HISTORIES)
                  .where(
                      PLANTING_ZONE_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                          OBSERVATIONS.PLANTING_SITE_HISTORY_ID))
                  .and(
                      PLANTING_ZONE_HISTORIES.ID.`in`(
                          DSL.select(PLANTING_SUBZONE_HISTORIES.PLANTING_ZONE_HISTORY_ID)
                              .from(OBSERVATION_PLOTS)
                              .join(MONITORING_PLOT_HISTORIES)
                              .on(
                                  OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(
                                      MONITORING_PLOT_HISTORIES.MONITORING_PLOT_ID))
                              .join(PLANTING_SUBZONE_HISTORIES)
                              .on(
                                  MONITORING_PLOT_HISTORIES.PLANTING_SUBZONE_HISTORY_ID.eq(
                                      PLANTING_SUBZONE_HISTORIES.ID))
                              .where(OBSERVATION_PLOTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
                              .and(
                                  MONITORING_PLOT_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                                      OBSERVATIONS.PLANTING_SITE_HISTORY_ID)))))
          .convertFrom { results ->
            results.map { record ->
              val areaHa = record[PLANTING_ZONE_HISTORIES.AREA_HA]!!
              val species = record[plantingZoneSpeciesMultiset]
              val subzones = record[plantingSubzoneMultiset]
              val identifiedSpecies =
                  species.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              val totalPlants = species.sumOf { it.totalLive + it.totalDead }
              val totalLiveSpeciesExceptUnknown =
                  identifiedSpecies.count { (it.totalLive + it.totalExisting) > 0 }

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

              val monitoringPlots = subzones.flatMap { it.monitoringPlots }

              val mortalityRate = species.calculateMortalityRate()
              val mortalityRateStdDev =
                  monitoringPlots
                      .mapNotNull { plot ->
                        plot.mortalityRate?.let { mortalityRate ->
                          val permanentPlants =
                              plot.species.sumOf { species ->
                                species.permanentLive + species.cumulativeDead
                              }
                          mortalityRate to permanentPlants
                        }
                      }
                      .calculateWeightedStandardDeviation()

              val plantingCompleted = record[zonePlantingCompletedField]
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
              val plantingDensityStdDev =
                  completedPlotsPlantingDensities.calculateStandardDeviation()

              val estimatedPlants =
                  if (plantingCompleted) {
                    areaHa.toDouble() * plantingDensity
                  } else {
                    null
                  }

              ObservationPlantingZoneResultsModel(
                  areaHa = areaHa,
                  completedTime = completedTime,
                  estimatedPlants = estimatedPlants?.roundToInt(),
                  mortalityRate = mortalityRate,
                  mortalityRateStdDev = mortalityRateStdDev,
                  name = record[PLANTING_ZONE_HISTORIES.NAME.asNonNullable()],
                  plantingCompleted = plantingCompleted,
                  plantingDensity = plantingDensity.roundToInt(),
                  plantingDensityStdDev = plantingDensityStdDev,
                  plantingSubzones = subzones,
                  plantingZoneId = record[PLANTING_ZONE_HISTORIES.PLANTING_ZONE_ID.asNonNullable()],
                  species = identifiedSpecies,
                  totalSpecies = totalLiveSpeciesExceptUnknown,
                  totalPlants = totalPlants,
              )
            }
          }

  private val plantingSiteSpeciesMultiset =
      with(OBSERVED_SITE_SPECIES_TOTALS) {
        speciesMultiset(
            DSL.select(
                    CERTAINTY_ID,
                    MORTALITY_RATE,
                    SPECIES_ID,
                    SPECIES_NAME,
                    TOTAL_LIVE,
                    TOTAL_DEAD,
                    TOTAL_EXISTING,
                    CUMULATIVE_DEAD,
                    PERMANENT_LIVE,
                )
                .from(OBSERVED_SITE_SPECIES_TOTALS)
                .where(OBSERVATION_ID.eq(OBSERVATIONS.ID))
                .orderBy(SPECIES_ID, SPECIES_NAME))
      }

  private fun fetchByCondition(condition: Condition, limit: Int?): List<ObservationResultsModel> {
    return dslContext
        .select(
            adHocMonitoringPlotMultiset,
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
            plantingSiteSpeciesMultiset,
            plantingZoneMultiset)
        .from(OBSERVATIONS)
        .leftJoin(PLANTING_SITE_HISTORIES)
        .on(OBSERVATIONS.PLANTING_SITE_HISTORY_ID.eq(PLANTING_SITE_HISTORIES.ID))
        .where(condition)
        .orderBy(OBSERVATIONS.COMPLETED_TIME.desc().nullsLast(), OBSERVATIONS.ID.desc())
        .let { if (limit != null) it.limit(limit) else it }
        .fetch { record ->
          // Area can be null for an observation that has not started.
          val areaHa = record[PLANTING_SITE_HISTORIES.AREA_HA]

          val zones = record[plantingZoneMultiset]
          val species = record[plantingSiteSpeciesMultiset]
          val knownSpecies = species.filter { it.certainty != RecordedSpeciesCertainty.Unknown }
          val liveSpecies = knownSpecies.filter { it.totalLive > 0 || it.totalExisting > 0 }

          val plantingCompleted = zones.isNotEmpty() && zones.all { it.plantingCompleted }

          val monitoringPlots = zones.flatMap { it.plantingSubzones }.flatMap { it.monitoringPlots }
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
              if (zones.isNotEmpty() && zones.all { it.estimatedPlants != null }) {
                zones.mapNotNull { it.estimatedPlants }.sum()
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
                      mortalityRate to permanentPlants
                    }
                  }
                  .calculateWeightedStandardDeviation()

          ObservationResultsModel(
              adHocPlot = record[adHocMonitoringPlotMultiset].firstOrNull(),
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
              plantingZones = zones,
              species = knownSpecies,
              startDate = record[OBSERVATIONS.START_DATE.asNonNullable()],
              state = record[OBSERVATIONS.STATE_ID.asNonNullable()],
              totalPlants = totalPlants,
              totalSpecies = totalSpecies,
          )
        }
  }
}
