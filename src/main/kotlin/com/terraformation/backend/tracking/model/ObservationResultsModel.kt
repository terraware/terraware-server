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
    val photos: List<ObservationMonitoringPlotPhotoModel>,
    /**
     * Number of live plants per hectare. This is calculated by dividing the number of live plants
     * observed by the number of hectares in the monitoring plot. Existing plants are not counted
     * because the intent is to track how many of the plants that were introduced to the site are
     * still alive.
     */
    val plantingDensity: Int,
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

data class ObservationPlantingSubzoneResultsModel(
    val monitoringPlots: List<ObservationMonitoringPlotResultsModel>,
    val plantingSubzoneId: PlantingSubzoneId,
)

data class ObservationPlantingZoneResultsModel(
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    /**
     * Estimated number of plants in planting zone based on estimated planting density and planting
     * zone area. Only present if all the subzones in the zone have been marked as having completed
     * planting.
     */
    val estimatedPlants: Int?,
    /**
     * Percentage of plants of all species that were dead in this zone's permanent monitoring plots.
     * Dead plants from previous observations are counted in this percentage, but only live plants
     * from the current observation are counted. Existing plants are not counted because the intent
     * is to track the health of plants that were introduced to the site.
     */
    val mortalityRate: Int,
    /**
     * Estimated planting density for the zone based on the observed planting densities of
     * monitoring plots. Only present if all the subzones in the zone have been marked as having
     * completed planting.
     */
    val plantingDensity: Int?,
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
)

data class ObservationResultsModel(
    val completedTime: Instant?,
    /**
     * Estimated total number of live plants at the site, based on the estimated planting density
     * and site size. Only present if all the subzones in the site have been marked as having
     * completed planting.
     */
    val endDate: LocalDate,
    val estimatedPlants: Int?,
    /**
     * Percentage of plants of all species that were dead in this site's permanent monitoring plots.
     * Dead plants from previous observations are counted in this percentage, but only live plants
     * from the current observation are counted. Existing plants are not counted because the intent
     * is to track the health of plants that were introduced to the site.
     */
    val mortalityRate: Int,
    val observationId: ObservationId,
    /**
     * Estimated planting density for the site, based on the observed planting densities of
     * monitoring plots. Only present if all the subzones in the site have been marked as having
     * completed planting.
     */
    val plantingDensity: Int?,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<ObservationPlantingZoneResultsModel>,
    val species: List<ObservationSpeciesResultsModel>,
    val startDate: LocalDate,
    val state: ObservationState,
    val totalSpecies: Int,
)
