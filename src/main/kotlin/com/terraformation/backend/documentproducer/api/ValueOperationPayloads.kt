package com.terraformation.backend.documentproducer.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.model.AppendValueOperation
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DeleteValueOperation
import com.terraformation.backend.documentproducer.model.ReplaceValuesOperation
import com.terraformation.backend.documentproducer.model.UpdateValueOperation
import com.terraformation.backend.documentproducer.model.ValueOperation
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema

enum class ValueOperationType {
  Append,
  Delete,
  Replace,
  Update,
}

@JsonSubTypes(
    JsonSubTypes.Type(value = AppendValueOperationPayload::class, name = "Append"),
    JsonSubTypes.Type(value = DeleteValueOperationPayload::class, name = "Delete"),
    JsonSubTypes.Type(value = ReplaceValuesOperationPayload::class, name = "Replace"),
    JsonSubTypes.Type(value = UpdateValueOperationPayload::class, name = "Update"),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "operation")
@Schema(
    description =
        "Supertype of the payloads that describe which operations to perform on a variable's " +
            "value(s). See the descriptions of the individual operations for details.",
    discriminatorMapping =
        [
            DiscriminatorMapping(schema = AppendValueOperationPayload::class, value = "Append"),
            DiscriminatorMapping(schema = DeleteValueOperationPayload::class, value = "Delete"),
            DiscriminatorMapping(schema = ReplaceValuesOperationPayload::class, value = "Replace"),
            DiscriminatorMapping(schema = UpdateValueOperationPayload::class, value = "Update"),
        ],
    discriminatorProperty = "operation",
)
sealed interface ValueOperationPayload {
  // Dummy property so the OpenAPI schema will include the enum values
  @get:JsonIgnore val operation: ValueOperationType

  fun getExistingValueId(): VariableValueId? = null

  fun <ID : VariableValueId?> toOperationModel(
      projectId: ProjectId,
      base: BaseVariableValueProperties<ID>? = null,
  ): ValueOperation
}

@Schema(
    description =
        "Operation that appends a new value to a variable. If the variable does not have an " +
            "existing value, creates the value with list position 0.\n" +
            "\n" +
            "If the variable has an existing value and it is NOT a list, replaces the existing " +
            "value. In this case, the new list position will be 0.\n" +
            "\n" +
            "If the variable has existing values and it IS a list, creates the value with a list " +
            "position 1 greater than the currently-highest position, that is, appends the value " +
            "to the list.\n" +
            "\n" +
            "If the variable is a table column and no rowValueId is specified, associates the " +
            "new value with the most recently appended row. You MUST append a row value before " +
            "appending the values of the columns."
)
data class AppendValueOperationPayload(
    @Schema(
        description =
            "If the variable is a table column and the new value should be appended to an " +
                "existing row, the existing row's value ID."
    )
    val rowValueId: VariableValueId? = null,
    val variableId: VariableId,
    val value: NewValuePayload,
) : ValueOperationPayload {
  override val operation: ValueOperationType
    get() = ValueOperationType.Append

  override fun <ID : VariableValueId?> toOperationModel(
      projectId: ProjectId,
      base: BaseVariableValueProperties<ID>?,
  ): ValueOperation {
    return AppendValueOperation(
        value.toValueModel(
            BaseVariableValueProperties(null, projectId, 0, variableId, value.citation, rowValueId)
        )
    )
  }
}

@Schema(
    description =
        "Operation that deletes a value from a variable. Deletion is non-destructive; this " +
            "actually creates a new value with its own value ID, where the new value is marked " +
            "as deleted. This \"is deleted\" value is included in incremental value query " +
            "results.\n" +
            "\n" +
            "If the variable is a list and there are other values with higher list positions, " +
            "the remaining items will be renumbered such that the list remains contiguously " +
            "numbered starting at 0.\n" +
            "\n" +
            "If the variable is a table, or in other words if the value is a table row, any " +
            "values associated with the row are also deleted. The row itself gets a new value " +
            "that is marked as deleted, and the new values that are created to delete the row's " +
            "contents are associated with this newly-created deleted row value."
)
data class DeleteValueOperationPayload(val valueId: VariableValueId) : ValueOperationPayload {
  override val operation: ValueOperationType
    get() = ValueOperationType.Delete

  override fun <ID : VariableValueId?> toOperationModel(
      projectId: ProjectId,
      base: BaseVariableValueProperties<ID>?,
  ): ValueOperation {
    return DeleteValueOperation(projectId, valueId)
  }

  override fun getExistingValueId() = valueId
}

@Schema(
    description =
        "Operation that replaces all the values of a variable with new ones. This is an " +
            "\"upsert\" operation: it replaces any existing values, or creates new values if " +
            "there weren't already any.\n" +
            "\n" +
            "This operation may not be used with table variables.\n" +
            "\n" +
            "If the variable is a list and previously had more values than are included in this " +
            "payload, the existing values with higher-numbered list positions are deleted.\n" +
            "\n" +
            "If the variable is not a list, it is invalid for this payload to include more than " +
            "one value."
)
data class ReplaceValuesOperationPayload(
    @Schema(
        description =
            "If the variable is a table column, the value ID of the row whose values should be " +
                "replaced."
    )
    val rowValueId: VariableValueId? = null,
    val variableId: VariableId,
    val values: List<NewValuePayload>,
) : ValueOperationPayload {
  override val operation: ValueOperationType
    get() = ValueOperationType.Replace

  override fun <ID : VariableValueId?> toOperationModel(
      projectId: ProjectId,
      base: BaseVariableValueProperties<ID>?,
  ): ValueOperation {
    return ReplaceValuesOperation(
        projectId,
        variableId,
        rowValueId,
        values.mapIndexed { index, valuePayload ->
          valuePayload.toValueModel(
              BaseVariableValueProperties(null, projectId, index, variableId, valuePayload.citation)
          )
        },
    )
  }
}

@Schema(
    description =
        "Operation that replaces a single existing value with a new one. The new value will " +
            "have the same list position as the existing one.\n" +
            "\n" +
            "This operation may not be used with table variables.\n" +
            "\n" +
            "If the variable is a table column, the new value will be contained in the same row " +
            "as the existing one."
)
data class UpdateValueOperationPayload(
    val valueId: VariableValueId,
    val value: NewValuePayload,
) : ValueOperationPayload {
  override val operation: ValueOperationType
    get() = ValueOperationType.Update

  override fun <ID : VariableValueId?> toOperationModel(
      projectId: ProjectId,
      base: BaseVariableValueProperties<ID>?,
  ): ValueOperation {
    if (base == null) {
      throw IllegalArgumentException("No existing value with $valueId found")
    }
    return UpdateValueOperation(
        value.toValueModel(
            BaseVariableValueProperties(
                valueId,
                projectId,
                base.listPosition,
                base.variableId,
                value.citation,
            )
        )
    )
  }

  override fun getExistingValueId() = valueId
}
