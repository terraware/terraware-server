package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/tracking/observations")
@RestController
@TrackingEndpoint
class ObservationsController {
  @GetMapping
  @Operation(summary = "Gets a list of current and future observations of planting sites.")
  fun listObservations(
      @RequestParam
      @Schema(
          description =
              "Limit results to observations of planting sites in a specific organization. " +
                  "Ignored if plantingSiteId is specified.")
      organizationId: OrganizationId? = null,
      @RequestParam
      @Schema(description = "Limit results to observations of a specific planting site.")
      plantingSiteId: PlantingSiteId? = null,
  ): ListObservationsResponsePayload {
    return ListObservationsResponsePayload(emptyList(), emptyList(), 0)
  }

  @GetMapping("/{observationId}/plots")
  @Operation(
      summary = "Gets a list of monitoring plots that need to be surveyed in an observation.")
  fun listObservablePlots(
      @PathVariable observationId: ObservationId
  ): ListObservablePlotsResponsePayload {
    return ListObservablePlotsResponsePayload(emptyList())
  }

  @ApiResponseSimpleSuccess
  @ApiResponse409("The plot was already completed.")
  @Operation(summary = "Stores the results of a completed observation of a plot.")
  @PostMapping("/{observationId}/plots/{plotId}")
  fun completePlotObservation(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @RequestBody payload: CompletePlotObservationRequestPayload,
  ): SimpleSuccessResponsePayload {
    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Uploads a photo of a monitoring plot.")
  @PostMapping(
      "/{observationId}/plots/{plotId}/photos", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @RequestBodyPhotoFile
  fun uploadPlotPhoto(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @RequestPart("file") file: MultipartFile,
      @RequestPart("payload") payload: UploadPlotPhotoRequestPayload,
  ): SimpleSuccessResponsePayload {
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse409("The plot is already claimed by someone else.")
  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Claims a monitoring plot.",
      description = "A plot may only be claimed by one user at a time.")
  @PostMapping("/{observationId}/plots/{plotId}/claim")
  fun claimMonitoringPlot(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
  ): SimpleSuccessResponsePayload {
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse409("You don't have a claim on the plot.")
  @ApiResponseSimpleSuccess
  @Operation(summary = "Releases the claim on a monitoring plot.")
  @PostMapping("/{observationId}/plots/{plotId}/release")
  fun releaseMonitoringPlot(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
  ): SimpleSuccessResponsePayload {
    return SimpleSuccessResponsePayload()
  }
}

data class CurrentObservationPayload(
    @Schema(description = "Date this observation is scheduled to end.") //
    val endDate: LocalDate,
    val id: ObservationId,
    @Schema(description = "Total number of monitoring plots that haven't been claimed yet.")
    val numUnclaimedPlots: Int,
    val plantingSiteId: PlantingSiteId,
    val plantingSiteName: String,
    @Schema(description = "Date this observation started.") //
    val startDate: LocalDate,
)

data class FutureObservationPayload(
    @Schema(description = "Date this observation is scheduled to end.") //
    val endDate: LocalDate,
    val plantingSiteId: PlantingSiteId,
    val plantingSiteName: String,
    @Schema(description = "Date this observation is scheduled to start.") //
    val startDate: LocalDate
)

data class ListObservationsResponsePayload(
    val current: List<CurrentObservationPayload>,
    val future: List<FutureObservationPayload>,
    @Schema(
        description =
            "Total number of monitoring plots that haven't been claimed yet across all current " +
                "observations.")
    val totalUnclaimedPlots: Int,
) : SuccessResponsePayload

data class ObservableMonitoringPlotPayload(
    val boundary: Geometry,
    val claimedByFirstName: String,
    val claimedByUserId: UserId,
    val completedByFirstName: String,
    val completedByUserId: UserId,
    val completedTime: Instant,
    @Schema(description = "True if this is the first observation to include the monitoring plot.")
    val isFirstObservation: Boolean,
    val isPermanent: Boolean,
    val observationId: ObservationId,
    val plantingSubzoneId: PlantingSubzoneId,
    val plotId: MonitoringPlotId,
)

enum class ObservedCondition {
  AnimalDamage,
  FastGrowth,
  FavorableWeather,
  Fungus,
  Pests,
  SeedProduction,
  UnfavorableWeather,
}

enum class RecordedSpeciesCertainty {
  CantTell,
  Known,
  Other,
}

enum class RecordedPlantStatus {
  Dead,
  Existing,
  Live,
}

enum class PlotPhotoPosition {
  NortheastCorner,
  NorthwestCorner,
  SoutheastCorner,
  SouthwestCorner,
}

data class RecordedPlantPayload(
    val certainty: RecordedSpeciesCertainty,
    @Schema(description = "GPS coordinates where plant was observed.") //
    val gpsCoordinates: Point,
    @Schema(
        description = "Required if certainty is Known. Ignored if certainty is CantTell or Other.")
    val speciesId: SpeciesId?,
    @Schema(
        description =
            "If certainty is Other, the optional user-supplied name of the species. Ignored if " +
                "certainty is CantTell or Known.")
    val speciesName: String?,
    val status: RecordedPlantStatus,
)

data class CompletePlotObservationRequestPayload(
    val conditions: Set<ObservedCondition>,
    val notes: String?,
    val plants: List<RecordedPlantPayload>,
    val plotId: MonitoringPlotId,
)

data class UploadPlotPhotoRequestPayload(
    val gpsCoordinates: Point,
    val position: PlotPhotoPosition,
)

data class ListObservablePlotsResponsePayload(val plots: List<ObservableMonitoringPlotPayload>) :
    SuccessResponsePayload

// TODO: Remove this once we generate a real ID type wrapper.
typealias ObservationId = Long
