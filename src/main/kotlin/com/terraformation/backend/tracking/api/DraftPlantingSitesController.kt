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
import com.terraformation.backend.db.tracking.tables.records.DraftPlantingSitesRecord
import com.terraformation.backend.gis.GeometryFileErrorCode
import com.terraformation.backend.gis.GeometryFileException
import com.terraformation.backend.gis.GeometryFileFormat
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.DraftPlantingSiteService
import com.terraformation.backend.tracking.db.DraftPlantingSiteStore
import com.terraformation.backend.tracking.model.BoundaryFileModel
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
    return GetDraftPlantingSiteResponsePayload(draftPlantingSiteStore.fetchOneById(id))
  }

  @Operation(summary = "Saves a draft of an in-progress planting site.")
  @PostMapping
  fun createDraftPlantingSite(
      @RequestBody payload: CreateDraftPlantingSiteRequestPayload
  ): CreateDraftPlantingSiteResponsePayload {
    val record =
        draftPlantingSiteStore.create(
            data = payload.data,
            description = payload.description,
            name = payload.name,
            numStrata = payload.numStrata,
            numSubstrata = payload.numSubstrata,
            organizationId = payload.organizationId,
            projectId = payload.projectId,
            timeZone = payload.timeZone,
        )

    return CreateDraftPlantingSiteResponsePayload(record.id!!)
  }

  @Operation(summary = "Updates an existing draft of an in-progress planting site.")
  @PutMapping("/{id}")
  fun updateDraftPlantingSite(
      @PathVariable id: DraftPlantingSiteId,
      @RequestBody payload: UpdateDraftPlantingSiteRequestPayload,
  ): SimpleSuccessResponsePayload {
    draftPlantingSiteStore.update(id, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @Operation(
      summary = "Parses a boundary file for a draft planting site.",
      description =
          "Fully stateless. Accepts KML (.kml), KMZ (.kmz), GeoJSON (.geojson or .json), or a ZIP containing " +
              "one shapefile with matching .shp, .shx, .dbf, and .prj files. " +
              "Returns the parsed geometry, original filename, detected format, number of " +
              "separate polygons, and area in hectares on success. Content validation failures " +
              "also return HTTP 200, with status error and a problems list instead of geometry. " +
              "Clients must check status and translate problem codes into user-facing messages.",
  )
  @ApiResponse200(
      description =
          "The file was processed. Check status for a parsed boundary or content validation problems."
  )
  @ApiResponse413(description = "The file exceeds the $MAX_BOUNDARY_FILE_SIZE_MB MB limit.")
  @PostMapping("/{id}/boundaryFile", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  fun parseDraftPlantingSiteBoundary(
      @PathVariable id: DraftPlantingSiteId,
      @RequestPart("file") file: MultipartFile,
  ): ParseDraftPlantingSiteBoundaryResponsePayload {
    val maxFileSize = MAX_BOUNDARY_FILE_SIZE_MB * 1024 * 1024
    if (file.size > maxFileSize) {
      throw MaxUploadSizeExceededException(maxFileSize)
    }

    return try {
      val model = draftPlantingSiteService.parseBoundaryFile(id, file.bytes, file.originalFilename)
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
    @Schema(
        description =
            "ID of the user who created this draft. Only that user is allowed to modify or " +
                "delete the draft."
    )
    val createdBy: UserId,
    val createdTime: Instant,
    @Schema(
        description =
            "In-progress state of the draft. This includes map data and other information needed " +
                "by the client. It is treated as opaque data by the server."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val id: DraftPlantingSiteId,
    val modifiedTime: Instant,
    val name: String,
    @Schema(
        description =
            "If the user has started defining strata, the number of strata defined so far."
    )
    val numStrata: Int?,
    @Schema(
        description =
            "If the user has started defining substrata, the number of substrata defined so far."
    )
    val numSubstrata: Int?,
    val organizationId: OrganizationId,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
) {
  constructor(
      record: DraftPlantingSitesRecord
  ) : this(
      createdBy = record.createdBy!!,
      createdTime = record.createdTime!!,
      data = record.data!!,
      description = record.description,
      id = record.id!!,
      modifiedTime = record.modifiedTime!!,
      name = record.name!!,
      numStrata = record.numSubstrata,
      numSubstrata = record.numSubstrata,
      organizationId = record.organizationId!!,
      projectId = record.projectId,
      timeZone = record.timeZone,
  )
}

data class GetDraftPlantingSiteResponsePayload(val site: DraftPlantingSitePayload) :
    SuccessResponsePayload {
  constructor(record: DraftPlantingSitesRecord) : this(DraftPlantingSitePayload(record))
}

data class CreateDraftPlantingSiteRequestPayload(
    @Schema(
        description =
            "In-progress state of the draft. This includes map data and other information needed " +
                "by the client. It is treated as opaque data by the server."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val name: String,
    @Schema(
        description =
            "If the user has started defining strata, the number of strata defined so far."
    )
    val numStrata: Int?,
    @Schema(
        description =
            "If the user has started defining substrata, the number of substrata defined " +
                "so far."
    )
    val numSubstrata: Int?,
    val organizationId: OrganizationId,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
)

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
            "In-progress state of the draft. This includes map data and other information needed " +
                "by the client. It is treated as opaque data by the server."
    )
    val data: ArbitraryJsonObject,
    val description: String?,
    val name: String,
    @Schema(
        description =
            "If the user has started defining strata, the number of strata defined so far."
    )
    val numStrata: Int?,
    @Schema(
        description =
            "If the user has started defining substrata, the number of substrata defined " +
                "so far."
    )
    val numSubstrata: Int?,
    @Schema(description = "If the draft is associated with a project, its ID.")
    val projectId: ProjectId?,
    val timeZone: ZoneId?,
) {
  fun applyTo(record: DraftPlantingSitesRecord) {
    record.data = data
    record.description = description
    record.name = name
    record.numSubstrata = numSubstrata
    record.numStrata = numStrata
    record.projectId = projectId
    record.timeZone = timeZone
  }
}
