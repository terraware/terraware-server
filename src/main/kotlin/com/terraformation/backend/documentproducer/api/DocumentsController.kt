package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.tables.pojos.DocumentSavedVersionsRow
import com.terraformation.backend.db.docprod.tables.pojos.DocumentsRow
import com.terraformation.backend.documentproducer.DocumentService
import com.terraformation.backend.documentproducer.DocumentUpgradeService
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.model.ExistingDocumentModel
import com.terraformation.backend.documentproducer.model.ExistingSavedVersionModel
import com.terraformation.backend.documentproducer.model.NewDocumentModel
import com.terraformation.backend.documentproducer.model.NewSavedVersionModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/document-producer/documents")
@RestController
class DocumentsController(
    private val documentService: DocumentService,
    private val documentStore: DocumentStore,
    private val documentUpgradeService: DocumentUpgradeService,
) {
  @GetMapping
  @Operation(summary = "Gets a list of all the documents.")
  fun listDocuments(
      @Parameter(description = "If present, only list documents for this project.")
      @RequestParam
      projectId: ProjectId?
  ): ListDocumentsResponsePayload {
    val models =
        if (projectId != null) {
          documentStore.fetchByProjectId(projectId)
        } else {
          documentStore.fetchAll()
        }
    return ListDocumentsResponsePayload(models.map { DocumentPayload(it) })
  }

  @Operation(summary = "Creates a new document.")
  @PostMapping
  fun createDocument(
      @RequestBody payload: CreateDocumentRequestPayload
  ): CreateDocumentResponsePayload {
    val model = documentService.create(payload.toModel())

    return CreateDocumentResponsePayload(DocumentPayload(model))
  }

  @Operation(summary = "Gets a document.")
  @GetMapping("/{id}")
  fun getDocument(@PathVariable("id") id: DocumentId): GetDocumentResponsePayload {
    return GetDocumentResponsePayload(DocumentPayload(documentStore.fetchOneById(id)))
  }

  @Operation(summary = "Updates a document.")
  @PutMapping("/{id}")
  fun updateDocument(
      @PathVariable("id") id: DocumentId,
      @RequestBody payload: UpdateDocumentRequestPayload,
  ): SimpleSuccessResponsePayload {
    documentStore.updateDocument(id) { payload.applyChanges(it) }

    return SimpleSuccessResponsePayload()
  }

  @Operation(
      summary =
          "Gets the history of a document. This includes both information about document edits " +
              "and information about saved versions."
  )
  @GetMapping("/{id}/history")
  fun getDocumentHistory(@PathVariable id: DocumentId): GetDocumentHistoryResponsePayload {
    val document = documentStore.fetchDocumentById(id)
    val createdEntry = DocumentHistoryCreatedPayload(document)
    val savedVersions = documentStore.listSavedVersions(id).map { DocumentHistorySavedPayload(it) }
    val editHistory = documentStore.listEditHistory(id).map { DocumentHistoryEditedPayload(it) }

    val events = (editHistory + savedVersions + createdEntry).sortedByDescending { it.createdTime }

    return GetDocumentHistoryResponsePayload(events)
  }

  @ApiResponse200
  @ApiResponse404
  @ApiResponse409("The document has no values to save.")
  @Operation(summary = "Saves a version of a document.")
  @PostMapping("/{documentId}/versions")
  fun createSavedDocumentVersion(
      @PathVariable documentId: DocumentId,
      @RequestBody payload: CreateSavedDocumentVersionRequestPayload,
  ): CreateSavedDocumentVersionResponsePayload {
    val model = documentStore.createSavedVersion(payload.toModel(documentId))

    return CreateSavedDocumentVersionResponsePayload(DocumentSavedVersionPayload(model))
  }

  @GetMapping("/{documentId}/versions/{versionId}")
  @Operation(summary = "Gets details of a specific saved version of a document.")
  fun getSavedDocumentVersion(
      @PathVariable documentId: DocumentId,
      @PathVariable versionId: DocumentSavedVersionId,
  ): GetSavedDocumentVersionResponsePayload {
    val model = documentStore.fetchSavedVersion(documentId, versionId)

    return GetSavedDocumentVersionResponsePayload(DocumentSavedVersionPayload(model))
  }

  @Operation(summary = "Updates a saved version of a document.")
  @PutMapping("/{documentId}/versions/{versionId}")
  fun updateSavedDocumentVersion(
      @PathVariable documentId: DocumentId,
      @PathVariable versionId: DocumentSavedVersionId,
      @RequestBody payload: UpdateSavedDocumentVersionRequestPayload,
  ): SimpleSuccessResponsePayload {
    documentStore.updateSavedVersion(documentId, versionId, payload::applyChanges)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404(
      description = "The document does not exist or the requested manifest does not exist."
  )
  @ApiResponse409(
      description =
          "The requested manifest is for a different document template than the current one."
  )
  @Operation(
      summary = "Upgrades a document to a newer manifest.",
      description = "The manifest must be for the same document template as the existing manifest.",
  )
  @PostMapping("/{documentId}/upgrade")
  fun upgradeManifest(
      @PathVariable documentId: DocumentId,
      @RequestBody payload: UpgradeManifestRequestPayload,
  ): SimpleSuccessResponsePayload {
    documentUpgradeService.upgradeManifest(documentId, payload.variableManifestId)

    return SimpleSuccessResponsePayload()
  }
}

data class DocumentPayload(
    val createdBy: UserId,
    val createdTime: Instant,
    val documentTemplateId: DocumentTemplateId,
    val documentTemplateName: String,
    val id: DocumentId,
    val internalComment: String?,
    val lastSavedVersionId: DocumentSavedVersionId?,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val name: String,
    val ownedBy: UserId,
    val projectDealName: String?,
    val projectId: ProjectId,
    val projectName: String,
    val status: DocumentStatus,
    val variableManifestId: VariableManifestId,
) {
  constructor(
      model: ExistingDocumentModel
  ) : this(
      createdBy = model.createdBy,
      createdTime = model.createdTime,
      documentTemplateId = model.documentTemplateId,
      documentTemplateName = model.documentTemplateName,
      id = model.id,
      internalComment = model.internalComment,
      lastSavedVersionId = model.lastSavedVersionId,
      modifiedBy = model.modifiedBy,
      modifiedTime = model.modifiedTime,
      name = model.name,
      ownedBy = model.ownedBy,
      projectDealName = model.projectDealName,
      projectId = model.projectId,
      projectName = model.projectName,
      status = model.status,
      variableManifestId = model.variableManifestId,
  )
}

@Schema(
    description =
        "Information about a saved version of a document. The maxVariableValueId and " +
            "variableManifestId may be used to retrieve the contents of the saved version."
)
data class DocumentSavedVersionPayload(
    val createdBy: UserId,
    val createdTime: Instant,
    val isSubmitted: Boolean,
    val maxVariableValueId: VariableValueId,
    val name: String,
    val variableManifestId: VariableManifestId,
    val versionId: DocumentSavedVersionId,
) {
  constructor(
      model: ExistingSavedVersionModel
  ) : this(
      createdBy = model.createdBy,
      createdTime = model.createdTime,
      isSubmitted = model.isSubmitted,
      maxVariableValueId = model.maxVariableValueId,
      name = model.name,
      variableManifestId = model.variableManifestId,
      versionId = model.id,
  )
}

data class CreateDocumentRequestPayload(
    val documentTemplateId: DocumentTemplateId,
    val name: String,
    val ownedBy: UserId,
    val projectId: ProjectId,
) {
  fun toModel() = NewDocumentModel(documentTemplateId, name, ownedBy, projectId)
}

data class CreateSavedDocumentVersionRequestPayload(
    @Schema(defaultValue = "false") val isSubmitted: Boolean?,
    val name: String,
) {
  fun toModel(documentId: DocumentId) =
      NewSavedVersionModel(
          documentId = documentId,
          isSubmitted = isSubmitted ?: false,
          name = name,
      )
}

data class UpdateDocumentRequestPayload(
    val internalComment: String?,
    val name: String,
    val ownedBy: UserId,
    val status: DocumentStatus,
) {
  fun applyChanges(row: DocumentsRow) =
      row.copy(
          internalComment = internalComment,
          name = name,
          ownedBy = ownedBy,
          statusId = status,
      )
}

data class UpdateSavedDocumentVersionRequestPayload(val isSubmitted: Boolean) {
  fun applyChanges(row: DocumentSavedVersionsRow) = row.copy(isSubmitted = isSubmitted)
}

data class UpgradeManifestRequestPayload(
    @Schema(
        description =
            "ID of manifest to upgrade the document to. This must be greater than the document's " +
                "current manifest ID (downgrades are not supported) and must be for the same " +
                "document template as the current manifest."
    )
    val variableManifestId: VariableManifestId
)

data class CreateDocumentResponsePayload(val document: DocumentPayload) : SuccessResponsePayload

data class CreateSavedDocumentVersionResponsePayload(
    val version: DocumentSavedVersionPayload,
) : SuccessResponsePayload

data class GetDocumentHistoryResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of events in the document's history in reverse chronological order. " +
                        "The last element is always the \"Created\" event."
            )
    )
    val history: List<DocumentHistoryPayload>,
) : SuccessResponsePayload

data class GetDocumentResponsePayload(val document: DocumentPayload) : SuccessResponsePayload

data class GetSavedDocumentVersionResponsePayload(
    val version: DocumentSavedVersionPayload,
) : SuccessResponsePayload

data class ListDocumentsResponsePayload(val documents: List<DocumentPayload>) :
    SuccessResponsePayload
