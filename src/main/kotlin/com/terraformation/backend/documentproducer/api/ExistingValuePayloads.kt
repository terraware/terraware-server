package com.terraformation.backend.documentproducer.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.db.docprod.VariableWorkflowStatus
import com.terraformation.backend.documentproducer.model.DateValue
import com.terraformation.backend.documentproducer.model.DeletedValue
import com.terraformation.backend.documentproducer.model.EmailValue
import com.terraformation.backend.documentproducer.model.ExistingDateValue
import com.terraformation.backend.documentproducer.model.ExistingDeletedValue
import com.terraformation.backend.documentproducer.model.ExistingEmailValue
import com.terraformation.backend.documentproducer.model.ExistingImageValue
import com.terraformation.backend.documentproducer.model.ExistingLinkValue
import com.terraformation.backend.documentproducer.model.ExistingNumberValue
import com.terraformation.backend.documentproducer.model.ExistingSectionValue
import com.terraformation.backend.documentproducer.model.ExistingSelectValue
import com.terraformation.backend.documentproducer.model.ExistingTableValue
import com.terraformation.backend.documentproducer.model.ExistingTextValue
import com.terraformation.backend.documentproducer.model.ExistingValue
import com.terraformation.backend.documentproducer.model.ImageValue
import com.terraformation.backend.documentproducer.model.LinkValue
import com.terraformation.backend.documentproducer.model.NumberValue
import com.terraformation.backend.documentproducer.model.SectionValue
import com.terraformation.backend.documentproducer.model.SectionValueText
import com.terraformation.backend.documentproducer.model.SectionValueVariable
import com.terraformation.backend.documentproducer.model.SelectValue
import com.terraformation.backend.documentproducer.model.TableValue
import com.terraformation.backend.documentproducer.model.TextValue
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate

enum class ExistingValuePayloadType {
  Date,
  Deleted,
  Email,
  Image,
  Link,
  Number,
  SectionText,
  SectionVariable,
  Select,
  Table,
  Text,
}

@JsonSubTypes(
    JsonSubTypes.Type(value = ExistingDateValuePayload::class, name = "Date"),
    JsonSubTypes.Type(value = ExistingDeletedValuePayload::class, name = "Deleted"),
    JsonSubTypes.Type(value = ExistingEmailValuePayload::class, name = "Email"),
    JsonSubTypes.Type(value = ExistingLinkValuePayload::class, name = "Link"),
    JsonSubTypes.Type(value = ExistingImageValuePayload::class, name = "Image"),
    JsonSubTypes.Type(value = ExistingNumberValuePayload::class, name = "Number"),
    JsonSubTypes.Type(value = ExistingSectionTextValuePayload::class, name = "SectionText"),
    JsonSubTypes.Type(value = ExistingSectionVariableValuePayload::class, name = "SectionVariable"),
    JsonSubTypes.Type(value = ExistingSelectValuePayload::class, name = "Select"),
    JsonSubTypes.Type(value = ExistingTableValuePayload::class, name = "Table"),
    JsonSubTypes.Type(value = ExistingTextValuePayload::class, name = "Text"),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface ExistingValuePayload {
  val id: VariableValueId
  val listPosition: Int
  val citation: String?
  val type: ExistingValuePayloadType

  companion object {
    fun of(model: ExistingValue): ExistingValuePayload {
      return when (model) {
        is DateValue -> ExistingDateValuePayload(model)
        is DeletedValue -> ExistingDeletedValuePayload(model)
        is EmailValue -> ExistingEmailValuePayload(model)
        is ImageValue -> ExistingImageValuePayload(model)
        is LinkValue -> ExistingLinkValuePayload(model)
        is NumberValue -> ExistingNumberValuePayload(model)
        is SectionValue ->
            when (model.value) {
              is SectionValueText -> ExistingSectionTextValuePayload(model)
              is SectionValueVariable -> ExistingSectionVariableValuePayload(model)
            }
        is SelectValue -> ExistingSelectValuePayload(model)
        is TableValue -> ExistingTableValuePayload(model)
        is TextValue -> ExistingTextValuePayload(model)
      }
    }
  }
}

data class ExistingDateValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
    val dateValue: LocalDate,
) : ExistingValuePayload {
  constructor(
      model: ExistingDateValue
  ) : this(model.id, model.listPosition, model.citation, model.value)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Date
}

@Schema(
    description =
        "Represents the deletion of an earlier value at the same location. This is only included " +
            "when you are querying for incremental changes to a document's values."
)
data class ExistingDeletedValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
) : ExistingValuePayload {
  constructor(model: ExistingDeletedValue) : this(model.id, model.listPosition)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Deleted

  override val citation: String?
    @JsonIgnore get() = null
}

data class ExistingEmailValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
    val emailValue: String,
) : ExistingValuePayload {
  constructor(
      model: ExistingEmailValue
  ) : this(model.id, model.listPosition, model.citation, model.value)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Email
}

@Schema(
    description =
        "Metadata about an image. The actual image data (e.g., the JPEG or PNG file) must be " +
            "retrieved in a separate request using the value ID in this payload."
)
data class ExistingImageValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
    val caption: String?,
) : ExistingValuePayload {
  constructor(
      model: ExistingImageValue
  ) : this(model.id, model.listPosition, model.citation, model.value.caption)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Image
}

data class ExistingLinkValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
    val url: URI,
    val title: String?,
) : ExistingValuePayload {
  constructor(
      model: ExistingLinkValue
  ) : this(model.id, model.listPosition, model.citation, model.value.url, model.value.title)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Link
}

data class ExistingNumberValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
    val numberValue: BigDecimal,
) : ExistingValuePayload {
  constructor(
      model: ExistingNumberValue
  ) : this(model.id, model.listPosition, model.citation, model.value)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Number
}

data class ExistingSectionTextValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
    val textValue: String,
) : ExistingValuePayload {
  constructor(
      model: ExistingSectionValue
  ) : this(
      model.id,
      model.listPosition,
      model.citation,
      (model.value as SectionValueText).textValue,
  )

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.SectionText
}

data class ExistingSectionVariableValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    val variableId: VariableId,
    val usageType: VariableUsageType,
    val displayStyle: VariableInjectionDisplayStyle? = null,
) : ExistingValuePayload {
  constructor(
      model: ExistingSectionValue
  ) : this(
      model.id,
      model.listPosition,
      (model.value as SectionValueVariable).usedVariableId,
      model.value.usageType,
      model.value.displayStyle,
  )

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.SectionVariable

  override val citation: String?
    @JsonIgnore
    @Schema(
        description =
            "Ignored for section variable values because the referenced variable can already " +
                "have a citation."
    )
    get() = null
}

data class ExistingSelectValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
    val optionValues: Set<VariableSelectOptionId>,
) : ExistingValuePayload {
  constructor(
      model: ExistingSelectValue
  ) : this(model.id, model.listPosition, model.citation, model.value)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Select
}

@Schema(
    description =
        "A row in a table. Each row has its own value ID. ExistingVariableValuesPayload includes " +
            "this ID for values of variables that are defined as columns of a table."
)
data class ExistingTableValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
) : ExistingValuePayload {
  constructor(model: ExistingTableValue) : this(model.id, model.listPosition, model.citation)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Table
}

data class ExistingTextValuePayload(
    override val id: VariableValueId,
    override val listPosition: Int,
    override val citation: String?,
    val textValue: String,
) : ExistingValuePayload {
  constructor(
      model: ExistingTextValue
  ) : this(model.id, model.listPosition, model.citation, model.value)

  override val type: ExistingValuePayloadType
    get() = ExistingValuePayloadType.Text
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExistingVariableValuesPayload(
    val variableId: VariableId,
    @Schema(description = "If this is the value of a table cell, the ID of the row it's part of.")
    val rowValueId: VariableValueId? = null,
    @Schema(
        description = "User-visible feedback from reviewer. Not populated for table cell values."
    )
    val feedback: String?,
    @Schema(
        description =
            "Internal comment from reviewer. Only populated if the current user has permission " +
                "to read internal comments. Not populated for table cell values."
    )
    val internalComment: String?,
    @Schema(description = "Current status of this variable. Not populated for table cell values.")
    val status: VariableWorkflowStatus?,
    @Schema(
        description =
            "Values of this variable or this table cell. When getting the full set of values for " +
                "a document, this will be the complete list of this variable's values in order " +
                "of list position. When getting incremental changes to a document, this is only " +
                "the items that have changed, and existing items won't be present. For example, " +
                "if a variable is a list and has 3 values, and a fourth value is added, the " +
                "incremental list of values in this payload will have one item and its list " +
                "position will be 3 (since lists are 0-indexed)."
    )
    val values: List<ExistingValuePayload>,
)
