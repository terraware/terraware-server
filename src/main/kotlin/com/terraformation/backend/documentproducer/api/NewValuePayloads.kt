package com.terraformation.backend.documentproducer.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.backend.api.AllowBlankString
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.VariableValueId
import com.terraformation.backend.documentproducer.model.BaseVariableValueProperties
import com.terraformation.backend.documentproducer.model.DateValue
import com.terraformation.backend.documentproducer.model.EmailValue
import com.terraformation.backend.documentproducer.model.ImageValue
import com.terraformation.backend.documentproducer.model.ImageValueDetails
import com.terraformation.backend.documentproducer.model.LinkValue
import com.terraformation.backend.documentproducer.model.LinkValueDetails
import com.terraformation.backend.documentproducer.model.NumberValue
import com.terraformation.backend.documentproducer.model.SectionValue
import com.terraformation.backend.documentproducer.model.SectionValueText
import com.terraformation.backend.documentproducer.model.SectionValueVariable
import com.terraformation.backend.documentproducer.model.SelectValue
import com.terraformation.backend.documentproducer.model.TableValue
import com.terraformation.backend.documentproducer.model.TextValue
import com.terraformation.backend.documentproducer.model.VariableValue
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate

@JsonSubTypes(
    JsonSubTypes.Type(value = NewDateValuePayload::class, name = "Date"),
    JsonSubTypes.Type(value = NewEmailValuePayload::class, name = "Email"),
    JsonSubTypes.Type(value = NewImageValuePayload::class, name = "Image"),
    JsonSubTypes.Type(value = NewLinkValuePayload::class, name = "Link"),
    JsonSubTypes.Type(value = NewNumberValuePayload::class, name = "Number"),
    JsonSubTypes.Type(value = NewSectionTextValuePayload::class, name = "SectionText"),
    JsonSubTypes.Type(value = NewSectionVariableValuePayload::class, name = "SectionVariable"),
    JsonSubTypes.Type(value = NewSelectValuePayload::class, name = "Select"),
    JsonSubTypes.Type(value = NewTableValuePayload::class, name = "Table"),
    JsonSubTypes.Type(value = NewTextValuePayload::class, name = "Text"),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@Schema(
    description =
        "Supertype for payloads that represent new variable values. See the descriptions of " +
            "individual payload types for more details."
)
sealed interface NewValuePayload {
  @get:JsonIgnore val citation: String?

  // Dummy property so the OpenAPI schema will include the enum values
  @get:JsonIgnore val type: VariableValuePayloadType

  fun <ID : VariableValueId?> toValueModel(
      base: BaseVariableValueProperties<ID>
  ): VariableValue<ID, *>
}

data class NewDateValuePayload(
    @get:JsonIgnore(false) override val citation: String?,
    val dateValue: LocalDate,
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.Date

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      DateValue(base, dateValue)
}

data class NewEmailValuePayload(
    @get:JsonIgnore(false) override val citation: String?,
    val emailValue: String,
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.Email

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      EmailValue(base, emailValue)
}

@Schema(
    description =
        "Updated metadata about an image value. May only be used in Update operations, and " +
            "cannot be used to replace the actual image data."
)
data class NewImageValuePayload(
    @get:JsonIgnore(false) override val citation: String?,
    val caption: String?,
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.Image

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      ImageValue(base, ImageValueDetails(caption, FileId(0)))
}

data class NewLinkValuePayload(
    @get:JsonIgnore(false) override val citation: String?,
    val url: URI,
    val title: String?,
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.Link

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      LinkValue(base, LinkValueDetails(url, title))
}

data class NewNumberValuePayload(
    @get:JsonIgnore(false) override val citation: String?,
    val numberValue: BigDecimal,
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.Number

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      NumberValue(base, numberValue)
}

data class NewSelectValuePayload(
    @get:JsonIgnore(false) override val citation: String?,
    val optionIds: Set<VariableSelectOptionId>,
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.Select

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      SelectValue(base, optionIds)
}

data class NewSectionTextValuePayload(
    @get:JsonIgnore(false)
    @Schema(
        description =
            "Citation for this chunk of text. If you want text with multiple citations at " +
                "different positions, you can split it into multiple text values and put a " +
                "citation on each of them."
    )
    override val citation: String?,
    @AllowBlankString val textValue: String,
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.SectionText

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      SectionValue(base, SectionValueText(textValue))
}

data class NewSectionVariableValuePayload(
    val variableId: VariableId,
    val usageType: VariableUsageType,
    val displayStyle: VariableInjectionDisplayStyle? = null,
) : NewValuePayload {
  override val citation: String?
    get() = null

  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.SectionVariable

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      SectionValue(base, SectionValueVariable(variableId, usageType, displayStyle))
}

data class NewTableValuePayload(
    @get:JsonIgnore(false)
    @Schema(
        description =
            "Citations on table values can be used if you want a citation that is associated " +
                "with the table as a whole rather than with individual cells, or if you want a " +
                "citation on an empty table: append a row with no column values but with a " +
                "citation."
    )
    override val citation: String?
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.Table

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      TableValue(base)

  override fun equals(other: Any?) = other is NewTableValuePayload

  override fun hashCode() = javaClass.hashCode()
}

data class NewTextValuePayload(
    @get:JsonIgnore(false) override val citation: String?,
    val textValue: String,
) : NewValuePayload {
  override val type: VariableValuePayloadType
    get() = VariableValuePayloadType.Text

  override fun <ID : VariableValueId?> toValueModel(base: BaseVariableValueProperties<ID>) =
      TextValue(base, textValue)
}
