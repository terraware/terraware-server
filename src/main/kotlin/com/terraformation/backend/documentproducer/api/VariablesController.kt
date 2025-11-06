package com.terraformation.backend.documentproducer.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.terraformation.backend.api.InternalEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.docprod.DependencyCondition
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.docprod.VariableId
import com.terraformation.backend.documentproducer.db.VariableStore
import com.terraformation.backend.documentproducer.model.DateVariable
import com.terraformation.backend.documentproducer.model.EmailVariable
import com.terraformation.backend.documentproducer.model.ImageVariable
import com.terraformation.backend.documentproducer.model.LinkVariable
import com.terraformation.backend.documentproducer.model.NumberVariable
import com.terraformation.backend.documentproducer.model.SectionVariable
import com.terraformation.backend.documentproducer.model.SelectOption
import com.terraformation.backend.documentproducer.model.SelectVariable
import com.terraformation.backend.documentproducer.model.TableColumn
import com.terraformation.backend.documentproducer.model.TableVariable
import com.terraformation.backend.documentproducer.model.TextVariable
import com.terraformation.backend.documentproducer.model.Variable
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.ws.rs.BadRequestException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@InternalEndpoint
@RequestMapping("/api/v1/document-producer/variables")
@RestController
class VariablesController(
    private val variableStore: VariableStore,
) {
  @Operation(
      summary = "List the available variables, optionally filtered by a document or deliverable.",
      description =
          "Variables returned for a document include all section hierarchies and variables " +
              "injected into section text.",
  )
  @GetMapping
  fun listVariables(
      @RequestParam deliverableId: DeliverableId?,
      @RequestParam documentId: DocumentId?,
      @Parameter(
          description =
              "If specified, return the definition of a specific variable given its stable ID. " +
                  "May be specified more than once to return multiple variables. deliverableId " +
                  "and documentId are ignored if this is specified."
      )
      @RequestParam
      stableId: List<StableId>?,
      @Parameter(
          description =
              "If specified, return the definition of a specific variable. May be specified more " +
                  "than once to return multiple variables. deliverableId, documentId, and " +
                  "stableId are ignored if this is specified."
      )
      @RequestParam
      variableId: List<VariableId>?,
  ): ListVariablesResponsePayload {
    if (deliverableId != null && documentId != null) {
      throw BadRequestException("Only Deliverable ID or Document ID can be provided, not both.")
    }

    val variables =
        if (!variableId.isNullOrEmpty()) {
          variableId.distinct().mapNotNull { variableStore.fetchVariableOrNull(it) }
        } else if (!stableId.isNullOrEmpty()) {
          stableId.distinct().mapNotNull { variableStore.fetchByStableId(it) }
        } else if (deliverableId != null) {
          variableStore.fetchDeliverableVariables(deliverableId)
        } else if (documentId != null) {
          variableStore.fetchManifestVariables(documentId) +
              variableStore.fetchUsedVariables(documentId)
        } else {
          variableStore.fetchAllNonSectionVariables()
        }

    return ListVariablesResponsePayload(variables.map { VariablePayload.of(it) })
  }
}

@JsonSubTypes(
    Type(value = DateVariablePayload::class, name = "Date"),
    Type(value = EmailVariablePayload::class, name = "Email"),
    Type(value = ImageVariablePayload::class, name = "Image"),
    Type(value = LinkVariablePayload::class, name = "Link"),
    Type(value = NumberVariablePayload::class, name = "Number"),
    Type(value = SectionVariablePayload::class, name = "Section"),
    Type(value = SelectVariablePayload::class, name = "Select"),
    Type(value = TableVariablePayload::class, name = "Table"),
    Type(value = TextVariablePayload::class, name = "Text"),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
sealed interface VariablePayload {
  @get:JsonIgnore val model: Variable

  val deliverableId: DeliverableId?
    get() = model.deliverablePositions.keys.firstOrNull()

  val deliverableQuestion: String?
    get() = model.deliverableQuestion

  val dependencyCondition: DependencyCondition?
    get() = model.dependencyCondition

  val dependencyValue: String?
    get() = model.dependencyValue

  val dependencyVariableStableId: StableId?
    get() = model.dependencyVariableStableId

  val description: String?
    get() = model.description

  val id: VariableId
    get() = model.id

  val internalOnly: Boolean
    get() = model.internalOnly

  val isList: Boolean
    get() = model.isList

  val isRequired: Boolean
    get() = model.isRequired

  val name: String
    get() = model.name

  val position: Int?
    get() = model.position

  val recommendedBy: Set<VariableId>?
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @ArraySchema(
        arraySchema = Schema(description = "IDs of sections that recommend this variable.")
    )
    get() = model.recommendedBy.toSet()

  val stableId: StableId
    get() = model.stableId

  val type
    get() = model.type

  companion object {
    fun of(model: Variable): VariablePayload {
      return when (model) {
        is DateVariable -> DateVariablePayload(model)
        is EmailVariable -> EmailVariablePayload(model)
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

data class EmailVariablePayload(override val model: EmailVariable) : VariablePayload

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
        arraySchema = Schema(description = "IDs of variables that this section recommends.")
    )
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
