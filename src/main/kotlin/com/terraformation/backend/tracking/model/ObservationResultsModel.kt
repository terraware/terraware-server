package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservedPlotCoordinatesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

data class ObservationMonitoringPlotPhotoModel(
    val fileId: FileId,
    val gpsCoordinates: Point,
    val position: ObservationPlotPosition,
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
     * plants. 0 if this is a plot-level result for a temporary monitoring plot.
     */
    val permanentLive: Int,
    /** Species ID if certainty is Known. */
    val speciesId: SpeciesId?,
    /** User-supplied species name if certainty is Other. */
    val speciesName: String?,
    val totalDead: Int,
    val totalExisting: Int,
    val totalLive: Int,
    /** Total number of live and existing plants of this species. */
    val totalPlants: Int,
)

enum class ObservationMonitoringPlotStatus {
  Outstanding,
  InProgress,
  Completed
}

data class ObservationMonitoringPlotResultsModel(
    val boundary: Polygon,
    val claimedByName: String?,
    val claimedByUserId: UserId?,
    val completedTime: Instant?,
    val coordinates: List<ObservedPlotCoordinatesModel>,
    val isPermanent: Boolean,
    val monitoringPlotId: MonitoringPlotId,
    val monitoringPlotName: String,
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
    val sizeMeters: Int,
    val species: List<ObservationSpeciesResultsModel>,
    val status: ObservationMonitoringPlotStatus,
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

interface BaseMonitoringResult {
  /**
   * Estimated number of plants in planting zone based on estimated planting density and planting
   * zone area. Only present if planting is completed.
   */
  val estimatedPlants: Int?

  /**
   * Percentage of plants of all species that were dead in this zone's permanent monitoring plots.
   * Dead plants from previous observations are counted in this percentage, but only live plants
   * from the current observation are counted. Existing plants are not counted because the intent is
   * to track the health of plants that were introduced to the site.
   */
  val mortalityRate: Int

  /**
   * Whether planting has completed. Planting is considered completed if all containing subzones has
   * completed planting.
   */
  val plantingCompleted: Boolean

  /**
   * Estimated planting density for the zone based on the observed planting densities of monitoring
   * plots. Only present if planting is completed.
   */
  val plantingDensity: Int?
}

data class ObservationPlantingSubzoneResultsModel(
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    override val estimatedPlants: Int?,
    override val mortalityRate: Int,
    val monitoringPlots: List<ObservationMonitoringPlotResultsModel>,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int?,
    val plantingSubzoneId: PlantingSubzoneId,
) : BaseMonitoringResult

data class ObservationPlantingZoneResultsModel(
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    override val estimatedPlants: Int?,
    override val mortalityRate: Int,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int?,
    val plantingSubzones: List<ObservationPlantingSubzoneResultsModel>,
    val plantingZoneId: PlantingZoneId,
    val species: List<ObservationSpeciesResultsModel>,
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
) : BaseMonitoringResult

data class ObservationResultsModel(
    val completedTime: Instant?,
    override val estimatedPlants: Int?,
    override val mortalityRate: Int,
    val observationId: ObservationId,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int?,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<ObservationPlantingZoneResultsModel>,
    val species: List<ObservationSpeciesResultsModel>,
    val startDate: LocalDate,
    val state: ObservationState,
    val totalSpecies: Int,
) : BaseMonitoringResult

data class ObservationPlantingZoneRollupResultsModel(
    val areaHa: BigDecimal,
    /** Earliest and latest completed times of the observations used in this rollup. */
    val completedTimeRange: Pair<Instant, Instant>,
    override val estimatedPlants: Int?,
    override val mortalityRate: Int,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int?,
    /** List of subzone observation results used for this rollup */
    val plantingSubzones: List<ObservationPlantingSubzoneResultsModel?>,
    val plantingZoneId: PlantingZoneId,
) : BaseMonitoringResult {
  companion object {
    fun of(
        areaHa: BigDecimal,
        plantingZoneId: PlantingZoneId,
        subzoneResults: List<ObservationPlantingSubzoneResultsModel?>
    ): ObservationPlantingZoneRollupResultsModel? {
      if (subzoneResults.isEmpty()) {
        return null
      }

      val plantingCompleted = subzoneResults.none { it == null || !it.plantingCompleted }

      val nonNullSubzoneResults = subzoneResults.filterNotNull()

      val monitoringPlots = nonNullSubzoneResults.flatMap { it.monitoringPlots }
      val monitoringPlotsSpecies = monitoringPlots.flatMap { it.species }

      val plantingDensity =
          if (plantingCompleted) {
            monitoringPlots.map { it.plantingDensity }.average().roundToInt()
          } else {
            null
          }

      val estimatedPlants =
          if (plantingDensity != null) {
            areaHa.toDouble() * plantingDensity
          } else {
            null
          }

      val mortalityRate = monitoringPlotsSpecies.calculateMortalityRate()

      return ObservationPlantingZoneRollupResultsModel(
          areaHa = areaHa,
          completedTimeRange =
              nonNullSubzoneResults.minOf { it.completedTime!! } to
                  nonNullSubzoneResults.maxOf { it.completedTime!! },
          estimatedPlants = estimatedPlants?.roundToInt(),
          mortalityRate = mortalityRate,
          plantingCompleted = plantingCompleted,
          plantingDensity = plantingDensity,
          plantingSubzones = subzoneResults,
          plantingZoneId = plantingZoneId)
    }
  }
}

data class ObservationRollupResultsModel(
    /** Earliest and latest completed times of the observations used in this rollup. */
    val completedTimeRange: Pair<Instant, Instant>,
    override val estimatedPlants: Int?,
    override val mortalityRate: Int,
    override val plantingCompleted: Boolean,
    override val plantingDensity: Int?,
    val plantingSiteId: PlantingSiteId,
    /** List of subzone observation results used for this rollup */
    val plantingZones: List<ObservationPlantingZoneRollupResultsModel?>,
) : BaseMonitoringResult {
  companion object {
    fun of(
        plantingSiteId: PlantingSiteId,
        zoneResults: List<ObservationPlantingZoneRollupResultsModel?>
    ): ObservationRollupResultsModel? {
      if (zoneResults.isEmpty()) {
        return null
      }

      val plantingCompleted = zoneResults.none { it == null || !it.plantingCompleted }
      val nonNullZoneResults = zoneResults.filterNotNull()

      val monitoringPlots =
          nonNullZoneResults.flatMap { zone ->
            zone.plantingSubzones.flatMap { it?.monitoringPlots ?: emptyList() }
          }
      val monitoringPlotsSpecies = monitoringPlots.flatMap { it.species }

      val plantingDensity =
          if (plantingCompleted) {
            monitoringPlots.map { it.plantingDensity }.average().roundToInt()
          } else {
            null
          }

      val estimatedPlants =
          if (plantingDensity != null) {
            nonNullZoneResults.sumOf { it.estimatedPlants ?: 0 }
          } else {
            null
          }

      val mortalityRate = monitoringPlotsSpecies.calculateMortalityRate()

      return ObservationRollupResultsModel(
          completedTimeRange =
              nonNullZoneResults.minOf { it.completedTimeRange.first } to
                  nonNullZoneResults.maxOf { it.completedTimeRange.second },
          estimatedPlants = estimatedPlants,
          mortalityRate = mortalityRate,
          plantingCompleted = plantingCompleted,
          plantingDensity = plantingDensity,
          plantingSiteId = plantingSiteId,
          plantingZones = zoneResults,
      )
    }
  }
}

/**
 * Calculates the mortality rate across all non-preexisting plants of all species in permanent
 * monitoring plots.
 */
fun List<ObservationSpeciesResultsModel>.calculateMortalityRate(): Int {
  val numNonExistingPlants = this.sumOf { it.permanentLive + it.cumulativeDead }
  val numDeadPlants = this.sumOf { it.cumulativeDead }

  return if (numNonExistingPlants > 0) {
    (numDeadPlants * 100.0 / numNonExistingPlants).roundToInt()
  } else {
    0
  }
}
