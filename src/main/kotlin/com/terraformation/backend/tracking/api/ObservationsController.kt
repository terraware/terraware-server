package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200
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
import com.terraformation.backend.api.gpxResponse
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.RecordedPlantId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationMediaFilesRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.file.SUPPORTED_MEDIA_TYPES
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.api.GetMuxStreamResponsePayload
import com.terraformation.backend.file.model.FileMetadata
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.BiomassStore
import com.terraformation.backend.tracking.db.ObservationResultsStore
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.AssignedPlotDetails
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.NewObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.ObservationPlotCounts
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.RecordedPlantModel
import com.terraformation.backend.tracking.model.ReplacementDuration
import com.terraformation.backend.tracking.model.ReplacementResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.ws.rs.BadRequestException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
    private val biomassStore: BiomassStore,
    private val messages: Messages,
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
                  "Ignored if plantingSiteId is specified."
      )
      organizationId: OrganizationId? = null,
      @RequestParam
      @Schema(
          description =
              "Limit results to observations of a specific planting site. Required if " +
                  "organizationId is not specified."
      )
      plantingSiteId: PlantingSiteId? = null,
  ): ListObservationsResponsePayload {
    val observations: Collection<ExistingObservationModel>
    val plantingSites: Map<PlantingSiteId, ExistingPlantingSiteModel>
    val plotCounts: Map<ObservationId, ObservationPlotCounts>

    if (plantingSiteId != null) {
      observations = observationStore.fetchObservationsByPlantingSite(plantingSiteId)
      plantingSites =
          mapOf(
              plantingSiteId to
                  plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
          )
      plotCounts = observationStore.countPlots(plantingSiteId)
    } else if (organizationId != null) {
      observations = observationStore.fetchObservationsByOrganization(organizationId)
      plantingSites =
          plantingSiteStore.fetchSitesByOrganizationId(organizationId).associateBy { it.id }
      plotCounts = observationStore.countPlots(organizationId)
    } else {
      throw BadRequestException("Must specify organizationId or plantingSiteId")
    }

    val payloads =
        observations.map { observation ->
          ObservationPayload(
              observation,
              plotCounts[observation.id],
              plantingSites[observation.plantingSiteId]!!.name,
          )
        }

    return ListObservationsResponsePayload(
        observations = payloads,
        totalIncompletePlots = plotCounts.values.sumOf { it.totalIncomplete },
        totalUnclaimedPlots = plotCounts.values.sumOf { it.totalUnclaimed },
    )
  }

  @GetMapping("/results")
  @Operation(summary = "Gets a list of the results of observations.")
  fun listObservationResults(
      @RequestParam organizationId: OrganizationId?,
      @RequestParam plantingSiteId: PlantingSiteId?,
      @Parameter(description = "Whether to include plants in the results. Default to false")
      includePlants: Boolean? = null,
      @Parameter(
          description =
              "Maximum number of results to return. Results are always returned in order of " +
                  "completion time, newest first, so setting this to 1 will return the results " +
                  "of the most recently completed observation."
      )
      limit: Int? = null,
  ): ListObservationResultsResponsePayload {
    val depth =
        if (includePlants == true) {
          ObservationResultsDepth.Plant
        } else {
          ObservationResultsDepth.Plot
        }
    val results =
        when {
          plantingSiteId != null ->
              observationResultsStore.fetchByPlantingSiteId(plantingSiteId, depth, limit)
          organizationId != null ->
              observationResultsStore.fetchByOrganizationId(organizationId, depth, limit)
          else -> throw BadRequestException("Must specify a search criterion")
        }

    return ListObservationResultsResponsePayload(results.map { ObservationResultsPayload(it) })
  }

  @GetMapping("/results/summaries")
  @Operation(summary = "Gets the rollup observation summaries of a planting site")
  fun listObservationSummaries(
      @RequestParam plantingSiteId: PlantingSiteId,
      @Parameter(
          description =
              "Maximum number of results to return. Results are always returned in order of " +
                  "observations completion time, newest first, so setting this to 1 will return the " +
                  "summaries including the most recently completed observation."
      )
      limit: Int? = null,
  ): ListObservationSummariesResponsePayload {
    val results = observationResultsStore.fetchSummariesForPlantingSite(plantingSiteId, limit)
    return ListObservationSummariesResponsePayload(
        results.map { PlantingSiteObservationSummaryPayload(it) }
    )
  }

  @GetMapping("/{observationId}")
  @Operation(summary = "Gets information about a single observation.")
  fun getObservation(@PathVariable observationId: ObservationId): GetObservationResponsePayload {
    val observation = observationStore.fetchObservationById(observationId)
    val platingSite =
        plantingSiteStore.fetchSiteById(observation.plantingSiteId, PlantingSiteDepth.Site)
    val plotCounts = observationStore.countPlots(observationId)

    return GetObservationResponsePayload(
        ObservationPayload(observation, plotCounts, platingSite.name)
    )
  }

  @ApiResponse409("Observation is already completed or abandoned.")
  @ApiResponseSimpleSuccess
  @Operation(summary = "Abandon the observation.")
  @PostMapping("/{observationId}/abandon")
  fun abandonObservation(
      @PathVariable observationId: ObservationId,
  ): SimpleSuccessResponsePayload {
    observationStore.abandonObservation(observationId)

    return SimpleSuccessResponsePayload()
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

  @ApiResponse(
      responseCode = "200",
      content =
          [
              Content(
                  mediaType = "application/gpx+xml",
                  schema = Schema(type = "string", format = "binary"),
              )
          ],
  )
  @GetMapping("/{observationId}/plots", produces = ["application/gpx+xml"])
  @Operation(summary = "Exports monitoring plots assigned to an observation as a GPX file.")
  fun exportAssignedPlots(@PathVariable observationId: ObservationId): ResponseEntity<ByteArray> {
    val observation = observationStore.fetchObservationById(observationId)
    val observations = observationStore.fetchObservationPlotDetails(observationId)
    val plantingSite =
        plantingSiteStore.fetchSiteById(observation.plantingSiteId, PlantingSiteDepth.Site)
    val waypoints = observations.flatMap { it.gpxWaypoints(messages) }

    val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    val startDate = dateTimeFormatter.format(observation.startDate)
    val endDate = dateTimeFormatter.format(observation.endDate)
    val filename = "observation_${plantingSite.name}_$startDate-$endDate.gpx"

    return gpxResponse(filename, waypoints)
  }

  @GetMapping("/{observationId}/results")
  @Operation(
      summary = "Gets the results of an observation of a planting site.",
      description = "Some information is only available once all plots have been completed.",
  )
  fun getObservationResults(
      @PathVariable observationId: ObservationId,
      @Parameter(description = "Whether to include plants in the results. Default to false")
      includePlants: Boolean? = null,
  ): GetObservationResultsResponsePayload {
    val depth =
        if (includePlants == true) {
          ObservationResultsDepth.Plant
        } else {
          ObservationResultsDepth.Plot
        }
    val results = observationResultsStore.fetchOneById(observationId, depth)

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
        payload.plants.map { it.toRow() },
    )

    return SimpleSuccessResponsePayload()
  }

  @GetMapping("/{observationId}/plots/{plotId}")
  @Operation(summary = "Gets one assigned observation monitoring plot")
  fun getOneAssignedPlot(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
  ): GetOneAssignedPlotResponsePayload {
    val details = observationStore.fetchOneObservationPlotDetails(observationId, plotId)

    return GetOneAssignedPlotResponsePayload(AssignedPlotPayload(details))
  }

  @ApiResponse404(
      "The plot or observation can't be found, or the plot isn't part of the observation."
  )
  @ApiResponse409("The plot's observation has not been completed yet.")
  @ApiResponseSimpleSuccess
  @Operation(summary = "Updates information about a completed plot in an observation.")
  @PatchMapping("/{observationId}/plots/{plotId}")
  fun updateCompletedObservationPlot(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @RequestBody payload: UpdateObservationRequestPayload,
  ): SimpleSuccessResponsePayload {
    observationService.updateCompletedPlot(observationId, plotId) {
      payload.updates.forEach { element ->
        when (element) {
          is BiomassSpeciesUpdateOperationPayload ->
              biomassStore.updateBiomassSpecies(
                  observationId,
                  plotId,
                  element.speciesId,
                  element.scientificName,
                  element::applyTo,
              )
          is BiomassUpdateOperationPayload ->
              biomassStore.updateBiomassDetails(observationId, plotId, element::applyTo)
          is QuadratUpdateOperationPayload -> {
            biomassStore.updateBiomassQuadratDetails(
                observationId,
                plotId,
                element.position,
                element::applyTo,
            )
          }
        }
      }
    }

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Updates information about the observation of a plot.")
  @PutMapping("/{observationId}/plots/{plotId}")
  fun updatePlotObservation(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @RequestBody payload: UpdatePlotObservationRequestPayload,
  ): SimpleSuccessResponsePayload {
    val coordinateModels = payload.coordinates.map { it.toModel() }

    observationStore.updateObservedPlotCoordinates(observationId, plotId, coordinateModels)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Updates information about a recorded tree from a biomass observation.")
  @PutMapping("/{observationId}/trees/{treeId}")
  fun updateRecordedTree(
      @PathVariable observationId: ObservationId,
      @PathVariable treeId: RecordedTreeId,
      @RequestBody payload: UpdateRecordedTreeRequestPayload,
  ): SimpleSuccessResponsePayload {
    biomassStore.updateRecordedTree(observationId, treeId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404("The observation does not exist or does not have the requested monitoring plot.")
  @ApiResponse409(
      "The observation of the monitoring plot has already been completed and the plot cannot be " +
          "replaced."
  )
  @Operation(
      summary = "Requests that a monitoring plot be replaced with a new one.",
      description =
          "In some cases, the requested plot will be removed from the observation but not " +
              "replaced with a different one.",
  )
  @PostMapping("/{observationId}/plots/{plotId}/replace")
  fun replaceObservationPlot(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @RequestBody payload: ReplaceObservationPlotRequestPayload,
  ): ReplaceObservationPlotResponsePayload {
    val result =
        observationService.replaceMonitoringPlot(
            observationId,
            plotId,
            payload.justification,
            payload.duration,
        )

    return ReplaceObservationPlotResponsePayload(result)
  }

  @ApiResponse200Photo
  @ApiResponse404(
      "The plot observation does not exist, or does not have a photo with the requested ID."
  )
  @GetMapping(
      "/{observationId}/plots/{plotId}/photos/{fileId}",
      produces = [MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE],
  )
  @Operation(
      summary = "Retrieves a specific photo from an observation of a monitoring plot.",
      description = PHOTO_OPERATION_DESCRIPTION,
  )
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

  @ApiResponse200
  @ApiResponse404(
      "The plot observation does not exist, or does not have a video with the requested ID."
  )
  @GetMapping("/{observationId}/plots/{plotId}/media/{fileId}/stream")
  @Operation(summary = "Gets streaming details for a video for an observation.")
  fun getObservationMediaStream(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @PathVariable fileId: FileId,
  ): GetMuxStreamResponsePayload {
    val streamModel = observationService.getMuxStreamInfo(observationId, plotId, fileId)

    return GetMuxStreamResponsePayload(streamModel)
  }

  @ApiResponse404(
      "The plot observation does not exist, or does not have a photo with the requested ID."
  )
  @ApiResponseSimpleSuccess
  @PutMapping("/{observationId}/plots/{plotId}/photos/{fileId}")
  @Operation(
      summary =
          "Updates information about a specific photo from an observation of a monitoring plot."
  )
  fun updatePlotPhoto(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @PathVariable fileId: FileId,
      @RequestBody payload: UpdatePlotPhotoRequestPayload,
  ): SimpleSuccessResponsePayload {
    observationService.updateMediaFile(observationId, plotId, fileId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse404(
      "The plot observation does not exist, or does not have a photo with the requested ID."
  )
  @ApiResponseSimpleSuccess
  @DeleteMapping("/{observationId}/plots/{plotId}/photos/{fileId}")
  @Operation(
      summary = "Deletes a photo from an observation of a monitoring plot.",
      description = "Only photos that were not part of the original observation may be deleted.",
  )
  fun deletePlotPhoto(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @PathVariable fileId: FileId,
  ): SimpleSuccessResponsePayload {
    observationService.deleteMediaFile(observationId, plotId, fileId)

    return SimpleSuccessResponsePayload()
  }

  @Operation(
      summary = "Uploads a photo of a monitoring plot as part of the observation.",
      description =
          "Photos uploaded via this endpoint are considered to be part of the original " +
              "observation and cannot be deleted later.",
  )
  @PostMapping(
      "/{observationId}/plots/{plotId}/photos",
      consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
  )
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
        observationService.storeMediaFile(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = payload.position,
            data = file.inputStream,
            metadata = FileMetadata.of(contentType, filename, file.size, payload.gpsCoordinates),
            caption = payload.caption,
            isOriginal = true,
            type = payload.type ?: ObservationMediaType.Plot,
        )
    return UploadPlotPhotoResponsePayload(fileId)
  }

  @Operation(summary = "Adds a photo of a monitoring plot after an observation is complete.")
  @PostMapping(
      "/{observationId}/plots/{plotId}/otherMedia",
      consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
  )
  @RequestBodyPhotoFile
  fun uploadOtherPlotMedia(
      @PathVariable observationId: ObservationId,
      @PathVariable plotId: MonitoringPlotId,
      @RequestPart("file") file: MultipartFile,
      @RequestPart("payload") payload: UploadPlotMediaRequestPayload,
  ): UploadPlotPhotoResponsePayload {
    val contentType = file.getPlainContentType(SUPPORTED_MEDIA_TYPES)
    val filename = file.getFilename("photo")

    val fileId =
        observationService.storeMediaFile(
            observationId = observationId,
            monitoringPlotId = plotId,
            position = payload.position,
            data = file.inputStream,
            metadata = FileMetadata.of(contentType, filename, file.size),
            caption = payload.caption,
            isOriginal = false,
            type = payload.type ?: ObservationMediaType.Plot,
        )
    return UploadPlotPhotoResponsePayload(fileId)
  }

  @ApiResponse409("The plot is already claimed by someone else.")
  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Claims a monitoring plot.",
      description = "A plot may only be claimed by one user at a time.",
  )
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

  @GetMapping("/adHoc")
  @Operation(summary = "Gets a list of ad-hoc observations of planting sites.")
  fun listAdHocObservations(
      @RequestParam
      @Schema(
          description =
              "Limit results to observations of planting sites in a specific organization. " +
                  "Ignored if plantingSiteId is specified."
      )
      organizationId: OrganizationId? = null,
      @RequestParam
      @Schema(
          description =
              "Limit results to observations of a specific planting site. Required if " +
                  "organizationId is not specified."
      )
      plantingSiteId: PlantingSiteId? = null,
  ): ListAdHocObservationsResponsePayload {
    val observations: Collection<ExistingObservationModel>
    val plantingSites: Map<PlantingSiteId, ExistingPlantingSiteModel>
    val plotCounts: Map<ObservationId, ObservationPlotCounts>

    if (plantingSiteId != null) {
      observations =
          observationStore.fetchObservationsByPlantingSite(plantingSiteId, isAdHoc = true)
      plantingSites =
          mapOf(
              plantingSiteId to
                  plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
          )
      plotCounts = observationStore.countPlots(plantingSiteId, true)
    } else if (organizationId != null) {
      observations =
          observationStore.fetchObservationsByOrganization(organizationId, isAdHoc = true)
      plantingSites =
          plantingSiteStore.fetchSitesByOrganizationId(organizationId).associateBy { it.id }
      plotCounts = observationStore.countPlots(organizationId, true)
    } else {
      throw BadRequestException("Must specify organizationId or plantingSiteId")
    }

    val payloads =
        observations.map { observation ->
          ObservationPayload(
              observation,
              plotCounts[observation.id],
              plantingSites[observation.plantingSiteId]!!.name,
          )
        }

    return ListAdHocObservationsResponsePayload(
        observations = payloads,
    )
  }

  @Operation(summary = "Records a new completed ad-hoc observation.")
  @PostMapping("/adHoc")
  fun completeAdHocObservation(
      @RequestBody payload: CompleteAdHocObservationRequestPayload
  ): CompleteAdHocObservationResponsePayload {
    val (observationId, plotId) =
        observationService.completeAdHocObservation(
            payload.biomassMeasurements?.toModel(),
            payload.conditions,
            payload.notes,
            payload.observedTime,
            payload.observationType,
            payload.plantingSiteId,
            payload.plants?.map { it.toRow() } ?: emptyList(),
            payload.swCorner,
        )

    return CompleteAdHocObservationResponsePayload(observationId, plotId)
  }

  @GetMapping("/adHoc/results")
  @Operation(summary = "Gets a list of the results of ad-hoc observations.")
  fun listAdHocObservationResults(
      @RequestParam organizationId: OrganizationId?,
      @RequestParam plantingSiteId: PlantingSiteId?,
      @Parameter(description = "Whether to include plants in the results. Default to false")
      includePlants: Boolean? = null,
      @Parameter(
          description =
              "Maximum number of results to return. Results are always returned in order of " +
                  "completion time, newest first, so setting this to 1 will return the results " +
                  "of the most recently completed observation."
      )
      limit: Int? = null,
  ): ListAdHocObservationResultsResponsePayload {
    val depth =
        if (includePlants == true) {
          ObservationResultsDepth.Plant
        } else {
          ObservationResultsDepth.Plot
        }
    val results =
        when {
          plantingSiteId != null ->
              observationResultsStore.fetchByPlantingSiteId(
                  plantingSiteId,
                  depth,
                  limit,
                  isAdHoc = true,
              )
          organizationId != null ->
              observationResultsStore.fetchByOrganizationId(
                  organizationId,
                  depth,
                  limit,
                  isAdHoc = true,
              )
          else -> throw BadRequestException("Must specify a search criterion")
        }

    return ListAdHocObservationResultsResponsePayload(results.map { ObservationResultsPayload(it) })
  }

  @Operation(summary = "Schedules a new observation.")
  @PostMapping
  fun scheduleObservation(
      @RequestBody @Valid payload: ScheduleObservationRequestPayload
  ): ScheduleObservationResponsePayload {
    val id = observationService.scheduleObservation(payload.toModel())

    return ScheduleObservationResponsePayload(id)
  }

  @Operation(summary = "Reschedules an existing observation.")
  @PutMapping("/{observationId}")
  fun rescheduleObservation(
      @PathVariable observationId: ObservationId,
      @RequestBody payload: RescheduleObservationRequestPayload,
  ): SimpleSuccessResponsePayload {
    observationService.rescheduleObservation(observationId, payload.startDate, payload.endDate)

    return SimpleSuccessResponsePayload()
  }

  @Operation(
      summary =
          "Replaces a user-entered 'Other' species with one of the organization's species in " +
              "an observation."
  )
  @PostMapping("/{observationId}/mergeOtherSpecies")
  fun mergeOtherSpecies(
      @PathVariable observationId: ObservationId,
      @RequestBody payload: MergeOtherSpeciesRequestPayload,
  ): SimpleSuccessResponsePayload {
    observationService.mergeOtherSpecies(observationId, payload.otherSpeciesName, payload.speciesId)

    return SimpleSuccessResponsePayload()
  }
}

data class ObservationPayload(
    @Schema(description = "Date this observation is scheduled to end.") //
    val endDate: LocalDate,
    val id: ObservationId,
    val isAdHoc: Boolean,
    @Schema(
        description =
            "Total number of monitoring plots that haven't been completed yet. Includes both " +
                "claimed and unclaimed plots."
    )
    val numIncompletePlots: Int,
    @Schema(description = "Total number of monitoring plots in observation, regardless of status.")
    val numPlots: Int,
    @Schema(description = "Total number of monitoring plots that haven't been claimed yet.")
    val numUnclaimedPlots: Int,
    @Schema(
        description =
            "If this observation has already started, the version of the planting site that was " +
                "used to place its monitoring plots."
    )
    val plantingSiteHistoryId: PlantingSiteHistoryId?,
    val plantingSiteId: PlantingSiteId,
    val plantingSiteName: String,
    @ArraySchema(
        arraySchema =
            Schema(
                description = "If specific subzones were requested for this observation, their IDs."
            )
    )
    val requestedSubzoneIds: Set<PlantingSubzoneId>?,
    @Schema(description = "Date this observation started.") //
    val startDate: LocalDate,
    val state: ObservationState,
    val type: ObservationType,
) {
  constructor(
      model: ExistingObservationModel,
      counts: ObservationPlotCounts?,
      plantingSiteName: String,
  ) : this(
      endDate = model.endDate,
      id = model.id,
      isAdHoc = model.isAdHoc,
      numIncompletePlots = counts?.totalIncomplete ?: 0,
      numPlots = counts?.totalPlots ?: 0,
      numUnclaimedPlots = counts?.totalUnclaimed ?: 0,
      plantingSiteHistoryId = model.plantingSiteHistoryId,
      plantingSiteId = model.plantingSiteId,
      plantingSiteName = plantingSiteName,
      requestedSubzoneIds = model.requestedSubzoneIds.ifEmpty { null },
      startDate = model.startDate,
      state = model.state,
      type = model.observationType,
  )
}

data class GetObservationResponsePayload(val observation: ObservationPayload) :
    SuccessResponsePayload

data class ListObservationsResponsePayload(
    val observations: List<ObservationPayload>,
    @Schema(
        description =
            "Total number of monitoring plots that haven't been completed yet across all current " +
                "observations."
    )
    val totalIncompletePlots: Int,
    @Schema(
        description =
            "Total number of monitoring plots that haven't been claimed yet across all current " +
                "observations."
    )
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
    val elevationMeters: BigDecimal?,
    @Schema(description = "True if this is the first observation to include the monitoring plot.")
    val isFirstObservation: Boolean,
    val isPermanent: Boolean,
    val observationId: ObservationId,
    val plantingSubzoneId: PlantingSubzoneId,
    val plantingSubzoneName: String,
    val plantingZoneName: String,
    val plotId: MonitoringPlotId,
    val plotName: String,
    val plotNumber: Long,
    @Schema(description = "Length of each edge of the monitoring plot in meters.")
    val sizeMeters: Int,
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
      elevationMeters = details.elevationMeters,
      isFirstObservation = details.isFirstObservation,
      isPermanent = details.model.isPermanent,
      observationId = details.model.observationId,
      plantingSubzoneId = details.plantingSubzoneId,
      plantingSubzoneName = details.plantingSubzoneName,
      plantingZoneName = details.plantingZoneName,
      plotId = details.model.monitoringPlotId,
      plotName = "${details.plotNumber}",
      plotNumber = details.plotNumber,
      sizeMeters = details.sizeMeters,
  )
}

data class RecordedPlantPayload(
    val certainty: RecordedSpeciesCertainty,
    @Schema(description = "GPS coordinates where plant was observed.") //
    val gpsCoordinates: Point,
    val id: RecordedPlantId?,
    @Schema(
        description = "Required if certainty is Known. Ignored if certainty is Other or Unknown."
    )
    val speciesId: SpeciesId?,
    @Schema(
        description =
            "If certainty is Other, the optional user-supplied name of the species. Ignored if " +
                "certainty is Known or Unknown."
    )
    val speciesName: String?,
    val status: RecordedPlantStatus,
) {
  constructor(
      model: RecordedPlantModel
  ) : this(
      certainty = model.certainty,
      gpsCoordinates = model.gpsCoordinates,
      id = model.id,
      speciesId = model.speciesId,
      speciesName = model.speciesName,
      status = model.status,
  )

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

data class ObservationMonitoringPlotCoordinatesPayload(
    val gpsCoordinates: Point,
    val position: ObservationPlotPosition,
) {
  constructor(model: ObservedPlotCoordinatesModel) : this(model.gpsCoordinates, model.position)

  fun toModel() = NewObservedPlotCoordinatesModel(gpsCoordinates, position)
}

data class CompleteAdHocObservationRequestPayload(
    @Schema(description = "Biomass Measurements. Required for biomass measurement observations")
    val biomassMeasurements: NewBiomassMeasurementPayload?,
    val conditions: Set<ObservableCondition>,
    val notes: String?,
    @Schema(description = "Date and time the observation was performed in the field.")
    val observedTime: Instant,
    @Schema(description = "Observation type for this observation.")
    val observationType: ObservationType,
    @Schema(description = "Recorded plants. Required for monitoring observations.")
    val plants: List<RecordedPlantPayload>?,
    @Schema(description = "Which planting site this observation needs to be scheduled for.")
    val plantingSiteId: PlantingSiteId,
    @Schema(description = "GPS coordinates for the South West corner of the ad-hoc plot.")
    val swCorner: Point,
)

data class CompleteAdHocObservationResponsePayload(
    val observationId: ObservationId,
    val plotId: MonitoringPlotId,
) : SuccessResponsePayload

data class CompletePlotObservationRequestPayload(
    val conditions: Set<ObservableCondition>,
    val notes: String?,
    @Schema(description = "Date and time the observation was performed in the field.")
    val observedTime: Instant,
    val plants: List<RecordedPlantPayload>,
)

data class GetObservationResultsResponsePayload(val observation: ObservationResultsPayload) :
    SuccessResponsePayload

data class GetOneAssignedPlotResponsePayload(
    val plot: AssignedPlotPayload,
) : SuccessResponsePayload

data class ListAdHocObservationsResponsePayload(
    val observations: List<ObservationPayload>,
) : SuccessResponsePayload

data class ListAdHocObservationResultsResponsePayload(
    val observations: List<ObservationResultsPayload>
) : SuccessResponsePayload

data class ListAssignedPlotsResponsePayload(
    val plots: List<AssignedPlotPayload>,
) : SuccessResponsePayload

data class ListObservationResultsResponsePayload(
    val observations: List<ObservationResultsPayload>
) : SuccessResponsePayload

data class ListObservationSummariesResponsePayload(
    @Schema(
        description =
            "History of rollup summaries of planting site observations in order of observation " +
                "time, latest first. "
    )
    val summaries: List<PlantingSiteObservationSummaryPayload>,
) : SuccessResponsePayload

data class MergeOtherSpeciesRequestPayload(
    @Schema(
        description =
            "Name of the species of certainty Other whose recorded plants should be updated to " +
                "refer to the known species."
    )
    val otherSpeciesName: String,
    @Schema(
        description =
            "ID of the existing species that the Other species' recorded plants should be merged " +
                "into."
    )
    val speciesId: SpeciesId,
)

data class ReplaceObservationPlotRequestPayload(
    val duration: ReplacementDuration,
    val justification: String,
)

data class ReplaceObservationPlotResponsePayload(
    @Schema(
        description =
            "IDs of monitoring plots that were added to the observation. Empty if no plots were " +
                "added."
    )
    val addedMonitoringPlotIds: Set<MonitoringPlotId>,
    @Schema(
        description =
            "IDs of monitoring plots that were removed from the observation. Will usually " +
                "include the requested plot ID, but may be empty if the replacement request " +
                "couldn't be satisfied."
    )
    val removedMonitoringPlotIds: Set<MonitoringPlotId>,
) : SuccessResponsePayload {
  constructor(
      result: ReplacementResult
  ) : this(
      addedMonitoringPlotIds = result.addedMonitoringPlotIds,
      removedMonitoringPlotIds = result.removedMonitoringPlotIds,
  )
}

data class RescheduleObservationRequestPayload(
    @Schema(
        description =
            "The end date for this observation, should be limited to 2 months from the start date ."
    )
    val endDate: LocalDate,
    @Schema(
        description =
            "The start date for this observation, can be up to a year from the date this schedule request occurs on."
    )
    val startDate: LocalDate,
)

data class ScheduleObservationRequestPayload(
    @Schema(
        description =
            "The end date for this observation, should be limited to 2 months from the start date."
    )
    val endDate: LocalDate,
    @Schema(description = "Which planting site this observation needs to be scheduled for.")
    val plantingSiteId: PlantingSiteId,
    @field:NotEmpty
    @ArraySchema(
        arraySchema =
            Schema(description = "The IDs of the subzones this observation should cover."),
        minItems = 1,
    )
    val requestedSubzoneIds: Set<PlantingSubzoneId>,
    @Schema(
        description =
            "The start date for this observation, can be up to a year from the date this " +
                "schedule request occurs on."
    )
    val startDate: LocalDate,
) {
  fun toModel() =
      NewObservationModel(
          endDate = endDate,
          id = null,
          isAdHoc = false,
          observationType = ObservationType.Monitoring,
          plantingSiteId = plantingSiteId,
          requestedSubzoneIds = requestedSubzoneIds,
          startDate = startDate,
          state = ObservationState.Upcoming,
      )
}

data class ScheduleObservationResponsePayload(val id: ObservationId) : SuccessResponsePayload

data class UpdatePlotObservationRequestPayload(
    @ArraySchema(
        arraySchema = Schema(description = "Observed coordinates, if any, up to one per position.")
    )
    val coordinates: List<ObservationMonitoringPlotCoordinatesPayload>,
)

data class UpdateObservationRequestPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of changes to make to different parts of the observation. " +
                        "Changes are all-or-nothing; if any of them fails, none of them is applied."
            )
    )
    val updates: List<ObservationUpdateOperationPayload>,
)

data class UpdatePlotPhotoRequestPayload(
    val caption: String?,
) {
  fun applyTo(row: ObservationMediaFilesRow) = row.copy(caption = caption)
}

data class UpdateRecordedTreeRequestPayload(
    val description: String?,
) {
  fun applyTo(model: ExistingRecordedTreeModel): ExistingRecordedTreeModel {
    return model.copy(description = description)
  }
}

data class UploadPlotMediaRequestPayload(
    val caption: String?,
    val position: ObservationPlotPosition?,
    @Schema(description = "Type of subject the uploaded file depicts.", defaultValue = "Plot")
    val type: ObservationMediaType?,
)

data class UploadPlotPhotoRequestPayload(
    val caption: String?,
    val gpsCoordinates: Point,
    val position: ObservationPlotPosition?,
    @Schema(
        description = "Type of observation plot photo.",
        defaultValue = "Plot",
    )
    val type: ObservationMediaType?,
)

data class UploadPlotPhotoResponsePayload(val fileId: FileId) : SuccessResponsePayload
