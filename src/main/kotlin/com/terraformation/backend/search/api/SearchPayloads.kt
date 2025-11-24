package com.terraformation.backend.search.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.NotNode
import com.terraformation.backend.search.OrNode
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchFieldPath
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchNode
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

interface HasSearchFields {
  val fields: List<String>?

  fun getSearchFieldPaths(prefix: SearchFieldPrefix): List<SearchFieldPath> =
      fields?.map { prefix.resolve(it) } ?: emptyList()
}

interface HasSortOrder {
  val sortOrder: List<SearchSortOrderElement>?

  fun getSearchSortFields(prefix: SearchFieldPrefix): List<SearchSortField> =
      sortOrder?.map { it.toSearchSortField(prefix) } ?: emptyList()
}

interface HasSearchNode {
  val search: SearchNodePayload?

  fun toSearchNode(prefix: SearchFieldPrefix): SearchNode {
    return this.search?.toSearchNode(prefix) ?: NoConditionNode()
  }
}

data class PrefixedSearch(val prefix: String, val search: SearchNodePayload)

interface HasSearchCriteria : HasSearchNode {
  val filters: List<PrefixedSearch>?

  fun toSearchCriteria(prefix: SearchFieldPrefix): Map<SearchFieldPrefix, SearchNode> {
    val criteria = mutableMapOf(prefix to toSearchNode(prefix))
    filters?.forEach {
      prefix.relativeSublistPrefix(it.prefix)?.let { prefix ->
        criteria[prefix] = it.search.toSearchNode(prefix)
      }
    }
    return criteria
  }
}

data class SearchSortOrderElement(
    val field: String,
    @Schema(
        defaultValue = "Ascending",
    )
    val direction: SearchDirection?,
) {
  fun toSearchSortField(prefix: SearchFieldPrefix) =
      SearchSortField(prefix.resolve(field), direction ?: SearchDirection.Ascending)
}

@JsonSubTypes(
    JsonSubTypes.Type(name = "and", value = AndNodePayload::class),
    JsonSubTypes.Type(name = "field", value = FieldNodePayload::class),
    JsonSubTypes.Type(name = "not", value = NotNodePayload::class),
    JsonSubTypes.Type(name = "or", value = OrNodePayload::class),
)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "operation",
)
@Schema(
    description =
        "A search criterion. The search will return results that match this criterion. The " +
            "criterion can be composed of other search criteria to form arbitrary Boolean " +
            "search expressions. TYPESCRIPT-OVERRIDE-TYPE-WITH-ANY",
)
sealed interface SearchNodePayload {
  fun toSearchNode(prefix: SearchFieldPrefix): SearchNode
}

@JsonTypeName("or")
@Schema(
    description =
        "Search criterion that matches results that meet any of a set of other search criteria. " +
            "That is, if the list of children is x, y, and z, this will require x OR y OR z."
)
data class OrNodePayload(
    @ArraySchema(
        minItems = 1,
        arraySchema =
            Schema(description = "List of criteria at least one of which must be satisfied"),
    )
    @NotEmpty
    val children: List<SearchNodePayload>
) : SearchNodePayload {
  override fun toSearchNode(prefix: SearchFieldPrefix): SearchNode {
    return OrNode(children.map { it.toSearchNode(prefix) })
  }
}

@JsonTypeName("and")
@Schema(
    description =
        "Search criterion that matches results that meet all of a set of other search criteria. " +
            "That is, if the list of children is x, y, and z, this will require x AND y AND z."
)
data class AndNodePayload(
    @ArraySchema(
        minItems = 1,
        arraySchema = Schema(description = "List of criteria all of which must be satisfied"),
    )
    @NotEmpty
    val children: List<SearchNodePayload>
) : SearchNodePayload {
  override fun toSearchNode(prefix: SearchFieldPrefix): SearchNode {
    return AndNode(children.map { it.toSearchNode(prefix) })
  }
}

@JsonTypeName("not")
@Schema(
    description =
        "Search criterion that matches results that do not match a set of search criteria."
)
data class NotNodePayload(val child: SearchNodePayload) : SearchNodePayload {
  override fun toSearchNode(prefix: SearchFieldPrefix): SearchNode {
    return NotNode(child.toSearchNode(prefix))
  }
}

@JsonTypeName("field")
data class FieldNodePayload(
    val field: String,
    @ArraySchema(
        schema = Schema(nullable = true),
        minItems = 1,
        arraySchema =
            Schema(
                description =
                    "List of values to match. For exact, fuzzy and phrase match searches, a list " +
                        "of at least one value to search for; the list may include null to match " +
                        "accessions where the field does not have a value. For range searches, " +
                        "the list must contain exactly two values, the minimum and maximum; one " +
                        "of the values may be null to search for all values above a minimum or " +
                        "below a maximum."
            ),
    )
    @NotEmpty
    val values: List<String?>,
    val type: SearchFilterType = SearchFilterType.Exact,
) : SearchNodePayload {
  override fun toSearchNode(prefix: SearchFieldPrefix): SearchNode {
    return FieldNode(prefix.resolve(field), values, type)
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SearchResponsePayload(val results: List<Map<String, Any>>, val cursor: String?) {
  constructor(searchResults: SearchResults) : this(searchResults.results, searchResults.cursor)
}

data class FieldValuesPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "All the values this field could possibly have, whether or not any " +
                        "accessions have them. For fields that allow the user to enter arbitrary " +
                        "values, this is equivalent to querying the list of values without any " +
                        "filter criteria, that is, it's a list of all the user-entered values."
            )
    )
    val values: List<String?>,
    @Schema(
        description =
            "If true, the list of values is too long to return in its entirety and \"values\" is " +
                "a partial list."
    )
    val partial: Boolean,
)

data class SearchValuesResponsePayload(val results: Map<String, FieldValuesPayload>)

data class SearchCountResponsePayload(val count: Long)
