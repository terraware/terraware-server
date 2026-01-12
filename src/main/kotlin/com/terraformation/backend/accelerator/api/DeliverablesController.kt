package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.DeliverableService
import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.SubmissionService
import com.terraformation.backend.accelerator.db.DeliverableNotFoundException
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.DeliverablesImporter
import com.terraformation.backend.accelerator.db.ProjectDocumentStorageFailedException
import com.terraformation.backend.accelerator.db.SubmissionStore
import com.terraformation.backend.accelerator.model.DeliverableSubmissionModel
import com.terraformation.backend.accelerator.model.SubmissionDocumentModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.ResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessOrError
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.api.getFilename
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.importer.CsvImportFailedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.ServerErrorException
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import org.springframework.http.HttpStatus
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
@RequestMapping("/api/v1/accelerator/deliverables")
@RestController
class DeliverablesController(
    private val deliverablesImporter: DeliverablesImporter,
    private val deliverableService: DeliverableService,
    private val deliverableStore: DeliverableStore,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
    private val submissionService: SubmissionService,
    private val submissionStore: SubmissionStore,
) {
  @ApiResponse200
  @GetMapping
  @Operation(
      summary = "Lists the deliverables for accelerator projects",
      description =
          "The list may optionally be filtered based on certain criteria as specified in the " +
              "query string. If no filter parameters are supplied, lists all the deliverables " +
              "in all the participants and projects that are visible to the user. For users with " +
              "accelerator admin privileges, this will be the full list of all deliverables for " +
              "all accelerator projects.",
  )
  fun listDeliverables(
      @Parameter(
          description = "Filter deliverables by modules. Can be used with other request params."
      )
      @RequestParam
      moduleId: ModuleId? = null,
      @Parameter(
          description =
              "List deliverables for projects belonging to this organization. Ignored if " +
                  "participantId or projectId is specified."
      )
      @RequestParam
      organizationId: OrganizationId? = null,
      @Parameter(description = "List deliverables for this project only.")
      @RequestParam
      projectId: ProjectId? = null,
  ): ListDeliverablesResponsePayload {
    val models =
        deliverableStore.fetchDeliverableSubmissions(
            organizationId,
            projectId,
            moduleId = moduleId,
        )

    return ListDeliverablesResponsePayload(models.map { ListDeliverablesElement(it) })
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{deliverableId}/submissions/{projectId}")
  @Operation(
      summary = "Gets the details of a single deliverable and its submission documents, if any."
  )
  fun getDeliverable(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable projectId: ProjectId,
  ): GetDeliverableResponsePayload {
    val model =
        deliverableStore
            .fetchDeliverableSubmissions(deliverableId = deliverableId, projectId = projectId)
            .firstOrNull() ?: throw DeliverableNotFoundException(deliverableId)
    return GetDeliverableResponsePayload(DeliverablePayload(model))
  }

  @ApiResponse(
      responseCode = "307",
      description =
          "If the current user has permission to view the document, redirects to the document " +
              "on the document store. Depending on the document store, the redirect URL may or " +
              "may not be valid for only a limited time.",
      headers = [Header(name = "Location", description = "URL of document in document store.")],
  )
  @ApiResponse404
  @GetMapping("/{deliverableId}/documents/{documentId}")
  @Operation(summary = "Gets a single submission document from a deliverable.")
  fun getDeliverableDocument(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable documentId: SubmissionDocumentId,
  ): ResponseEntity<String> {
    val url = submissionService.getExternalUrl(deliverableId, documentId)

    return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(url).build()
  }

  @ApiResponse200
  @ApiResponse(
      responseCode = "507",
      description =
          "The server is unable to store the uploaded file. This response indicates a condition " +
              "that triggers the system to create a customer support ticket; clients can inform " +
              "users of that fact.",
  )
  @Operation(summary = "Uploads a new document to satisfy a deliverable.")
  @PostMapping("/{deliverableId}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = MediaType.ALL_VALUE)])]
  )
  fun uploadDeliverableDocument(
      @PathVariable deliverableId: DeliverableId,
      @RequestPart(required = true) projectId: String,
      @RequestPart(required = true) description: String,
      @RequestPart(required = true) file: MultipartFile,
  ): UploadDeliverableDocumentResponsePayload {
    try {
      val documentId =
          submissionService.receiveDocument(
              file.inputStream,
              file.getFilename(),
              ProjectId(projectId),
              deliverableId,
              description,
              file.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
          )

      return UploadDeliverableDocumentResponsePayload(documentId)
    } catch (e: ProjectDocumentStorageFailedException) {
      throw ServerErrorException(
          "Unable to store uploaded file",
          HttpStatus.INSUFFICIENT_STORAGE.value(),
          e,
      )
    }
  }

  @ApiResponse200
  @Operation(summary = "Import a list of deliverables metadata. ")
  @PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = MediaType.ALL_VALUE)])]
  )
  fun importDeliverables(
      @RequestPart(required = true) file: MultipartFile
  ): ImportDeliverableResponsePayload {
    try {
      file.inputStream.use { inputStream -> deliverablesImporter.importDeliverables(inputStream) }
    } catch (e: CsvImportFailedException) {
      return ImportDeliverableResponsePayload(
          SuccessOrError.Error,
          e.errors.map { ImportDeliverableProblemElement(it.rowNumber, it.message) },
          e.message,
      )
    }
    return ImportDeliverableResponsePayload(SuccessOrError.Ok)
  }

  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Updates the state of a submission from a project.",
      description = "Only permitted for users with accelerator admin privileges.",
  )
  @RequireGlobalRole([GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
  @PutMapping("/{deliverableId}/submissions/{projectId}")
  fun updateSubmission(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateSubmissionRequestPayload,
  ): SimpleSuccessResponsePayload {
    submissionStore.updateSubmissionStatus(
        deliverableId,
        projectId,
        payload.status,
        payload.feedback,
        payload.internalComment,
    )

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Submits a submission from a project.")
  @PostMapping("/{deliverableId}/submissions/{projectId}/submit")
  fun submitSubmission(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable projectId: ProjectId,
  ): SimpleSuccessResponsePayload {
    deliverableService.submitDeliverable(deliverableId, projectId)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Marks a submission from a project as complete.")
  @PostMapping("/{deliverableId}/submissions/{projectId}/complete")
  fun completeSubmission(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable projectId: ProjectId,
  ): SimpleSuccessResponsePayload {
    deliverableService.setDeliverableCompletion(deliverableId, projectId, true)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Marks a submission from a project as incomplete.")
  @PostMapping("/{deliverableId}/submissions/{projectId}/incomplete")
  fun incompleteSubmission(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable projectId: ProjectId,
  ): SimpleSuccessResponsePayload {
    deliverableService.setDeliverableCompletion(deliverableId, projectId, false)

    return SimpleSuccessResponsePayload()
  }
}

data class ListDeliverablesElement(
    val category: DeliverableCategory,
    val cohortId: CohortId?,
    val cohortName: String?,
    @Schema(description = "Optional description of the deliverable in HTML form.")
    val descriptionHtml: String?,
    val dueDate: LocalDate?,
    val id: DeliverableId,
    val moduleId: ModuleId,
    val moduleName: String,
    val moduleTitle: String?,
    val name: String,
    @Schema(
        description =
            "Number of documents submitted for this deliverable. Only valid for deliverables of " +
                "type Document."
    )
    val numDocuments: Int?,
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
      model: DeliverableSubmissionModel,
  ) : this(
      model.category,
      model.cohortId,
      model.cohortName,
      model.descriptionHtml,
      model.dueDate,
      model.deliverableId,
      model.moduleId,
      model.moduleName,
      model.moduleTitle,
      model.name,
      model.documents.size,
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

data class SubmissionDocumentPayload(
    val createdTime: Instant,
    val description: String?,
    val documentStore: DocumentStore,
    val id: SubmissionDocumentId,
    val name: String,
    val originalName: String?,
) {
  constructor(
      model: SubmissionDocumentModel
  ) : this(
      model.createdTime,
      model.description,
      model.documentStore,
      model.id,
      model.name,
      model.originalName,
  )
}

data class DeliverablePayload(
    val category: DeliverableCategory,
    @Schema(description = "Optional description of the deliverable in HTML form.")
    val descriptionHtml: String?,
    val documents: List<SubmissionDocumentPayload>,
    @Schema(
        description =
            "If the deliverable has been reviewed, the user-visible feedback from the review."
    )
    val dueDate: LocalDate?,
    val feedback: String?,
    val id: DeliverableId,
    @Schema(
        description =
            "Internal-only comment on the submission. Only present if the current user has accelerator admin privileges."
    )
    val internalComment: String?,
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
    val templateUrl: URI?,
    val type: DeliverableType,
) {
  constructor(
      model: DeliverableSubmissionModel,
  ) : this(
      model.category,
      model.descriptionHtml,
      model.documents.map { SubmissionDocumentPayload(it) },
      model.dueDate,
      model.feedback,
      model.deliverableId,
      model.internalComment,
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
      model.templateUrl,
      model.type,
  )
}

data class ImportDeliverableProblemElement(
    val row: Int,
    val problem: String,
)

data class ImportDeliverableResponsePayload(
    override val status: SuccessOrError,
    val problems: List<ImportDeliverableProblemElement> = emptyList(),
    val message: String? = null,
) : ResponsePayload

data class GetDeliverableResponsePayload(
    val deliverable: DeliverablePayload,
) : SuccessResponsePayload

data class ListDeliverablesResponsePayload(val deliverables: List<ListDeliverablesElement>) :
    SuccessResponsePayload

data class UpdateSubmissionRequestPayload(
    val feedback: String?,
    val internalComment: String?,
    val status: SubmissionStatus,
)

data class UploadDeliverableDocumentResponsePayload(val documentId: SubmissionDocumentId) :
    SuccessResponsePayload
