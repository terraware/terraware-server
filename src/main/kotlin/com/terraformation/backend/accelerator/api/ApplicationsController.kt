package com.terraformation.backend.accelerator.api

import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.accelerator.ApplicationService
import com.terraformation.backend.accelerator.db.ApplicationStore
import com.terraformation.backend.accelerator.model.ApplicationModuleModel
import com.terraformation.backend.accelerator.model.ApplicationSubmissionResult
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.ExistingApplicationModel
import com.terraformation.backend.accelerator.model.ExternalApplicationStatus
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.toInputStream
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.ApplicationModuleStatus
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.records.ApplicationHistoriesRecord
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.util.toMultiPolygon
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.BadRequestException
import java.time.Instant
import org.geotools.util.ContentFormatException
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
class ApplicationsController(
    private val applicationService: ApplicationService,
    private val applicationStore: ApplicationStore,
    private val geometryFileParser: GeometryFileParser,
) {
  @Operation(summary = "Create a new application")
  @PostMapping
  fun createApplication(
      @RequestBody payload: CreateApplicationRequestPayload
  ): CreateApplicationResponsePayload {
    val model = applicationStore.create(payload.projectId)

    return CreateApplicationResponsePayload(model.id)
  }

  @GetMapping("/{applicationId}")
  @Operation(summary = "Get information about an application")
  fun getApplication(@PathVariable applicationId: ApplicationId): GetApplicationResponsePayload {
    val model = applicationStore.fetchOneById(applicationId)

    return GetApplicationResponsePayload(ApplicationPayload.of(model))
  }

  @GetMapping("/{applicationId}/deliverables")
  @Operation(summary = "Get deliverables for an application")
  fun getApplicationDeliverables(
      @PathVariable applicationId: ApplicationId
  ): GetApplicationDeliverablesResponsePayload {
    val deliverables = applicationStore.fetchApplicationDeliverables(applicationId = applicationId)

    return GetApplicationDeliverablesResponsePayload(
        deliverables.map { ApplicationDeliverablePayload(it) }
    )
  }

  @GetMapping("/{applicationId}/export", produces = ["application/geo+json"])
  @Operation(summary = "Get GeoJSON for an application")
  fun getApplicationGeoJson(
      @PathVariable applicationId: ApplicationId
  ): ResponseEntity<InputStreamResource> {
    val geoFeature = applicationStore.fetchGeoFeatureById(applicationId)
    val filename = "application-$applicationId.geojson"

    return geoFeature.toInputStream().toResponseEntity {
      contentDisposition = ContentDisposition.attachment().filename(filename).build()
    }
  }

  @GetMapping("/{applicationId}/history")
  @Operation(summary = "Get the history of changes to the metadata of an application")
  fun getApplicationHistory(
      @PathVariable applicationId: ApplicationId
  ): GetApplicationHistoryResponsePayload {
    val history = applicationStore.fetchHistoryByApplicationId(applicationId)

    return GetApplicationHistoryResponsePayload(history.map { ApplicationHistoryPayload(it) })
  }

  @GetMapping("/{applicationId}/modules")
  @Operation(summary = "Get modules for an application")
  fun getApplicationModules(
      @PathVariable applicationId: ApplicationId
  ): GetApplicationModulesResponsePayload {
    val modules = applicationStore.fetchModulesByApplicationId(applicationId)

    return GetApplicationModulesResponsePayload(modules.map { ApplicationModulePayload(it) })
  }

  @GetMapping("/{applicationId}/modules/{moduleId}/deliverables")
  @Operation(summary = "Get deliverables for an application module")
  fun getApplicationModuleDeliverables(
      @PathVariable applicationId: ApplicationId,
      @PathVariable moduleId: ModuleId,
  ): GetApplicationDeliverablesResponsePayload {
    val deliverables =
        applicationStore.fetchApplicationDeliverables(
            applicationId = applicationId,
            moduleId = moduleId,
        )

    return GetApplicationDeliverablesResponsePayload(
        deliverables.map { ApplicationDeliverablePayload(it) }
    )
  }

  @GetMapping
  @Operation(
      summary = "List all the applications with optional search criteria",
      description = "Only applications visible to the current user are returned.",
  )
  fun listApplications(
      @Parameter(description = "If present, only list applications for this organization.")
      @RequestParam
      organizationId: OrganizationId? = null,
      @Parameter(
          description =
              "If present, only list applications for this project. A project can only have " +
                  "one application, so this will either return an empty result or a result with " +
                  "a single element."
      )
      @RequestParam
      projectId: ProjectId? = null,
      @Parameter(
          description =
              "If true, list all applications for all projects. Only allowed for internal users."
      )
      @RequestParam
      listAll: Boolean? = null,
  ): ListApplicationsResponsePayload {
    val models =
        when {
          organizationId != null -> applicationStore.fetchByOrganizationId(organizationId)
          projectId != null -> applicationStore.fetchByProjectId(projectId)
          listAll == true -> applicationStore.fetchAll()
          else ->
              throw BadRequestException(
                  "One of organizationId, projectId, or listAll must be specified"
              )
        }

    return ListApplicationsResponsePayload(models.map { ApplicationPayload.of(it) })
  }

  @Operation(
      summary = "Restart a previously-submitted application",
      description = "If the application has not been submitted yet, this is a no-op.",
  )
  @PostMapping("/{applicationId}/restart")
  fun restartApplication(@PathVariable applicationId: ApplicationId): SimpleSuccessResponsePayload {
    applicationStore.restart(applicationId)

    return SimpleSuccessResponsePayload()
  }

  @Operation(
      summary = "Submit an application for review",
      description = "If the application has already been submitted, this is a no-op.",
  )
  @PostMapping("/{applicationId}/submit")
  fun submitApplication(
      @PathVariable applicationId: ApplicationId
  ): SubmitApplicationResponsePayload {
    val result = applicationService.submit(applicationId)

    return SubmitApplicationResponsePayload(result)
  }

  @InternalEndpoint
  @Operation(
      summary = "Update an application's metadata to reflect a review",
      description = "This is an internal-user-only operation.",
  )
  @PostMapping("/{applicationId}/review")
  @RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin, GlobalRole.TFExpert])
  fun reviewApplication(
      @PathVariable applicationId: ApplicationId,
      @RequestBody payload: ReviewApplicationRequestPayload,
  ): SimpleSuccessResponsePayload {
    applicationStore.review(applicationId, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Update an application's boundary")
  @PutMapping("/{applicationId}/boundary")
  fun updateApplicationBoundary(
      @PathVariable applicationId: ApplicationId,
      @RequestBody payload: UpdateApplicationBoundaryRequestPayload,
  ): SimpleSuccessResponsePayload {
    applicationService.updateBoundary(applicationId, payload.boundary)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Update an application's boundary using an uploaded file")
  @PostMapping("/{applicationId}/boundary", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  fun uploadApplicationBoundary(
      @PathVariable applicationId: ApplicationId,
      @RequestPart file: MultipartFile,
  ): SimpleSuccessResponsePayload {
    val geometry =
        try {
          geometryFileParser.parse(file.bytes, file.name)
        } catch (e: ContentFormatException) {
          throw BadRequestException(e.message)
        }

    applicationService.updateBoundary(applicationId, geometry)

    return SimpleSuccessResponsePayload()
  }
}

/**
 * Application statuses as exposed via API. This is the same as [ApplicationStatus] but with an
 * additional "In Review" value. If the user doesn't have permission to see accelerator details,
 * several underlying statuses are replaced with "In Review" in API responses.
 */
enum class ApiApplicationStatus(@get:JsonValue val jsonValue: String) {
  Accepted("Accepted"),
  CarbonAssessment("Carbon Assessment"),
  ExpertReview("Expert Review"),
  FailedPreScreen("Failed Pre-screen"),
  IssueActive("Issue Active"),
  IssueReassessment("Issue Reassessment"),
  NotEligible("Not Eligible"),
  NotSubmitted("Not Submitted"),
  P0Eligible("P0 Eligible"),
  PassedPreScreen("Passed Pre-screen"),
  SourcingTeamReview("Sourcing Team Review"),
  GISAssessment("GIS Assessment"),
  Submitted("Submitted"),
  // User-facing statuses, not stored in database
  InReview("In Review"),
  Waitlist("Waitlist");

  companion object {
    fun of(status: ApplicationStatus): ApiApplicationStatus {
      val exposeInternalStatuses = currentUser().canReadAllAcceleratorDetails()

      if (!exposeInternalStatuses) {
        return of(ExternalApplicationStatus.of(status))
      }

      return when (status) {
        ApplicationStatus.Accepted -> Accepted
        ApplicationStatus.CarbonAssessment -> CarbonAssessment
        ApplicationStatus.ExpertReview -> ExpertReview
        ApplicationStatus.FailedPreScreen -> FailedPreScreen
        ApplicationStatus.GISAssessment -> GISAssessment
        ApplicationStatus.IssueActive -> IssueActive
        ApplicationStatus.IssueReassessment -> IssueReassessment
        ApplicationStatus.NotEligible -> NotEligible
        ApplicationStatus.NotSubmitted -> NotSubmitted
        ApplicationStatus.P0Eligible -> P0Eligible
        ApplicationStatus.PassedPreScreen -> PassedPreScreen
        ApplicationStatus.SourcingTeamReview -> SourcingTeamReview
        ApplicationStatus.Submitted -> Submitted
      }
    }

    fun of(externalStatus: ExternalApplicationStatus): ApiApplicationStatus {
      return when (externalStatus) {
        ExternalApplicationStatus.NotSubmitted -> NotSubmitted
        ExternalApplicationStatus.FailedPreScreen -> FailedPreScreen
        ExternalApplicationStatus.PassedPreScreen -> PassedPreScreen
        ExternalApplicationStatus.InReview -> InReview
        ExternalApplicationStatus.Accepted -> Accepted
        ExternalApplicationStatus.NotEligible -> NotEligible
        ExternalApplicationStatus.Waitlist -> Waitlist
      }
    }
  }
}

data class ApplicationHistoryPayload(
    val feedback: String?,
    @Schema(
        description =
            "Internal-only comment, if any. Only set if the current user is an internal user."
    )
    val internalComment: String?,
    val modifiedTime: Instant,
    val status: ApiApplicationStatus,
) {
  constructor(
      record: ApplicationHistoriesRecord
  ) : this(
      record.feedback,
      record.internalComment,
      record.modifiedTime!!,
      ApiApplicationStatus.of(record.applicationStatusId!!),
  )
}

data class ApplicationPayload(
    val boundary: MultiPolygon?,
    val countryCode: String?,
    val createdTime: Instant,
    val feedback: String?,
    val id: ApplicationId,
    @Schema(
        description =
            "Internal-only comment, if any. Only set if the current user is an internal user."
    )
    val internalComment: String?,
    @Schema(
        description =
            "Internal-only reference name of application. Only set if the current user is an " +
                "internal user."
    )
    val internalName: String?,
    val modifiedTime: Instant?,
    val organizationId: OrganizationId,
    val organizationName: String,
    val projectId: ProjectId,
    val projectName: String,
    val status: ApiApplicationStatus,
) {
  companion object {
    fun of(model: ExistingApplicationModel): ApplicationPayload {
      val exposeInternalFields = currentUser().canReadAllAcceleratorDetails()
      return ApplicationPayload(
          boundary = model.boundary?.toMultiPolygon(),
          countryCode = model.countryCode,
          createdTime = model.createdTime,
          feedback = model.feedback,
          id = model.id,
          internalComment = if (exposeInternalFields) model.internalComment else null,
          internalName = if (exposeInternalFields) model.internalName else null,
          modifiedTime = model.modifiedTime,
          organizationId = model.organizationId,
          organizationName = model.organizationName,
          projectId = model.projectId,
          projectName = model.projectName,
          status = ApiApplicationStatus.of(model.status),
      )
    }
  }
}

data class ApplicationModulePayload(
    val applicationId: ApplicationId?,
    val moduleId: ModuleId,
    val name: String,
    val phase: CohortPhase,
    val overview: String? = null,
    val status: ApplicationModuleStatus?,
) {
  constructor(
      model: ApplicationModuleModel
  ) : this(
      model.applicationId,
      model.id,
      model.name,
      model.phase,
      model.overview,
      model.applicationModuleStatus,
  )
}

data class ApplicationDeliverablePayload(
    val category: DeliverableCategory,
    @Schema(description = "Optional description of the deliverable in HTML form.")
    val descriptionHtml: String?,
    val documents: List<SubmissionDocumentPayload>,
    val id: DeliverableId,
    val internalComment: String?,
    val modifiedTime: Instant?,
    val moduleId: ModuleId,
    val moduleName: String,
    val name: String,
    val organizationId: OrganizationId,
    val organizationName: String,
    val position: Int,
    val projectDealName: String?,
    val projectId: ProjectId,
    val projectName: String,
    val required: Boolean,
    val sensitive: Boolean,
    val status: SubmissionStatus,
    val type: DeliverableType,
) {
  constructor(
      model: DeliverableSubmissionModel
  ) : this(
      model.category,
      model.descriptionHtml,
      model.documents.map { SubmissionDocumentPayload(it) },
      model.deliverableId,
      model.internalComment,
      model.modifiedTime,
      model.moduleId,
      model.moduleName,
      model.name,
      model.organizationId,
      model.organizationName,
      model.position,
      model.projectDealName,
      model.projectId,
      model.projectName,
      model.required,
      model.sensitive,
      model.status,
      model.type,
  )
}

data class CreateApplicationRequestPayload(val projectId: ProjectId)

data class ReviewApplicationRequestPayload(
    val feedback: String?,
    val internalComment: String?,
    // This is not ApiApplicationStatus since setting an application to "In Review" would make no
    // sense as an admin operation.
    val status: ApplicationStatus,
) {
  fun applyTo(model: ExistingApplicationModel) =
      model.copy(feedback = feedback, internalComment = internalComment, status = status)
}

data class UpdateApplicationBoundaryRequestPayload(
    @Schema(oneOf = [MultiPolygon::class, Polygon::class]) //
    val boundary: Geometry
)

data class CreateApplicationResponsePayload(val id: ApplicationId) : SuccessResponsePayload

data class GetApplicationHistoryResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(description = "History of metadata changes in reverse chronological order.")
    )
    val history: List<ApplicationHistoryPayload>,
) : SuccessResponsePayload

data class GetApplicationResponsePayload(val application: ApplicationPayload) :
    SuccessResponsePayload

data class ListApplicationsResponsePayload(val applications: List<ApplicationPayload>) :
    SuccessResponsePayload

data class GetApplicationModulesResponsePayload(val modules: List<ApplicationModulePayload>) :
    SuccessResponsePayload

data class GetApplicationDeliverablesResponsePayload(
    val deliverables: List<ApplicationDeliverablePayload>
) : SuccessResponsePayload

data class SubmitApplicationResponsePayload(
    val application: ApplicationPayload,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "If the application failed any of the pre-screening checks, a list of the " +
                        "reasons why. Empty if the application passed pre-screening."
            )
    )
    val problems: List<String>,
) : SuccessResponsePayload {
  constructor(
      result: ApplicationSubmissionResult
  ) : this(ApplicationPayload.of(result.application), result.problems)
}
