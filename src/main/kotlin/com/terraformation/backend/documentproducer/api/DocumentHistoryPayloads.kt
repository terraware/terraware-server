package com.terraformation.backend.documentproducer.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentSavedVersionId
import com.terraformation.backend.db.docprod.VariableManifestId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.tables.pojos.DocumentsRow
import com.terraformation.backend.documentproducer.model.EditHistoryModel
import com.terraformation.backend.documentproducer.model.ExistingSavedVersionModel
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

enum class DocumentHistoryPayloadType {
  Created,
  Edited,
  Saved,
}

@JsonSubTypes(
    JsonSubTypes.Type(value = DocumentHistoryCreatedPayload::class, name = "Created"),
    JsonSubTypes.Type(value = DocumentHistoryEditedPayload::class, name = "Edited"),
    JsonSubTypes.Type(value = DocumentHistorySavedPayload::class, name = "Saved"),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface DocumentHistoryPayload {
  val createdBy: UserId
  val createdTime: Instant
  val type: DocumentHistoryPayloadType
}

@Schema(
    description =
        "History entry about the creation of the document. This is always the last element in " +
            "the reverse-chronological list of history events. It has the same information as " +
            "the createdBy and createdTime fields in DocumentPayload."
)
data class DocumentHistoryCreatedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
) : DocumentHistoryPayload {
  constructor(row: DocumentsRow) : this(row.createdBy!!, row.createdTime!!)

  override val type: DocumentHistoryPayloadType
    get() = DocumentHistoryPayloadType.Created
}

@Schema(
    description =
        "History entry about a document being edited. This represents the most recent edit by " +
            "the given user; if the same user edits the document multiple times in a row, only " +
            "the last edit will be listed in the history."
)
data class DocumentHistoryEditedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
) : DocumentHistoryPayload {
  constructor(model: EditHistoryModel) : this(model.createdBy, model.createdTime)

  override val type: DocumentHistoryPayloadType
    get() = DocumentHistoryPayloadType.Edited
}

@Schema(
    description =
        "History entry about a saved version of a document. The maxVariableValueId and " +
            "variableManifestId may be used to retrieve the contents of the saved version."
)
data class DocumentHistorySavedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
    val isSubmitted: Boolean,
    val maxVariableValueId: VariableValueId,
    val name: String,
    val variableManifestId: VariableManifestId,
    val versionId: DocumentSavedVersionId,
) : DocumentHistoryPayload {
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

  override val type: DocumentHistoryPayloadType
    get() = DocumentHistoryPayloadType.Saved
}
