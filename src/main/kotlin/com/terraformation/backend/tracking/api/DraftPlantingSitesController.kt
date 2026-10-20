package com.terraformation.backend.tracking.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse413
import com.terraformation.backend.api.ArbitraryJsonObject
import com.terraformation.backend.api.ResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessOrError
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.gis.GeometryFileErrorCode
import com.terraformation.backend.gis.GeometryFileException
import com.terraformation.backend.gis.GeometryFileFormat
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.DraftPlantingSiteService
import com.terraformation.backend.tracking.db.DraftPlantingSiteStore
import com.terraformation.backend.tracking.model.BoundaryFileModel
import com.terraformation.backend.tracking.model.DraftPlantingSiteMap
import com.terraformation.backend.tracking.model.DraftPlantingSiteMapConverter
import com.terraformation.backend.tracking.model.DraftPlantingSiteModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import org.locationtech.jts.geom.Geometry
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.MultipartFile

@RequestMapping("/api/v1/tracking/draftSites")
@RestController
@TrackingEndpoint
class DraftPlantingSitesController(
    private val draftPlantingSiteMapConverter: DraftPlantingSiteMapConverter,
    private val draftPlantingSiteService: DraftPlantingSiteService,
    private val draftPlantingSiteStore: DraftPlantingSiteStore,
) {
  private val log = perClassLogger()

  companion object {
    const val MAX_BOUNDARY_FILE_SIZE_MB = 10L
  }

  @GetMapping("/{id}")
  @Operation(summary = "Gets the details of a saved draft of a planting site.")
  fun getDraftPlantingSite(
      @PathVariable id: DraftPlantingSiteId
  ): GetDraftPlantingSiteResponsePayload {
    val model = draftPlantingSiteStore.fetchOneById(id)

    return GetDraftPlantingSiteResponsePayload(
        DraftPlantingSitePayload(model, draftPlantingSiteMapConverter.generate(model.map))
    )
  }

  @Operation(summary = "Saves a draft of an in-progress planting site.")
  @PostMapping
  fun createDraftPlantingSite(
      @RequestBody payload: CreateDraftPlantingSiteRequestPayload
  ): CreateDraftPlantingSiteResponsePayload {
    val model =
        draftPlantingSiteStore.create(
            payload.toModel(draftPlantingSiteMapConverter.parse(payload.data))
        )

    return CreateDraftPlantingSiteResponsePayload(model.id!!)
  }

  @Operation(summary = "Updates an existing draft of an in-progress planting site.")
  @PutMapping("/{id}")
  fun updateDraftPlantingSite(
      @PathVariable id: DraftPlantingSiteId,
      @RequestBody payload: UpdateDraftPlantingSiteRequestPayload,
  ): SimpleSuccessResponsePayload {
    val map = draftPlantingSiteMapConverter.parse(payload.data)

    draftPlantingSiteStore.update(id) { payload.applyTo(it, map) }

    return SimpleSuccessResponsePayload()
  }

  @Operation(
      summary = "Parses a boundary file for a draft planting site.",
      description =
          "Accepts KML (.kml), KMZ (.kmz), GeoJSON (.geojson or .json), or a ZIP containing " +
              "one shapefile with matching .shp, .shx, .dbf, and .prj files. " +
              "Returns the parsed geometry, original filename, detected format, number of " +
              "separate polygons, and area in hectares on success. Content validation failures " +
              "also return HTTP 200, with status error and a problems list instead of geometry.",
  )
  @ApiResponse200(
      description =
          "The file was processed. Check status for a parsed boundary or content validation problems."
  )
  @ApiResponse413(description = "The file exceeds the $MAX_BOUNDARY_FILE_SIZE_MB MB limit.")
  @PostMapping("/boundaryFile", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  fun parseDraftPlantingSiteBoundary(
      @RequestPart("file") file: MultipartFile,
  ): ParseDraftPlantingSiteBoundaryResponsePayload {
    val maxFileSize = MAX_BOUNDARY_FILE_SIZE_MB * 1024 * 1024
    if (file.size > maxFileSize) {
      throw MaxUploadSizeExceededException(maxFileSize)
    }

    return try {
      val model = draftPlantingSiteService.parseBoundaryFile(file.bytes, file.originalFilename)
      ParseDraftPlantingSiteBoundaryResponsePayload(model)
    } catch (e: GeometryFileException) {
      log.debug("Boundary file validation failed: ${e.code}", e)
      ParseDraftPlantingSiteBoundaryResponsePayload(
          filename = file.originalFilename ?: "",
          status = SuccessOrError.Error,
          problems = listOf(BoundaryFileProblemPayload(e.code)),
      )
    }
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Deletes an existing draft of an in-progress planting site.")
  fun deleteDraftPlantingSite(@PathVariable id: DraftPlantingSiteId): SimpleSuccessResponsePayload {
    draftPlantingSiteStore.delete(id)

    return SimpleSuccessResponsePayload()
  }
}

// response payload
data class DraftPlantingSitePayload(
    @Schema(description = "ID of the user who created this draft.") //
    val createdBy: UserId,
    val createdTime: Instant,
    @Schema(
        description =
            "In-progress state of the draft, including its map. The server stores the site type, " +
                "edit step, boundary source, boundary, exclusion, and strata and substrata; other " +
                "properties are not retained."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val id: DraftPlantingSiteId,
    val modifiedTime: Instant,
    val name: String,
    @Schema(description = "Number of strata in the draft.") val numStrata: Int?,
    @Schema(description = "Number of substrata in the draft.") val numSubstrata: Int?,
    val organizationId: OrganizationId,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
) {
  constructor(
      model: DraftPlantingSiteModel,
      data: ArbitraryJsonObject,
  ) : this(
      createdBy = model.createdBy!!,
      createdTime = model.createdTime!!,
      data = data,
      description = model.description,
      id = model.id!!,
      modifiedTime = model.modifiedTime!!,
      name = model.name,
      numStrata = model.numStrata,
      numSubstrata = model.numSubstrata,
      organizationId = model.organizationId,
      projectId = model.projectId,
      timeZone = model.timeZone,
  )
}

data class GetDraftPlantingSiteResponsePayload(val site: DraftPlantingSitePayload) :
    SuccessResponsePayload

data class CreateDraftPlantingSiteRequestPayload(
    @Schema(
        description =
            "In-progress state of the draft, including its map. The server stores the site type, " +
                "edit step, boundary source, boundary, exclusion, and strata and substrata; other " +
                "properties are not retained."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val name: String,
    @Schema(deprecated = true, description = "Ignored; the server computes this.")
    val numStrata: Int?,
    @Schema(deprecated = true, description = "Ignored; the server computes this.")
    val numSubstrata: Int?,
    val organizationId: OrganizationId,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
) {
  fun toModel(map: DraftPlantingSiteMap): DraftPlantingSiteModel {
    return DraftPlantingSiteModel(
        description = description,
        id = null,
        map = map,
        name = name,
        organizationId = organizationId,
        projectId = projectId,
        timeZone = timeZone,
    )
  }
}

data class CreateDraftPlantingSiteResponsePayload(val id: DraftPlantingSiteId) :
    SuccessResponsePayload

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description =
        "A parsed boundary or a content validation failure. Geometry and metadata are present only when status is ok; problems are present only when status is error."
)
data class ParseDraftPlantingSiteBoundaryResponsePayload(
    @Schema(description = "Area of the returned polygons in hectares, excluding holes.")
    val areaHa: BigDecimal? = null,
    @Schema(
        description =
            "Original filename of the uploaded file, or empty if no filename was supplied."
    )
    val filename: String,
    @Schema(
        description =
            "Detected format of the parsed contents. A ZIP containing KML is reported as KMZ; " +
                "a ZIP containing a shapefile is reported as Shapefile."
    )
    val format: GeometryFileFormat? = null,
    val geometry: Geometry? = null,
    @Schema(description = "Number of separate polygons after overlapping polygons are combined.")
    val numPolygons: Int? = null,
    @Schema(
        description =
            "One content validation problem. Omitted on success; clients translate the code into a message in the user's language."
    )
    val problems: List<BoundaryFileProblemPayload>? = null,
    override val status: SuccessOrError,
) : ResponsePayload {
  constructor(
      model: BoundaryFileModel
  ) : this(
      areaHa = model.areaHa,
      filename = model.filename,
      format = model.format,
      geometry = model.geometry,
      numPolygons = model.numPolygons,
      status = SuccessOrError.Ok,
  )
}

data class BoundaryFileProblemPayload(val code: GeometryFileErrorCode)

data class UpdateDraftPlantingSiteRequestPayload(
    @Schema(
        description =
            "In-progress state of the draft, including its map. The server stores the site type, " +
                "edit step, boundary source, boundary, exclusion, and strata and substrata; other " +
                "properties are not retained."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val name: String,
    @Schema(deprecated = true, description = "Ignored; the server computes this.")
    val numStrata: Int?,
    @Schema(deprecated = true, description = "Ignored; the server computes this.")
    val numSubstrata: Int?,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
) {
  fun applyTo(model: DraftPlantingSiteModel, map: DraftPlantingSiteMap): DraftPlantingSiteModel {
    return model.copy(
        description = description,
        map = map,
        name = name,
        projectId = projectId,
        timeZone = timeZone,
    )
  }
}
