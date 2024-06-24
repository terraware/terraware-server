package com.terraformation.backend.documentproducer.model

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.db.docprod.VariableInjectionDisplayStyle
import com.terraformation.backend.db.docprod.VariableSelectOptionId
import com.terraformation.backend.db.docprod.VariableType
import com.terraformation.backend.db.docprod.VariableUsageType
import com.terraformation.backend.db.docprod.VariableValueId
import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate

interface BaseVariableValue<ID : VariableValueId?> {
  val id: ID
  val projectId: ProjectId
  val listPosition: Int
  val variableId: VariableId
  val citation: String?

  /**
   * If this variable is a column in a table, the ID of the value representing its containing row.
   * Null for values of standalone variables.
   */
  val rowValueId: VariableValueId?
}

data class BaseVariableValueProperties<ID : VariableValueId?>(
    override val id: ID,
    override val projectId: ProjectId,
    override val listPosition: Int,
    override val variableId: VariableId,
    override val citation: String?,
    override val rowValueId: VariableValueId? = null,
) : BaseVariableValue<ID>

sealed interface VariableValue<ID : VariableValueId?, V> : BaseVariableValue<ID> {
  val type: VariableType
  val value: V
}

data class DateValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
    override val value: LocalDate,
) : VariableValue<ID, LocalDate>, BaseVariableValue<ID> by base {
  override val type: VariableType
    get() = VariableType.Date
}

data class DeletedValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
    override val type: VariableType,
) : VariableValue<ID, Nothing?>, BaseVariableValue<ID> by base {
  override val citation: String?
    get() = null

  override val value: Nothing?
    get() = null
}

data class ImageValueDetails(
    val caption: String?,
    val fileId: FileId,
)

data class ImageValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
    override val value: ImageValueDetails,
) : VariableValue<ID, ImageValueDetails>, BaseVariableValue<ID> by base {
  override val type: VariableType
    get() = VariableType.Image
}

data class LinkValueDetails(
    val url: URI,
    val title: String?,
)

data class LinkValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
    override val value: LinkValueDetails,
) : VariableValue<ID, LinkValueDetails>, BaseVariableValue<ID> by base {
  override val type: VariableType
    get() = VariableType.Link
}

data class NumberValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
    override val value: BigDecimal,
) : VariableValue<ID, BigDecimal>, BaseVariableValue<ID> by base {
  override val type: VariableType
    get() = VariableType.Number
}

sealed interface SectionValueFragment

data class SectionValueText(val textValue: String) : SectionValueFragment

data class SectionValueVariable(
    val usedVariableId: VariableId,
    val usageType: VariableUsageType,
    val displayStyle: VariableInjectionDisplayStyle?,
) : SectionValueFragment

data class SectionValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
    override val value: SectionValueFragment,
) : VariableValue<ID, SectionValueFragment>, BaseVariableValue<ID> by base {
  override val type: VariableType
    get() = VariableType.Section
}

data class SelectValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
    override val value: Set<VariableSelectOptionId>,
) : VariableValue<ID, Set<VariableSelectOptionId>>, BaseVariableValue<ID> by base {
  override val type: VariableType
    get() = VariableType.Select
}

/** A table "value" is just a container for column values and has no content of its own. */
data class TableValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
) : VariableValue<ID, Nothing?>, BaseVariableValue<ID> by base {
  override val type: VariableType
    get() = VariableType.Table

  override val value: Nothing?
    get() = null
}

data class TextValue<ID : VariableValueId?>(
    private val base: BaseVariableValueProperties<ID>,
    override val value: String,
) : VariableValue<ID, String>, BaseVariableValue<ID> by base {
  override val type: VariableType
    get() = VariableType.Text
}

typealias ExistingDateValue = DateValue<VariableValueId>

typealias ExistingDeletedValue = DeletedValue<VariableValueId>

typealias ExistingImageValue = ImageValue<VariableValueId>

typealias ExistingLinkValue = LinkValue<VariableValueId>

typealias ExistingNumberValue = NumberValue<VariableValueId>

typealias ExistingSectionValue = SectionValue<VariableValueId>

typealias ExistingSelectValue = SelectValue<VariableValueId>

typealias ExistingTableValue = TableValue<VariableValueId>

typealias ExistingTextValue = TextValue<VariableValueId>

typealias ExistingValue = VariableValue<VariableValueId, *>

typealias NewDateValue = DateValue<Nothing?>

typealias NewImageValue = ImageValue<Nothing?>

typealias NewLinkValue = LinkValue<Nothing?>

typealias NewNumberValue = NumberValue<Nothing?>

typealias NewSectionValue = SectionValue<Nothing?>

typealias NewSelectValue = SelectValue<Nothing?>

typealias NewTableValue = TableValue<Nothing?>

typealias NewTextValue = TextValue<Nothing?>

typealias NewValue = VariableValue<Nothing?, *>
