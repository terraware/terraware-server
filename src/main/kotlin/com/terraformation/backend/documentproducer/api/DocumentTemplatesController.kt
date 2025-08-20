package com.terraformation.backend.documentproducer.api

import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.tables.daos.DocumentTemplatesDao
import com.terraformation.backend.documentproducer.db.VariableManifestStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/document-producer/templates")
@RestController
class DocumentTemplatesController(
    val documentTemplatesDao: DocumentTemplatesDao,
    val variableManifestStore: VariableManifestStore,
) {
  @GetMapping
  @Operation(summary = "Gets a list of all the valid document templates.")
  fun listDocumentTemplates(): ListDocumentTemplatesResponsePayload {
    val manifestIds = variableManifestStore.fetchLatestVariableManifestIds()

    val documentTemplates =
        documentTemplatesDao.findAll().map { documentTemplatesRow ->
          DocumentTemplatePayload(
              id = documentTemplatesRow.id!!,
              name = documentTemplatesRow.name!!,
              variableManifestId = manifestIds[documentTemplatesRow.id],
          )
        }

    return ListDocumentTemplatesResponsePayload(documentTemplates)
  }
}

data class DocumentTemplatePayload(
    val id: DocumentTemplateId,
    val name: String,
    @Schema(
        description = "ID of the most recent variable manifest for the document template, if any."
    )
    val variableManifestId: VariableManifestId?,
)

data class ListDocumentTemplatesResponsePayload(
    val documentTemplates: List<DocumentTemplatePayload>
) : SuccessResponsePayload
