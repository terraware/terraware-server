package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import javax.ws.rs.BadRequestException
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/observations")
@RestController
@TrackingEndpoint
class ObservationsController(
    private val observationStore: ObservationStore,
    private val plantingSiteStore: PlantingSiteStore,
) {
  @GetMapping
  @Operation(summary = "Gets a list of observations of planting sites.")
  fun listObservations(
      @RequestParam
      @Schema(
          description =
              "Limit results to observations of planting sites in a specific organization. " +
                  "Ignored if plantingSiteId is specified.")
      organizationId: OrganizationId? = null,
      @RequestParam
      @Schema(
          description =
              "Limit results to observations of a specific planting site. Required if " +
                  "organizationId is not specified.")
      plantingSiteId: PlantingSiteId? = null,
  ): ListObservationsResponsePayload {
    val observations: Collection<ExistingObservationModel>
    val plantingSites: Map<PlantingSiteId, PlantingSiteModel>
    val unclaimedCounts: Map<ObservationId, Int>

    if (plantingSiteId != null) {
      observations = observationStore.fetchObservationsByPlantingSite(plantingSiteId)
      plantingSites =
          mapOf(
              plantingSiteId to
                  plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site))
      unclaimedCounts = observationStore.countUnclaimedPlots(plantingSiteId)
    } else if (organizationId != null) {
      observations = observationStore.fetchObservationsByOrganization(organizationId)
      plantingSites =
          plantingSiteStore.fetchSitesByOrganizationId(organizationId).associateBy { it.id }
      unclaimedCounts = observationStore.countUnclaimedPlots(organizationId)
    } else {
      throw BadRequestException("Must specify organizationId or plantingSiteId")
    }

    val payloads =
        observations.map { observation ->
          ObservationPayload(
              observation,
              unclaimedCounts[observation.id] ?: 0,
              plantingSites[observation.plantingSiteId]!!.name)
        }

    return ListObservationsResponsePayload(payloads, unclaimedCounts.values.sum())
  }

  @GetMapping("/{observationId}/plots")
  @Operation(summary = "Gets a list of monitoring plots assigned to an observation.")
  fun listAssignedPlots(
      @PathVariable observationId: ObservationId
  ): ListAssignedPlotsResponsePayload {
    val payloads =
        observationStore.fetchObservationPlotDetails(observationId).map { AssignedPlotPayload(it) }

    return ListAssignedPlotsResponsePayload(payloads)
  }

  @ApiResponseSimpleSuccess
  @ApiResponse409("The observation of the plot was already completed.")
  @Operation(summary = "Stores the results of a completed observation of a plot.")
  @PostMapping("/{observationId}/plots/{plotId}")
  fun completePlotObservation(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @RequestBody payload: CompletePlotObservationRequestPayload,
  ): SimpleSuccessResponsePayload {
    observationStore.completePlot(
        observationId,
        plotId,
        payload.conditions,
        payload.notes,
        payload.observedTime,
        payload.plants.map { it.toRow() })

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
    observationStore.claimPlot(observationId, plotId)

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
    observationStore.releasePlot(observationId, plotId)

    return SimpleSuccessResponsePayload()
  }
}

data class ObservationPayload(
    @Schema(description = "Date this observation is scheduled to end.") //
    val endDate: LocalDate,
    val id: ObservationId,
    @Schema(description = "Total number of monitoring plots that haven't been claimed yet.")
    val numUnclaimedPlots: Int,
    val plantingSiteId: PlantingSiteId,
    val plantingSiteName: String,
    @Schema(description = "Date this observation started.") //
    val startDate: LocalDate,
    val state: ObservationState,
) {
  constructor(
      model: ExistingObservationModel,
      numUnclaimedPlots: Int,
      plantingSiteName: String,
  ) : this(
      endDate = model.endDate,
      id = model.id,
      numUnclaimedPlots = numUnclaimedPlots,
      plantingSiteId = model.plantingSiteId,
      plantingSiteName = plantingSiteName,
      startDate = model.startDate,
      state = model.state,
  )
}

data class ListObservationsResponsePayload(
    val observations: List<ObservationPayload>,
    @Schema(
        description =
            "Total number of monitoring plots that haven't been claimed yet across all current " +
                "observations.")
    val totalUnclaimedPlots: Int,
) : SuccessResponsePayload

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AssignedPlotPayload(
    val boundary: Geometry,
    val claimedByName: String?,
    val claimedByUserId: UserId?,
    val completedByName: String?,
    val completedByUserId: UserId?,
    val completedTime: Instant?,
    @Schema(description = "True if this is the first observation to include the monitoring plot.")
    val isFirstObservation: Boolean,
    val isPermanent: Boolean,
    val observationId: ObservationId,
    val plantingSubzoneId: PlantingSubzoneId,
    val plantingSubzoneName: String,
    val plotId: MonitoringPlotId,
    val plotName: String,
) {
  constructor(
      details: AssignedPlotDetails
  ) : this(
      boundary = details.boundary,
      claimedByName = details.claimedByName,
      claimedByUserId = details.model.claimedBy,
      completedByName = details.completedByName,
      completedByUserId = details.model.completedBy,
      completedTime = details.model.completedTime,
      isFirstObservation = details.isFirstObservation,
      isPermanent = details.model.isPermanent,
      observationId = details.model.observationId,
      plantingSubzoneId = details.plantingSubzoneId,
      plantingSubzoneName = details.plantingSubzoneName,
      plotId = details.model.monitoringPlotId,
      plotName = details.plotName,
  )
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
) {
  fun toRow(): RecordedPlantsRow {
    return RecordedPlantsRow(
        certaintyId = certainty,
        gpsCoordinates = gpsCoordinates,
        speciesId = speciesId,
        speciesName = speciesName,
        statusId = status,
    )
  }
}

data class CompletePlotObservationRequestPayload(
    val conditions: Set<ObservableCondition>,
    val notes: String?,
    @Schema(description = "Date and time the observation was performed in the field.")
    val observedTime: Instant,
    val plants: List<RecordedPlantPayload>,
    val plotId: MonitoringPlotId,
)

data class ListAssignedPlotsResponsePayload(
    val plots: List<AssignedPlotPayload>,
) : SuccessResponsePayload
