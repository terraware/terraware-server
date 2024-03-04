package com.terraformation.backend.accelerator.api

import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.DeliverableCategory
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.DeliverableType
import com.terraformation.backend.db.accelerator.DocumentStore
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.net.URI
import java.time.Instant
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
class DeliverablesController {
  @ApiResponse200
  @GetMapping
  @Operation(
      summary = "Lists the deliverables for accelerator projects",
      description =
          "The list may optionally be filtered based on certain criteria as specified in the " +
              "query string. If no filter parameters are supplied, lists all the deliverables " +
              "in all the participants and projects that are visible to the user. For users with " +
              "accelerator admin privileges, this will be the full list of all deliverables for " +
              "all accelerator projects.")
  fun listDeliverables(
      @Parameter(
          description =
              "List deliverables for projects belonging to this organization. Ignored if " +
                  "participantId or projectId is specified.")
      @RequestParam
      organizationId: OrganizationId? = null,
      @Parameter(
          description =
              "List deliverables for all projects in this participant. Ignored if projectId is " +
                  "specified.")
      @RequestParam
      participantId: ParticipantId? = null,
      @Parameter(description = "List deliverables for this project only.")
      @RequestParam
      projectId: ProjectId? = null,
  ): ListDeliverablesResponsePayload {
    return ListDeliverablesResponsePayload(
        listOf(
            ListDeliverablesElement(
                category = DeliverableCategory.Compliance,
                descriptionHtml = "<p>A description</p>",
                id = DeliverableId(1),
                name = "Incorporation Documents",
                numDocuments = 2,
                organizationId = OrganizationId(1),
                organizationName = "Test Org",
                participantId = ParticipantId(2),
                participantName = "Random Participant",
                projectId = ProjectId(3),
                projectName = "Omega Project",
                status = SubmissionStatus.Rejected,
                type = DeliverableType.Document,
            ),
            ListDeliverablesElement(
                category = DeliverableCategory.FinancialViability,
                descriptionHtml = "<p>All about money!</p>",
                id = DeliverableId(1),
                name = "Budget",
                numDocuments = 0,
                organizationId = OrganizationId(1),
                organizationName = "Test Org",
                participantId = ParticipantId(2),
                participantName = "Random Participant",
                projectId = ProjectId(3),
                projectName = "Omega Project",
                status = SubmissionStatus.NotSubmitted,
                type = DeliverableType.Document,
            ),
        ))
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{deliverableId}")
  @Operation(
      summary = "Gets the details of a single deliverable and its submission documents, if any.")
  fun getDeliverable(@PathVariable deliverableId: DeliverableId): GetDeliverableResponsePayload {
    return GetDeliverableResponsePayload(
        DeliverablePayload(
            category = DeliverableCategory.Compliance,
            descriptionHtml = "<p>A description</p>",
            documents =
                listOf(
                    SubmissionDocumentPayload(
                        createdTime = Instant.now(),
                        description = "Project's articles of incorporation",
                        documentStore = DocumentStore.Google,
                        id = SubmissionDocumentId(13974),
                        name =
                            "Incorporation Documents_2024-02-28_Omega_Projects articles of incorporation.doc",
                        originalName = "corp.doc",
                    )),
            feedback = "Is this a joke? This is a bunch of cat photos, not a legal document.",
            id = deliverableId,
            internalComment = "These guys are real jokers.",
            name = "Incorporation Documents",
            organizationId = OrganizationId(1),
            organizationName = "Test Org",
            participantId = ParticipantId(2),
            participantName = "Random Participant",
            projectId = ProjectId(3),
            projectName = "Omega Project",
            status = SubmissionStatus.Rejected,
            templateUrl = "http://placekitten.com/g/200/300",
            type = DeliverableType.Document,
        ))
  }

  @ApiResponse(
      responseCode = "307",
      description =
          "If the current user has permission to view the document, redirects to the document " +
              "on the document store. Depending on the document store, the redirect URL may or " +
              "may not be valid for only a limited time.",
      headers = [Header(name = "Location", description = "URL of document in document store.")])
  @ApiResponse404
  @GetMapping("/{deliverableId}/documents/{documentId}")
  @Operation(summary = "Gets a single submission document from a deliverable.")
  fun getDeliverableDocument(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable documentId: SubmissionDocumentId
  ): ResponseEntity<String> {
    val url = URI("https://dropbox.com/")

    return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).location(url).build()
  }

  @Operation(summary = "Uploads a new document to satisfy a deliverable.")
  @PostMapping(
      "/{deliverableId}/documents/{projectId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = MediaType.ALL_VALUE)])])
  fun uploadDeliverableDocument(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable projectId: ProjectId,
      @RequestPart(required = true) description: String,
      @RequestPart(required = true) file: MultipartFile
  ): UploadDeliverableDocumentResponsePayload {
    return UploadDeliverableDocumentResponsePayload(SubmissionDocumentId(1))
  }

  @ApiResponseSimpleSuccess
  @Operation(
      summary = "Updates the state of a submission from a project.",
      description = "Only permitted for users with accelerator admin privileges.")
  @PutMapping("/{deliverableId}/submissions/{projectId}")
  fun updateSubmission(
      @PathVariable deliverableId: DeliverableId,
      @PathVariable projectId: ProjectId,
      @RequestBody payload: UpdateSubmissionRequestPayload
  ): SimpleSuccessResponsePayload {
    return SimpleSuccessResponsePayload()
  }
}

data class ListDeliverablesElement(
    val category: DeliverableCategory,
    @Schema(description = "Optional description of the deliverable in HTML form.")
    val descriptionHtml: String?,
    val id: DeliverableId,
    val name: String,
    @Schema(
        description =
            "Number of documents submitted for this deliverable. Only valid for deliverables of " +
                "type Document.")
    val numDocuments: Int?,
    val organizationId: OrganizationId,
    val organizationName: String,
    val participantId: ParticipantId,
    val participantName: String,
    val projectId: ProjectId,
    val projectName: String,
    val status: SubmissionStatus,
    val type: DeliverableType,
)

data class SubmissionDocumentPayload(
    val createdTime: Instant,
    val description: String,
    val documentStore: DocumentStore,
    val id: SubmissionDocumentId,
    val name: String,
    val originalName: String?,
)

data class DeliverablePayload(
    val category: DeliverableCategory,
    @Schema(description = "Optional description of the deliverable in HTML form.")
    val descriptionHtml: String?,
    val documents: List<SubmissionDocumentPayload>,
    @Schema(
        description =
            "If the deliverable has been reviewed, the user-visible feedback from the review.")
    val feedback: String?,
    val id: DeliverableId,
    @Schema(
        description =
            "Internal-only comment on the submission. Only present if the current user has accelerator admin privileges.")
    val internalComment: String?,
    val name: String,
    val organizationId: OrganizationId,
    val organizationName: String,
    val participantId: ParticipantId,
    val participantName: String,
    val projectId: ProjectId,
    val projectName: String,
    val status: SubmissionStatus,
    val templateUrl: String?,
    val type: DeliverableType,
)

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
