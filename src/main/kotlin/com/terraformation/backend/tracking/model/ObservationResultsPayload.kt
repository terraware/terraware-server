package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.locationtech.jts.geom.Polygon

data class ObservationMonitoringPlotPhotoPayload(
    val fileId: FileId,
)

data class ObservationSpeciesResultsPayload(
    val certainty: RecordedSpeciesCertainty,
    val mortalityRate: Int?,
    val speciesId: SpeciesId?,
    val speciesName: String?,
    val totalDead: Int,
    val totalExisting: Int,
    val totalLive: Int,
    @Schema(description = "Total number of live and existing plants of this species.")
    val totalPlants: Int,
)

enum class ObservationMonitoringPlotStatus {
  Outstanding,
  InProgress,
  Completed
}

data class ObservationMonitoringPlotResultsPayload(
    val boundary: Polygon,
    val claimedByName: String?,
    val claimedByUserId: UserId?,
    val completedTime: Instant?,
    val isPermanent: Boolean,
    val monitoringPlotId: MonitoringPlotId,
    val monitoringPlotName: String,
    val mortalityRate: Int,
    val notes: String?,
    val photos: List<ObservationMonitoringPlotPhotoPayload>,
    val plantingDensity: Int,
    val species: List<ObservationSpeciesResultsPayload>,
    val status: ObservationMonitoringPlotStatus,
    @Schema(
        description =
            "Total number of plants recorded. Includes all plants, regardless of live/dead " +
                "status or species.")
    val totalPlants: Int,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Includes plants with " +
                "Known and Other certainties. In the case of Other, each distinct user-supplied " +
                "species name is counted as a separate species for purposes of this total.")
    val totalSpecies: Int,
)

data class ObservationPlantingSubzoneResultsPayload(
    val monitoringPlots: List<ObservationMonitoringPlotResultsPayload>,
    val plantingSubzoneId: PlantingSubzoneId,
)

data class ObservationPlantingZoneResultsPayload(
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    val mortalityRate: Int,
    @Schema(
        description =
            "Estimated planting density for the zone, based on the observed planting densities " +
                "of monitoring plots. Only present if all the subzones in the zone have been " +
                "marked as finished planting.")
    val plantingDensity: Int?,
    val plantingSubzones: List<ObservationPlantingSubzoneResultsPayload>,
    val plantingZoneId: PlantingZoneId,
    val species: List<ObservationSpeciesResultsPayload>,
    val totalPlants: Int,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Includes plants with " +
                "Known and Other certainties. In the case of Other, each distinct user-supplied " +
                "species name is counted as a separate species for purposes of this total.")
    val totalSpecies: Int,
)

data class ObservationResultsPayload(
    val completedTime: Instant?,
    val mortalityRate: Int,
    val observationId: ObservationId,
    @Schema(
        description =
            "Estimated planting density for the site, based on the observed planting densities " +
                "of monitoring plots. Only present if all the subzones in the site have been " +
                "marked as finished planting.")
    val plantingDensity: Int?,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<ObservationPlantingZoneResultsPayload>,
    val species: List<ObservationSpeciesResultsPayload>,
    val startDate: LocalDate,
    val state: ObservationState,
    @Schema(
        description =
            "Estimated total number of live plants at the site, based on the estimated planting " +
                "density and site size. Only present if all the subzones in the site have been " +
                "marked as finished planting.")
    val totalPlants: Int?,
    val totalSpecies: Int,
)
