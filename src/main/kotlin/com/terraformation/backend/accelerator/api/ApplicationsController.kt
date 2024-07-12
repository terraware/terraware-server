package com.terraformation.backend.accelerator.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/applications")
@RestController
class ApplicationsController {
  @Operation(summary = "Create a new application")
  @PostMapping
  fun createApplication(
      @RequestBody payload: CreateApplicationRequestPayload
  ): CreateApplicationResponsePayload {
    TODO()
  }

  @GetMapping("/{applicationId}")
  @Operation(summary = "Get information about an application")
  fun getApplication(@PathVariable applicationId: ApplicationId): GetApplicationResponsePayload {
    TODO()
  }

  @GetMapping("/{applicationId}/history")
  @Operation(summary = "Get the history of changes to the metadata of an application")
  fun getApplicationHistory(
      @PathVariable applicationId: ApplicationId
  ): GetApplicationHistoryResponsePayload {
    TODO()
  }

  @GetMapping
  @Operation(
      summary = "List all the applications with optional search criteria",
      description = "Only applications visible to the current user are returned.")
  fun listApplications(
      @Parameter(description = "If present, only list applications for this organization.")
      @RequestParam
      organizationId: OrganizationId? = null,
      @Parameter(
          description =
              "If present, only list applications for this project. A project can only have " +
                  "one application, so this will either return an empty result or a result with " +
                  "a single element.")
      @RequestParam
      projectId: ProjectId? = null,
  ): ListApplicationsResponsePayload {
    TODO()
  }

  @Operation(
      summary = "Restart a previously-submitted application",
      description = "If the application has not been submitted yet, this is a no-op.")
  @PostMapping("/{applicationId}/restart")
  fun restartApplication(@PathVariable applicationId: ApplicationId): SimpleSuccessResponsePayload {
    TODO()
  }

  @Operation(
      summary = "Submit an application for review",
      description = "If the application has already been submitted, this is a no-op.")
  @PostMapping("/{applicationId}/submit")
  fun submitApplication(@PathVariable applicationId: ApplicationId): SimpleSuccessResponsePayload {
    TODO()
  }

  @InternalEndpoint
  @Operation(
      summary = "Update an application's metadata to reflect a review",
      description = "This is an internal-user-only operation.")
  @PostMapping("/{applicationId}/review")
  @RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin, GlobalRole.TFExpert])
  fun reviewApplication(
      @PathVariable applicationId: ApplicationId,
      @RequestBody payload: ReviewApplicationRequestPayload
  ): SimpleSuccessResponsePayload {
    TODO()
  }

  @Operation(summary = "Update an application's boundary")
  @PutMapping("/{applicationId}/boundary")
  fun updateApplicationBoundary(
      @PathVariable applicationId: ApplicationId,
      @RequestBody payload: UpdateApplicationBoundaryRequestPayload
  ): SimpleSuccessResponsePayload {
    TODO()
  }

  @Operation(summary = "Update an application's boundary using an uploaded file")
  @PostMapping("/{applicationId}/boundary", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  fun uploadApplicationBoundary(
      @PathVariable applicationId: ApplicationId,
      @RequestPart file: MultipartFile
  ): SimpleSuccessResponsePayload {
    TODO()
  }
}

data class ApplicationHistoryPayload(
    val feedback: String?,
    @Schema(
        description =
            "Internal-only comment, if any. Only set if the current user is an internal user.")
    val internalComment: String?,
    val modifiedTime: Instant,
    val status: ApplicationStatus,
)

data class ApplicationPayload(
    val boundary: Geometry?,
    val createdTime: Instant,
    val feedback: String?,
    val id: ApplicationId,
    @Schema(
        description =
            "Internal-only comment, if any. Only set if the current user is an internal user.")
    val internalComment: String?,
    @Schema(
        description =
            "Internal-only reference name of application. Only set if the current user is an " +
                "internal user.")
    val internalName: String?,
    val organizationId: OrganizationId,
    val projectId: ProjectId,
    val status: ApplicationStatus,
)

data class CreateApplicationRequestPayload(
    @Schema(oneOf = [MultiPolygon::class, Polygon::class]) //
    val boundary: Geometry?,
    val projectId: ProjectId,
)

data class ReviewApplicationRequestPayload(
    val feedback: String?,
    val internalComment: String?,
    val status: ApplicationStatus,
)

data class UpdateApplicationBoundaryRequestPayload(
    @Schema(oneOf = [MultiPolygon::class, Polygon::class]) //
    val boundary: Geometry
)

data class CreateApplicationResponsePayload(val id: ApplicationId) : SuccessResponsePayload

data class GetApplicationHistoryResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(description = "History of metadata changes in reverse chronological order."))
    val history: List<ApplicationHistoryPayload>,
) : SuccessResponsePayload

data class GetApplicationResponsePayload(val application: ApplicationPayload) :
    SuccessResponsePayload

data class ListApplicationsResponsePayload(val applications: List<ApplicationPayload>) :
    SuccessResponsePayload
