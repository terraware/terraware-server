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
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

enum class PddHistoryPayloadType {
  Created,
  Edited,
  Saved
}

@JsonSubTypes(
    JsonSubTypes.Type(value = PddHistoryCreatedPayload::class, name = "Created"),
    JsonSubTypes.Type(value = PddHistoryEditedPayload::class, name = "Edited"),
    JsonSubTypes.Type(value = PddHistorySavedPayload::class, name = "Saved"),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@Schema(
    discriminatorMapping =
        [
            DiscriminatorMapping(schema = PddHistoryCreatedPayload::class, value = "Created"),
            DiscriminatorMapping(schema = PddHistoryEditedPayload::class, value = "Edited"),
            DiscriminatorMapping(schema = PddHistorySavedPayload::class, value = "Saved"),
        ],
    discriminatorProperty = "type")
sealed interface PddHistoryPayload {
  val createdBy: UserId
  val createdTime: Instant
  val type: PddHistoryPayloadType
}

@Schema(
    description =
        "History entry about the creation of the document. This is always the last element in " +
            "the reverse-chronological list of history events. It has the same information as " +
            "the createdBy and createdTime fields in PddPayload.")
data class PddHistoryCreatedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
) : PddHistoryPayload {
  constructor(row: DocumentsRow) : this(row.createdBy!!, row.createdTime!!)

  override val type: PddHistoryPayloadType
    get() = PddHistoryPayloadType.Created
}

@Schema(
    description =
        "History entry about a document being edited. This represents the most recent edit by " +
            "the given user; if the same user edits the document multiple times in a row, only " +
            "the last edit will be listed in the history.")
data class PddHistoryEditedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
) : PddHistoryPayload {
  constructor(model: EditHistoryModel) : this(model.createdBy, model.createdTime)

  override val type: PddHistoryPayloadType
    get() = PddHistoryPayloadType.Edited
}

@Schema(
    description =
        "History entry about a saved version of a document. The maxVariableValueId and " +
            "variableManifestId may be used to retrieve the contents of the saved version.")
data class PddHistorySavedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
    val isSubmitted: Boolean,
    val maxVariableValueId: VariableValueId,
    val name: String,
    val variableManifestId: VariableManifestId,
    val versionId: DocumentSavedVersionId,
) : PddHistoryPayload {
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

  override val type: PddHistoryPayloadType
    get() = PddHistoryPayloadType.Saved
}
