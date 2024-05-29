package com.terraformation.pdd.variable.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.pdd.api.InternalEndpoint
import com.terraformation.pdd.api.SuccessResponsePayload
import com.terraformation.pdd.jooq.VariableId
import com.terraformation.pdd.jooq.VariableManifestId
import com.terraformation.pdd.variable.db.VariableStore
import com.terraformation.pdd.variable.model.DateVariable
import com.terraformation.pdd.variable.model.ImageVariable
import com.terraformation.pdd.variable.model.LinkVariable
import com.terraformation.pdd.variable.model.NumberVariable
import com.terraformation.pdd.variable.model.SectionVariable
import com.terraformation.pdd.variable.model.SelectOption
import com.terraformation.pdd.variable.model.SelectVariable
import com.terraformation.pdd.variable.model.TableColumn
import com.terraformation.pdd.variable.model.TableVariable
import com.terraformation.pdd.variable.model.TextVariable
import com.terraformation.pdd.variable.model.Variable
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/variables")
@RestController
class VariablesController(
    private val variableStore: VariableStore,
) {
  @GetMapping
  fun listVariables(@RequestParam manifestId: VariableManifestId): ListVariablesResponsePayload {
    val variables = variableStore.fetchManifestVariables(manifestId)

    return ListVariablesResponsePayload(variables.map { VariablePayload.of(it) })
  }
}

@JsonSubTypes(
    Type(value = DateVariablePayload::class, name = "Date"),
    Type(value = ImageVariablePayload::class, name = "Image"),
    Type(value = LinkVariablePayload::class, name = "Link"),
    Type(value = NumberVariablePayload::class, name = "Number"),
    Type(value = SectionVariablePayload::class, name = "Section"),
    Type(value = SelectVariablePayload::class, name = "Select"),
    Type(value = TableVariablePayload::class, name = "Table"),
    Type(value = TextVariablePayload::class, name = "Text"),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@Schema(
    discriminatorMapping =
        [
            DiscriminatorMapping(schema = DateVariablePayload::class, value = "Date"),
            DiscriminatorMapping(schema = ImageVariablePayload::class, value = "Image"),
            DiscriminatorMapping(schema = LinkVariablePayload::class, value = "Link"),
            DiscriminatorMapping(schema = NumberVariablePayload::class, value = "Number"),
            DiscriminatorMapping(schema = SectionVariablePayload::class, value = "Section"),
            DiscriminatorMapping(schema = SelectVariablePayload::class, value = "Select"),
            DiscriminatorMapping(schema = TableVariablePayload::class, value = "Table"),
            DiscriminatorMapping(schema = TextVariablePayload::class, value = "Text"),
        ])
interface VariablePayload {
  @get:JsonIgnore val model: Variable

  val description: String?
    get() = model.description

  val id: VariableId
    get() = model.id

  val isList: Boolean
    get() = model.isList

  val name: String
    get() = model.name

  val position: Int
    get() = model.position

  val recommendedBy: Set<VariableId>?
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @ArraySchema(
        arraySchema = Schema(description = "IDs of sections that recommend this variable."))
    get() = model.recommendedBy.toSet()

  val type
    get() = model.type

  companion object {
    fun of(model: Variable): VariablePayload {
      return when (model) {
        is DateVariable -> DateVariablePayload(model)
        is ImageVariable -> ImageVariablePayload(model)
        is LinkVariable -> LinkVariablePayload(model)
        is NumberVariable -> NumberVariablePayload(model)
        is SectionVariable -> SectionVariablePayload(model)
        is SelectVariable -> SelectVariablePayload(model)
        is TableVariable -> TableVariablePayload(model)
        is TextVariable -> TextVariablePayload(model)
      }
    }
  }
}

data class DateVariablePayload(override val model: DateVariable) : VariablePayload

data class ImageVariablePayload(override val model: ImageVariable) : VariablePayload

data class LinkVariablePayload(override val model: LinkVariable) : VariablePayload

data class NumberVariablePayload(override val model: NumberVariable) : VariablePayload {
  val decimalPlaces
    get() = model.decimalPlaces

  val maxValue
    get() = model.maxValue

  val minValue
    get() = model.minValue
}

data class SectionVariablePayload(override val model: SectionVariable) : VariablePayload {
  val children = model.children.map { SectionVariablePayload(it) }
  val recommends
    @ArraySchema(
        arraySchema = Schema(description = "IDs of variables that this section recommends."))
    get() = model.recommends

  val renderHeading
    get() = model.renderHeading
}

data class SelectOptionPayload(@get:JsonIgnore val model: SelectOption) {
  val id
    get() = model.id

  val name
    get() = model.name

  val description
    get() = model.description

  val renderedText
    get() = model.renderedText
}

data class SelectVariablePayload(override val model: SelectVariable) : VariablePayload {
  val isMultiple
    get() = model.isMultiple

  val options = model.options.map { SelectOptionPayload(it) }
}

data class TableColumnPayload(
    val isHeader: Boolean,
    val variable: VariablePayload,
) {
  constructor(model: TableColumn) : this(model.isHeader, VariablePayload.of(model.variable))
}

data class TableVariablePayload(
    override val model: TableVariable,
) : VariablePayload {
  val columns = model.columns.map { TableColumnPayload(it) }
  val tableStyle
    get() = model.tableStyle
}

data class TextVariablePayload(override val model: TextVariable) : VariablePayload {
  val textType
    get() = model.textType
}

data class ListVariablesResponsePayload(val variables: List<VariablePayload>) :
    SuccessResponsePayload
