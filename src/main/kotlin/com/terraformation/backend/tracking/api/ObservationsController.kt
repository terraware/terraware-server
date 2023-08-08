package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200Photo
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.PHOTO_MAXHEIGHT_DESCRIPTION
import com.terraformation.backend.api.PHOTO_MAXWIDTH_DESCRIPTION
import com.terraformation.backend.api.PHOTO_OPERATION_DESCRIPTION
import com.terraformation.backend.api.RequestBodyPhotoFile
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPhotoPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.ObservationResultsStore
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotPhotoModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotStatus
import com.terraformation.backend.tracking.model.ObservationPlantingSubzoneResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingZoneResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.BadRequestException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/tracking/observations")
@RestController
@TrackingEndpoint
class ObservationsController(
    private val observationService: ObservationService,
    private val observationStore: ObservationStore,
    private val observationResultsStore: ObservationResultsStore,
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

  @GetMapping("/results")
  @Operation(summary = "Gets a list of the results of observations.")
  fun listObservationResults(
      @RequestParam organizationId: OrganizationId?,
      @RequestParam plantingSiteId: PlantingSiteId?,
      @Parameter(
          description =
              "Maximum number of results to return. Results are always returned in order of " +
                  "completion time, newest first, so setting this to 1 will return the results " +
                  "of the most recently completed observation.")
      limit: Int? = null,
  ): ListObservationResultsResponsePayload {
    val results =
        when {
          plantingSiteId != null ->
              observationResultsStore.fetchByPlantingSiteId(plantingSiteId, limit)
          organizationId != null ->
              observationResultsStore.fetchByOrganizationId(organizationId, limit)
          else -> throw BadRequestException("Must specify a search criterion")
        }

    return ListObservationResultsResponsePayload(results.map { ObservationResultsPayload(it) })
  }

  @GetMapping("/{observationId}")
  @Operation(summary = "Gets information about a single observation.")
  fun getObservation(@PathVariable observationId: ObservationId): GetObservationResponsePayload {
    val observation = observationStore.fetchObservationById(observationId)
    val platingSite =
        plantingSiteStore.fetchSiteById(observation.plantingSiteId, PlantingSiteDepth.Site)
    val unclaimedCount =
        observationStore
            .countUnclaimedPlots(observation.plantingSiteId)
            .getOrDefault(observationId, 0)

    return GetObservationResponsePayload(
        ObservationPayload(observation, unclaimedCount, platingSite.name))
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

  @GetMapping("/{observationId}/results")
  @Operation(
      summary = "Gets the results of an observation of a planting site.",
      description = "Some information is only available once all plots have been completed.")
  fun getObservationResults(
      @PathVariable observationId: ObservationId
  ): GetObservationResultsResponsePayload {
    val results = observationResultsStore.fetchOneById(observationId)

    return GetObservationResultsResponsePayload(ObservationResultsPayload(results))
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

  @ApiResponse200Photo
  @ApiResponse404(
      "The plot observation does not exist, or does not have a photo with the requested ID.")
  @GetMapping(
      "/{observationId}/plots/{plotId}/photos/{fileId}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE])
  @Operation(
      summary = "Retrieves a specific photo from an observation of a monitoring plot.",
      description = PHOTO_OPERATION_DESCRIPTION)
  @ResponseBody
  fun getPlotPhoto(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @PathVariable fileId: FileId,
      @Parameter(description = PHOTO_MAXWIDTH_DESCRIPTION) @RequestParam maxWidth: Int? = null,
      @Parameter(description = PHOTO_MAXHEIGHT_DESCRIPTION) @RequestParam maxHeight: Int? = null,
  ): ResponseEntity<InputStreamResource> {
    return observationService
        .readPhoto(observationId, plotId, fileId, maxWidth, maxHeight)
        .toResponseEntity()
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
  ): UploadPlotPhotoResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)
    val filename = file.getFilename("photo")

    val fileId =
        observationService.storePhoto(
            data = file.inputStream,
            gpsCoordinates = payload.gpsCoordinates,
            metadata = FileMetadata.of(contentType, filename, file.size),
            monitoringPlotId = plotId,
            observationId = observationId,
            position = payload.position,
        )

    return UploadPlotPhotoResponsePayload(fileId)
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

data class GetObservationResponsePayload(val observation: ObservationPayload) :
    SuccessResponsePayload

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
        description = "Required if certainty is Known. Ignored if certainty is Other or Unknown.")
    val speciesId: SpeciesId?,
    @Schema(
        description =
            "If certainty is Other, the optional user-supplied name of the species. Ignored if " +
                "certainty is Known or Unknown.")
    val speciesName: String?,
    val status: RecordedPlantStatus,
) {
  fun toRow(): RecordedPlantsRow {
    return RecordedPlantsRow(
        certaintyId = certainty,
        gpsCoordinates = gpsCoordinates,
        speciesId = if (certainty == RecordedSpeciesCertainty.Known) speciesId else null,
        speciesName = if (certainty == RecordedSpeciesCertainty.Other) speciesName else null,
        statusId = status,
    )
  }
}

data class ObservationMonitoringPlotPhotoPayload(
    val fileId: FileId,
) {
  constructor(model: ObservationMonitoringPlotPhotoModel) : this(model.fileId)
}

data class ObservationSpeciesResultsPayload(
    val certainty: RecordedSpeciesCertainty,
    @Schema(
        description =
            "Number of dead plants observed in permanent monitoring plots in all observations " +
                "including this one. 0 if this is a plot-level result for a temporary monitoring " +
                "plot.")
    val cumulativeDead: Int,
    @Schema(
        description =
            "Percentage of plants in permanent monitoring plots that are dead. If there are no " +
                "permanent monitoring plots (or if this is a plot-level result for a temporary " +
                "monitoring plot) this will be null.")
    val mortalityRate: Int?,
    @Schema(
        description =
            "Number of live plants observed in permanent plots in this observation, not " +
                "including existing plants. 0 if ths is a plot-level result for a temporary " +
                "monitoring plot.")
    val permanentLive: Int,
    @Schema(
        description =
            "If certainty is Known, the ID of the species. Null if certainty is Other or Unknown.")
    val speciesId: SpeciesId?,
    @Schema(
        description =
            "If certainty is Other, the user-supplied name of the species. Null if certainty is " +
                "Known or Unknown.")
    val speciesName: String?,
    @Schema(description = "Total number of live and existing plants of this species.")
    val totalPlants: Int,
) {
  constructor(
      model: ObservationSpeciesResultsModel
  ) : this(
      certainty = model.certainty,
      cumulativeDead = model.cumulativeDead,
      mortalityRate = model.mortalityRate,
      permanentLive = model.permanentLive,
      speciesId = model.speciesId,
      speciesName = model.speciesName,
      totalPlants = model.totalPlants,
  )
}

data class ObservationMonitoringPlotResultsPayload(
    val boundary: Polygon,
    val claimedByName: String?,
    val claimedByUserId: UserId?,
    val completedTime: Instant?,
    @Schema(
        description =
            "True if this was a permanent monitoring plot in this observation. Clients should " +
                "not assume that the set of permanent monitoring plots is the same in all " +
                "observations; the number of permanent monitoring plots can be adjusted over " +
                "time based on observation results.")
    val isPermanent: Boolean,
    val monitoringPlotId: MonitoringPlotId,
    @Schema(description = "Full name of this monitoring plot, including zone and subzone prefixes.")
    val monitoringPlotName: String,
    @Schema(
        description =
            "If this is a permanent monitoring plot in this observation, percentage of plants of " +
                "all species that were dead.")
    val mortalityRate: Int?,
    val notes: String?,
    val photos: List<ObservationMonitoringPlotPhotoPayload>,
    @Schema(description = "Number of live plants per hectare.") //
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
) {
  constructor(
      model: ObservationMonitoringPlotResultsModel
  ) : this(
      boundary = model.boundary,
      claimedByName = model.claimedByName,
      claimedByUserId = model.claimedByUserId,
      completedTime = model.completedTime,
      isPermanent = model.isPermanent,
      monitoringPlotId = model.monitoringPlotId,
      monitoringPlotName = model.monitoringPlotName,
      mortalityRate = model.mortalityRate,
      notes = model.notes,
      photos = model.photos.map { ObservationMonitoringPlotPhotoPayload(it) },
      plantingDensity = model.plantingDensity,
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      status = model.status,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

data class ObservationPlantingSubzoneResultsPayload(
    val monitoringPlots: List<ObservationMonitoringPlotResultsPayload>,
    val plantingSubzoneId: PlantingSubzoneId,
) {
  constructor(
      model: ObservationPlantingSubzoneResultsModel
  ) : this(
      monitoringPlots = model.monitoringPlots.map { ObservationMonitoringPlotResultsPayload(it) },
      plantingSubzoneId = model.plantingSubzoneId,
  )
}

data class ObservationPlantingZoneResultsPayload(
    @Schema(description = "Area of this planting zone in hectares.") //
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    @Schema(
        description =
            "Estimated number of plants in planting zone based on estimated planting density and " +
                "planting zone area. Only present if all the subzones in the zone have been " +
                "marked as having completed planting.")
    val estimatedPlants: Int?,
    @Schema(
        description =
            "Percentage of plants of all species that were dead in this zone's permanent " +
                "monitoring plots.")
    val mortalityRate: Int,
    @Schema(
        description =
            "Estimated planting density for the zone based on the observed planting densities " +
                "of monitoring plots. Only present if all the subzones in the zone have been " +
                "marked as having completed planting.")
    val plantingDensity: Int?,
    val plantingSubzones: List<ObservationPlantingSubzoneResultsPayload>,
    val plantingZoneId: PlantingZoneId,
    val species: List<ObservationSpeciesResultsPayload>,
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
) {
  constructor(
      model: ObservationPlantingZoneResultsModel
  ) : this(
      areaHa = model.areaHa,
      completedTime = model.completedTime,
      estimatedPlants = model.estimatedPlants,
      mortalityRate = model.mortalityRate,
      plantingDensity = model.plantingDensity,
      plantingSubzones =
          model.plantingSubzones.map { ObservationPlantingSubzoneResultsPayload(it) },
      plantingZoneId = model.plantingZoneId,
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

data class ObservationResultsPayload(
    val completedTime: Instant?,
    @Schema(
        description =
            "Estimated total number of live plants at the site, based on the estimated planting " +
                "density and site size. Only present if all the subzones in the site have been " +
                "marked as having completed planting.")
    val estimatedPlants: Int?,
    @Schema(
        description =
            "Percentage of plants of all species that were dead in this site's permanent " +
                "monitoring plots.")
    val mortalityRate: Int,
    val observationId: ObservationId,
    @Schema(
        description =
            "Estimated planting density for the site, based on the observed planting densities " +
                "of monitoring plots. Only present if all the subzones in the site have been " +
                "marked as having completed planting.")
    val plantingDensity: Int?,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<ObservationPlantingZoneResultsPayload>,
    val species: List<ObservationSpeciesResultsPayload>,
    val startDate: LocalDate,
    val state: ObservationState,
    val totalSpecies: Int,
) {
  constructor(
      model: ObservationResultsModel
  ) : this(
      completedTime = model.completedTime,
      estimatedPlants = model.estimatedPlants,
      mortalityRate = model.mortalityRate,
      observationId = model.observationId,
      plantingDensity = model.plantingDensity,
      plantingSiteId = model.plantingSiteId,
      plantingZones = model.plantingZones.map { ObservationPlantingZoneResultsPayload(it) },
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      startDate = model.startDate,
      state = model.state,
      totalSpecies = model.totalSpecies,
  )
}

data class CompletePlotObservationRequestPayload(
    val conditions: Set<ObservableCondition>,
    val notes: String?,
    @Schema(description = "Date and time the observation was performed in the field.")
    val observedTime: Instant,
    val plants: List<RecordedPlantPayload>,
)

data class ListAssignedPlotsResponsePayload(
    val plots: List<AssignedPlotPayload>,
) : SuccessResponsePayload

data class UploadPlotPhotoRequestPayload(
    val gpsCoordinates: Point,
    val position: ObservationPhotoPosition,
)

data class UploadPlotPhotoResponsePayload(val fileId: FileId) : SuccessResponsePayload

data class GetObservationResultsResponsePayload(val observation: ObservationResultsPayload) :
    SuccessResponsePayload

data class ListObservationResultsResponsePayload(
    val observations: List<ObservationResultsPayload>
) : SuccessResponsePayload
