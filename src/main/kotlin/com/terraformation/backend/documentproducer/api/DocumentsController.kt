package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.DocumentStatus
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.tables.pojos.DocumentSavedVersionsRow
import com.terraformation.backend.db.docprod.tables.pojos.DocumentsRow
import com.terraformation.backend.documentproducer.DocumentUpgradeService
import com.terraformation.backend.documentproducer.db.DocumentStore
import com.terraformation.backend.documentproducer.model.ExistingDocumentModel
import com.terraformation.backend.documentproducer.model.ExistingSavedVersionModel
import com.terraformation.backend.documentproducer.model.NewDocumentModel
import com.terraformation.backend.documentproducer.model.NewSavedVersionModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/pdds")
@RestController
class DocumentsController(
    private val documentStore: DocumentStore,
    private val documentUpgradeService: DocumentUpgradeService,
) {
  @GetMapping
  @Operation(summary = "Gets a list of all the documents.")
  fun listPdds(): ListPddsResponsePayload {
    val models = documentStore.findAll()

    return ListPddsResponsePayload(models.map { PddPayload(it) })
  }

  @Operation(summary = "Creates a new document.")
  @PostMapping
  fun createPdd(@RequestBody payload: CreatePddRequestPayload): CreatePddResponsePayload {
    val model = documentStore.create(payload.toModel())

    return CreatePddResponsePayload(PddPayload(model))
  }

  @Operation(summary = "Gets a document.")
  @GetMapping("/{id}")
  fun getPdd(@PathVariable("id") id: DocumentId): GetPddResponsePayload {
    return GetPddResponsePayload(
        PddPayload(ExistingDocumentModel(documentStore.fetchDocumentById(id))))
  }

  @Operation(summary = "Updates a document.")
  @PutMapping("/{id}")
  fun updatePdd(
      @PathVariable("id") id: DocumentId,
      @RequestBody payload: UpdatePddRequestPayload
  ): SimpleSuccessResponsePayload {
    documentStore.updateDocument(id) { payload.applyChanges(it) }

    return SimpleSuccessResponsePayload()
  }

  @Operation(
      summary =
          "Gets the history of a document. This includes both information about document edits " +
              "and information about saved versions.")
  @GetMapping("/{id}/history")
  fun getDocumentHistory(@PathVariable id: DocumentId): GetDocumentHistoryResponsePayload {
    val document = documentStore.fetchDocumentById(id)
    val createdEntry = PddHistoryCreatedPayload(document)
    val savedVersions = documentStore.listSavedVersions(id).map { PddHistorySavedPayload(it) }
    val editHistory = documentStore.listEditHistory(id).map { PddHistoryEditedPayload(it) }

    val events = (editHistory + savedVersions + createdEntry).sortedByDescending { it.createdTime }

    return GetDocumentHistoryResponsePayload(events)
  }

  @ApiResponse200
  @ApiResponse404
  @ApiResponse409("The document has no values to save.")
  @Operation(summary = "Saves a version of a document.")
  @PostMapping("/{pddId}/versions")
  fun createSavedPddVersion(
      @PathVariable pddId: DocumentId,
      @RequestBody payload: CreateSavedPddVersionRequestPayload,
  ): CreateSavedPddVersionResponsePayload {
    val model = documentStore.createSavedVersion(payload.toModel(pddId))

    return CreateSavedPddVersionResponsePayload(PddSavedVersionPayload(model))
  }

  @GetMapping("/{pddId}/versions/{versionId}")
  @Operation(summary = "Gets details of a specific saved version of a document.")
  fun getSavedPddVersion(
      @PathVariable pddId: DocumentId,
      @PathVariable versionId: DocumentSavedVersionId,
  ): GetSavedPddVersionResponsePayload {
    val model = documentStore.fetchSavedVersion(pddId, versionId)

    return GetSavedPddVersionResponsePayload(PddSavedVersionPayload(model))
  }

  @Operation(summary = "Updates a saved version of a document.")
  @PutMapping("/{pddId}/versions/{versionId}")
  fun updateSavedPddVersion(
      @PathVariable pddId: DocumentId,
      @PathVariable versionId: DocumentSavedVersionId,
      @RequestBody payload: UpdateSavedPddVersionRequestPayload,
  ): SimpleSuccessResponsePayload {
    documentStore.updateSavedVersion(pddId, versionId, payload::applyChanges)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404(
      description = "The document does not exist or the requested manifest does not exist.")
  @ApiResponse409(
      description =
          "The requested manifest is for a different document template than the current one.")
  @Operation(
      summary = "Upgrades a document to a newer manifest.",
      description = "The manifest must be for the same document template as the existing manifest.")
  @PostMapping("/{documentId}/upgrade")
  fun upgradeManifest(
      @PathVariable documentId: DocumentId,
      @RequestBody payload: UpgradeManifestRequestPayload,
  ): SimpleSuccessResponsePayload {
    documentUpgradeService.upgradeManifest(documentId, payload.variableManifestId)

    return SimpleSuccessResponsePayload()
  }
}

data class PddPayload(
    val createdBy: UserId,
    val createdTime: Instant,
    val id: DocumentId,
    val documentTemplateId: DocumentTemplateId,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val name: String,
    val organizationName: String,
    val ownedBy: UserId,
    val status: DocumentStatus,
    val variableManifestId: VariableManifestId,
) {
  constructor(
      model: ExistingDocumentModel
  ) : this(
      createdBy = model.createdBy,
      createdTime = model.createdTime,
      id = model.id,
      documentTemplateId = model.documentTemplateId,
      modifiedBy = model.modifiedBy,
      modifiedTime = model.modifiedTime,
      name = model.name,
      organizationName = model.organizationName,
      ownedBy = model.ownedBy,
      status = model.status,
      variableManifestId = model.variableManifestId,
  )
}

@Schema(
    description =
        "Information about a saved version of a document. The maxVariableValueId and " +
            "variableManifestId may be used to retrieve the contents of the saved version.")
data class PddSavedVersionPayload(
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

data class CreatePddRequestPayload(
    val documentTemplateId: DocumentTemplateId,
    val name: String,
    val organizationName: String,
    val ownedBy: UserId,
) {
  fun toModel() = NewDocumentModel(documentTemplateId, name, organizationName, ownedBy)
}

data class CreateSavedPddVersionRequestPayload(
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

data class UpdatePddRequestPayload(
    val name: String,
    val organizationName: String,
    val ownedBy: UserId,
) {
  fun applyChanges(row: DocumentsRow) =
      row.copy(
          name = name,
          organizationName = organizationName,
          ownedBy = ownedBy,
      )
}

data class UpdateSavedPddVersionRequestPayload(val isSubmitted: Boolean) {
  fun applyChanges(row: DocumentSavedVersionsRow) = row.copy(isSubmitted = isSubmitted)
}

data class UpgradeManifestRequestPayload(
    @Schema(
        description =
            "ID of manifest to upgrade the document to. This must be greater than the document's " +
                "current manifest ID (downgrades are not supported) and must be for the same " +
                "document template as the current manifest.")
    val variableManifestId: VariableManifestId
)

data class CreatePddResponsePayload(val pdd: PddPayload) : SuccessResponsePayload

data class CreateSavedPddVersionResponsePayload(
    val version: PddSavedVersionPayload,
) : SuccessResponsePayload

data class GetDocumentHistoryResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of events in the document's history in reverse chronological order. " +
                        "The last element is always the \"Created\" event."))
    val history: List<PddHistoryPayload>,
) : SuccessResponsePayload

data class GetPddResponsePayload(val pdd: PddPayload) : SuccessResponsePayload

data class GetSavedPddVersionResponsePayload(
    val version: PddSavedVersionPayload,
) : SuccessResponsePayload

data class ListPddsResponsePayload(val pdds: List<PddPayload>) : SuccessResponsePayload
