package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPhotoType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.ObservedPlotCoordinatesId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

data class ObservationMonitoringPlotPhotoModel(
    val fileId: FileId,
    val gpsCoordinates: Point,
    val position: ObservationPlotPosition?,
    val type: ObservationPhotoType,
)

data class ObservedPlotCoordinatesModel(
    val id: ObservedPlotCoordinatesId,
    val gpsCoordinates: Point,
    val position: ObservationPlotPosition,
)

data class ObservationSpeciesResultsModel(
    val certainty: RecordedSpeciesCertainty,
    /**
     * Number of dead plants observed in permanent monitoring plots in all observations including
     * this one. Used in the mortality rate calculation. This needs to be a cumulative total because
     * dead plants, especially young seedlings, are likely to decay between observations, making
     * them impossible to see when people go out to observe a site. To reduce the amount of double
     * counting of dead plants, we instruct users to only record plants that seem to have died since
     * the previous observation.
     */
    val cumulativeDead: Int,
    /**
     * Percentage of plants in permanent monitoring plots that are dead. If there are no permanent
     * monitoring plots (or if this is a plot-level result for a temporary monitoring plot) this
     * will be null. The mortality rate is calculated using [cumulativeDead] and [permanentLive].
     * Existing plants are not included in the mortality rate because the intent is to track the
     * health of plants that were introduced to the site.
     */
    val mortalityRate: Int?,
    /**
     * Number of live plants observed in permanent plots in this observation, not including existing
     * plants. 0 if this is a plot-level result for a temporary monitoring plot. Used in the
     * survival rate calculation.
     */
    val permanentLive: Int,
    /** Species ID if certainty is Known. */
    val speciesId: SpeciesId?,
    /** User-supplied species name if certainty is Other. */
    val speciesName: String?,
    /**
     * Percentage of plants in permanent monitoring plots that are still alive since the t0 point.
     * If there are no permanent monitoring plots (or if this is a plot-level result for a temporary
     * monitoring plot) this will be null. The survival rate is calculated using [permanentLive] and
     * [t0Density]. Existing plants are not included in the survival rate because the intent is to
     * track the health of plants that were introduced to the site.
     */
    val survivalRate: Int? = null,
    /** Plant Density for this species at the t0 point. */
    val t0Density: BigDecimal?,
    val totalDead: Int,
    val totalExisting: Int,
    val totalLive: Int,
    /** Total number of live and existing plants of this species. */
    val totalPlants: Int,
)

data class ObservationMonitoringPlotResultsModel(
    val boundary: Polygon,
    val claimedByName: String?,
    val claimedByUserId: UserId?,
    val completedTime: Instant?,
    val conditions: Set<ObservableCondition>,
    val coordinates: List<ObservedPlotCoordinatesModel>,
    val elevationMeters: BigDecimal?,
    val isAdHoc: Boolean,
    val isPermanent: Boolean,
    val monitoringPlotId: MonitoringPlotId,
    val monitoringPlotNumber: Long,
    /**
     * If this is a permanent monitoring plot in this observation, percentage of plants of all
     * species that were dead. Dead plants from previous observations are counted in this
     * percentage, but only live plants from the current observation are counted. Existing plants
     * are not counted because the intent is to track the health of plants that were introduced to
     * the site.
     */
    val mortalityRate: Int?,
    val notes: String?,
    /** IDs of newer monitoring plots that overlap with this one. */
    val overlappedByPlotIds: Set<MonitoringPlotId>,
    /** IDs of older monitoring plots that this one overlaps with. */
    val overlapsWithPlotIds: Set<MonitoringPlotId>,
    val photos: List<ObservationMonitoringPlotPhotoModel>,
    /**
     * Number of live plants per hectare. This is calculated by dividing the number of live plants
     * observed by the number of hectares in the monitoring plot. Existing plants are not counted
     * because the intent is to track how many of the plants that were introduced to the site are
     * still alive.
     */
    val plantingDensity: Int,
    val plants: List<RecordedPlantModel>?,
    val sizeMeters: Int,
    val species: List<ObservationSpeciesResultsModel>,
    val status: ObservationPlotStatus,
    /**
     * If this is a permanent monitoring plot in this observation, percentage of plants of all
     * species that have survived since the t0 point. Existing plants are not counted because the
     * intent is to track the health of plants that were introduced to the site.
     */
    val survivalRate: Int?,
    /**
     * Total number of plants recorded. Includes all plants, regardless of live/dead status or
     * species.
     */
    val totalPlants: Int,
    /**
     * Total number of species observed, not counting dead plants. Includes plants with Known and
     * Other certainties. In the case of Other, each distinct user-supplied species name is counted
     * as a separate species for purposes of this total.
     */
    val totalSpecies: Int,
)

/**
 * Common values for monitoring results for different kinds of regions (subzones, planting zones,
 * planting sites).
 */
interface BaseMonitoringResult {
  /**
   * Estimated number of plants in the region based on estimated planting density and area. Only
   * present if all observed subzones in the region have completed planting.
   */
  val estimatedPlants: Int?

  /**
   * Percentage of plants of all species that were dead in the region's permanent monitoring plots.
   * Dead plants from previous observations are counted in this percentage, but only live plants
   * from the current observation are counted. Existing plants are not counted because the intent is
   * to track the health of plants that were introduced to the site.
   */
  val mortalityRate: Int?

  /** Standard deviation of mortality rates of plots */
  val mortalityRateStdDev: Int?

  /**
   * Whether planting has completed. Planting is considered completed if all subzones in the region
   * have completed planting.
   */
  val plantingCompleted: Boolean

  /** Standard deviation of planting density of plots */
  val plantingDensityStdDev: Int?

  /**
   * Estimated planting density for the region based on the observed planting densities of
   * monitoring plots.
   */
  val plantingDensity: Int

  /** List of species result used for this rollup */
  val species: List<ObservationSpeciesResultsModel>

  /**
   * Percentage of plants of all species that were alive in the region's permanent monitoring plots
   * since the t0 point. Only live plants from the current observation are counted towards this
   * percentage. Existing plants are not counted because the intent is to track the health of plants
   * that were introduced to the site.
   */
  val survivalRate: Int?

  /** Standard deviation of survival rates of plots */
  val survivalRateStdDev: Int?

  /**
   * Total number of plants recorded. Includes all plants, regardless of live/dead status or
   * species.
   */
  val totalPlants: Int
  /**
   * Total number of species observed, not counting dead plants. Includes plants with Known and
   * Other certainties. In the case of Other, each distinct user-supplied species name is counted as
   * a separate species for purposes of this total.
   */
  val totalSpecies: Int
}

data class ObservationPlantingSubzoneResultsModel(
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    override val estimatedPlants: Int?,
    override val mortalityRate: Int?,
    override val mortalityRateStdDev: Int?,
    val monitoringPlots: List<ObservationMonitoringPlotResultsModel>,
    val name: String,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int,
    override val plantingDensityStdDev: Int?,
    val plantingSubzoneId: PlantingSubzoneId?,
    override val species: List<ObservationSpeciesResultsModel>,
    override val survivalRate: Int?,
    override val survivalRateStdDev: Int?,
    override val totalPlants: Int,
    override val totalSpecies: Int,
) : BaseMonitoringResult

data class ObservationPlantingZoneResultsModel(
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    override val estimatedPlants: Int?,
    override val mortalityRate: Int?,
    override val mortalityRateStdDev: Int?,
    val name: String,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int,
    override val plantingDensityStdDev: Int?,
    val plantingSubzones: List<ObservationPlantingSubzoneResultsModel>,
    val plantingZoneId: PlantingZoneId?,
    override val species: List<ObservationSpeciesResultsModel>,
    override val survivalRate: Int?,
    override val survivalRateStdDev: Int?,
    override val totalPlants: Int,
    override val totalSpecies: Int,
) : BaseMonitoringResult

data class ObservationResultsModel(
    val adHocPlot: ObservationMonitoringPlotResultsModel?,
    val areaHa: BigDecimal?,
    val biomassDetails: ExistingBiomassDetailsModel?,
    val completedTime: Instant?,
    override val estimatedPlants: Int?,
    val isAdHoc: Boolean,
    override val mortalityRate: Int?,
    override val mortalityRateStdDev: Int?,
    val observationId: ObservationId,
    val observationType: ObservationType,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int,
    override val plantingDensityStdDev: Int?,
    val plantingSiteHistoryId: PlantingSiteHistoryId?,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<ObservationPlantingZoneResultsModel>,
    override val species: List<ObservationSpeciesResultsModel>,
    override val survivalRate: Int?,
    override val survivalRateStdDev: Int?,
    val startDate: LocalDate,
    val state: ObservationState,
    override val totalPlants: Int,
    override val totalSpecies: Int,
) : BaseMonitoringResult

data class ObservationPlantingZoneRollupResultsModel(
    val areaHa: BigDecimal,
    /** Time when the earliest observation in this rollup was completed. */
    val earliestCompletedTime: Instant,
    override val estimatedPlants: Int?,
    /** Time when the latest observation in this rollup was completed. */
    val latestCompletedTime: Instant,
    override val mortalityRate: Int?,
    override val mortalityRateStdDev: Int?,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int,
    override val plantingDensityStdDev: Int?,
    /** List of subzone observation results used for this rollup */
    val plantingSubzones: List<ObservationPlantingSubzoneResultsModel>,
    val plantingZoneId: PlantingZoneId,
    override val species: List<ObservationSpeciesResultsModel>,
    override val survivalRate: Int?,
    override val survivalRateStdDev: Int?,
    override val totalPlants: Int,
    override val totalSpecies: Int,
) : BaseMonitoringResult {
  companion object {
    fun of(
        areaHa: BigDecimal,
        plantingZoneId: PlantingZoneId,
        /** Must include every subzone in the planting zone */
        subzoneResults: Map<PlantingSubzoneId, ObservationPlantingSubzoneResultsModel?>,
    ): ObservationPlantingZoneRollupResultsModel? {
      val nonNullSubzoneResults = subzoneResults.values.filterNotNull()
      if (nonNullSubzoneResults.isEmpty()) {
        return null
      }

      val plantingCompleted = subzoneResults.values.none { it == null || !it.plantingCompleted }

      val completedMonitoringPlots =
          nonNullSubzoneResults
              .flatMap { it.monitoringPlots }
              .filter { it.status == ObservationPlotStatus.Completed }
      val species =
          nonNullSubzoneResults
              .map { it.species }
              .reduce { acc, species -> acc.unionSpecies(species) }

      val totalLiveSpeciesExceptUnknown =
          species.count {
            it.certainty != RecordedSpeciesCertainty.Unknown &&
                (it.totalLive + it.totalExisting) > 0
          }

      val completedPlotsPlantingDensities = completedMonitoringPlots.map { it.plantingDensity }
      val plantingDensity =
          if (completedPlotsPlantingDensities.isNotEmpty()) {
            completedPlotsPlantingDensities.average().roundToInt()
          } else {
            0
          }
      val plantingDensityStdDev = completedPlotsPlantingDensities.calculateStandardDeviation()

      val estimatedPlants =
          if (plantingCompleted) {
            areaHa.toDouble() * plantingDensity
          } else {
            null
          }

      val mortalityRate = species.calculateMortalityRate()
      val mortalityRateStdDev =
          completedMonitoringPlots
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
      val survivalRate = species.calculateSurvivalRate()
      val survivalRateStdDev =
          completedMonitoringPlots
              .mapNotNull { plot ->
                plot.survivalRate?.let { survivalRate ->
                  val sumDensity = plot.species.mapNotNull { it.t0Density }.sumOf { it }
                  survivalRate to sumDensity.toDouble()
                }
              }
              .calculateWeightedStandardDeviation()

      return ObservationPlantingZoneRollupResultsModel(
          areaHa = areaHa,
          earliestCompletedTime = completedMonitoringPlots.minOf { it.completedTime!! },
          estimatedPlants = estimatedPlants?.roundToInt(),
          latestCompletedTime = completedMonitoringPlots.maxOf { it.completedTime!! },
          mortalityRate = mortalityRate,
          mortalityRateStdDev = mortalityRateStdDev,
          plantingCompleted = plantingCompleted,
          plantingDensity = plantingDensity,
          plantingDensityStdDev = plantingDensityStdDev,
          plantingSubzones = nonNullSubzoneResults,
          plantingZoneId = plantingZoneId,
          species = species,
          survivalRate = survivalRate,
          survivalRateStdDev = survivalRateStdDev,
          totalPlants = species.sumOf { it.totalLive + it.totalDead },
          totalSpecies = totalLiveSpeciesExceptUnknown,
      )
    }
  }
}

data class ObservationRollupResultsModel(
    /** Time when the earliest observation in this rollup was completed. */
    val earliestCompletedTime: Instant,
    override val estimatedPlants: Int?,
    /** Time when the latest observation in this rollup was completed. */
    val latestCompletedTime: Instant,
    override val mortalityRate: Int?,
    override val mortalityRateStdDev: Int?,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int,
    override val plantingDensityStdDev: Int?,
    val plantingSiteId: PlantingSiteId,
    /** List of subzone observation results used for this rollup */
    val plantingZones: List<ObservationPlantingZoneRollupResultsModel>,
    /** List of species result used for this rollup */
    override val species: List<ObservationSpeciesResultsModel>,
    override val survivalRate: Int?,
    override val survivalRateStdDev: Int?,
    override val totalPlants: Int,
    override val totalSpecies: Int,
) : BaseMonitoringResult {
  companion object {
    fun of(
        plantingSiteId: PlantingSiteId,
        /** Must include every zone in the planting site */
        zoneResults: Map<PlantingZoneId, ObservationPlantingZoneRollupResultsModel?>,
    ): ObservationRollupResultsModel? {
      val nonNullZoneResults = zoneResults.values.filterNotNull()
      if (nonNullZoneResults.isEmpty()) {
        return null
      }

      val plantingCompleted = zoneResults.values.none { it == null || !it.plantingCompleted }

      val monitoringPlots =
          nonNullZoneResults.flatMap { zone ->
            zone.plantingSubzones.flatMap { it.monitoringPlots }
          }
      val species =
          nonNullZoneResults.map { it.species }.reduce { acc, species -> acc.unionSpecies(species) }

      val totalLiveSpeciesExceptUnknown =
          species.count {
            it.certainty != RecordedSpeciesCertainty.Unknown &&
                (it.totalLive + it.totalExisting) > 0
          }

      val completedPlotsPlantingDensities =
          monitoringPlots
              .filter { it.status == ObservationPlotStatus.Completed }
              .map { it.plantingDensity }
      val plantingDensity =
          if (completedPlotsPlantingDensities.isNotEmpty()) {
            completedPlotsPlantingDensities.average().roundToInt()
          } else {
            0
          }
      val plantingDensityStdDev = completedPlotsPlantingDensities.calculateStandardDeviation()

      val estimatedPlants =
          if (plantingCompleted) {
            nonNullZoneResults.sumOf { it.estimatedPlants ?: 0 }
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
      val survivalRate = species.calculateSurvivalRate()
      val survivalRateStdDev =
          monitoringPlots
              .mapNotNull { plot ->
                plot.survivalRate?.let { survivalRate ->
                  val sumDensity = plot.species.mapNotNull { it.t0Density }.sumOf { it }
                  survivalRate to sumDensity.toDouble()
                }
              }
              .calculateWeightedStandardDeviation()

      return ObservationRollupResultsModel(
          earliestCompletedTime = nonNullZoneResults.minOf { it.earliestCompletedTime },
          estimatedPlants = estimatedPlants,
          latestCompletedTime = nonNullZoneResults.maxOf { it.latestCompletedTime },
          mortalityRate = mortalityRate,
          mortalityRateStdDev = mortalityRateStdDev,
          plantingCompleted = plantingCompleted,
          plantingDensity = plantingDensity,
          plantingDensityStdDev = plantingDensityStdDev,
          plantingSiteId = plantingSiteId,
          plantingZones = nonNullZoneResults,
          species = species,
          survivalRate = survivalRate,
          survivalRateStdDev = survivalRateStdDev,
          totalPlants = species.sumOf { it.totalLive + it.totalDead },
          totalSpecies = totalLiveSpeciesExceptUnknown,
      )
    }
  }
}

data class RecordedPlantModel(
    val certainty: RecordedSpeciesCertainty,
    val gpsCoordinates: Point,
    val id: RecordedPlantId,
    val speciesId: SpeciesId?,
    val speciesName: String?,
    val status: RecordedPlantStatus,
)

/**
 * Calculates the mortality rate across all non-preexisting plants of all species in permanent
 * monitoring plots.
 */
fun List<ObservationSpeciesResultsModel>.calculateMortalityRate(): Int? {
  val numNonExistingPlants = this.sumOf { it.permanentLive + it.cumulativeDead }
  val numDeadPlants = this.sumOf { it.cumulativeDead }

  return if (numNonExistingPlants > 0) {
    (numDeadPlants * 100.0 / numNonExistingPlants).roundToInt()
  } else {
    null
  }
}

fun List<ObservationSpeciesResultsModel>.calculateSurvivalRate(
    sumT0Density: BigDecimal? = null
): Int? {
  val sumDensity = sumT0Density ?: this.mapNotNull { it.t0Density }.sumOf { it }
  val numKnownLive = this.sumOf { it.permanentLive }

  return if (sumDensity > BigDecimal.ZERO) {
    ((numKnownLive * 100.0).toBigDecimal() / sumDensity).setScale(0, RoundingMode.HALF_UP).toInt()
  } else {
    null
  }
}

/**
 * Combining observation species results by summing up numbers for results with matching (certainty,
 * speciesId, speciesName) triple. This is used to build per species data starting from permanent
 * monitoring plots data.
 */
fun List<ObservationSpeciesResultsModel>.unionSpecies(
    other: List<ObservationSpeciesResultsModel>
): List<ObservationSpeciesResultsModel> {
  val combined = this + other
  return combined
      .groupBy { Triple(it.certainty, it.speciesId, it.speciesName) }
      .map { (key, groupedSpecies) ->
        val permanentLive = groupedSpecies.sumOf { it.permanentLive }
        val cumulativeDead = groupedSpecies.sumOf { it.cumulativeDead }
        val numNonExistingPlants = permanentLive + cumulativeDead
        val t0Density = groupedSpecies.sumOf { it.t0Density ?: BigDecimal.ZERO }

        val mortalityRate =
            if (numNonExistingPlants > 0) {
              (cumulativeDead * 100.0 / numNonExistingPlants).roundToInt()
            } else {
              0
            }

        val survivalRate =
            if (t0Density > BigDecimal.ZERO) {
              ((permanentLive * 100.0).toBigDecimal() / t0Density)
                  .setScale(0, RoundingMode.HALF_UP)
                  .toInt()
            } else {
              null
            }

        ObservationSpeciesResultsModel(
            certainty = key.first,
            cumulativeDead = cumulativeDead,
            mortalityRate = mortalityRate,
            permanentLive = permanentLive,
            speciesId = key.second,
            speciesName = key.third,
            survivalRate = survivalRate,
            t0Density = t0Density,
            totalDead = groupedSpecies.sumOf { it.totalDead },
            totalExisting = groupedSpecies.sumOf { it.totalExisting },
            totalLive = groupedSpecies.sumOf { it.totalLive },
            totalPlants = groupedSpecies.sumOf { it.totalPlants },
        )
      }
}

/** Calculate standard deviation */
fun Collection<Int>.calculateStandardDeviation(): Int? {
  if (this.size <= 1) {
    return null
  }

  val numSamples = this.size.toDouble()
  val samples = this.map { it.toDouble() }
  val mean = samples.average()
  val sumSquaredDifferences = samples.sumOf { (it - mean) * (it - mean) }
  val variance = sumSquaredDifferences / (numSamples - 1)

  return sqrt(variance).roundToInt()
}

/**
 * Calculate standard deviation from a collection of pairs of (data, weight). Weight must be the
 * number of samples, so we can correctly apply the (n-1) Bessel's correction.
 */
fun Collection<Pair<Int, Double>>.calculateWeightedStandardDeviation(): Int? {
  if (this.size == 1) {
    // If there's only one sample, then by definition there's no variance.
    return 0
  }

  val totalWeights = this.sumOf { it.second }

  if (totalWeights <= 0) {
    return null
  }

  val weightedMean = this.sumOf { it.first * it.second } / totalWeights

  val weightedSumSquaredDifferences =
      this.sumOf { (it.first - weightedMean) * (it.first - weightedMean) * it.second }
  val variance = weightedSumSquaredDifferences / (totalWeights - 1)

  return sqrt(variance).roundToInt()
}
