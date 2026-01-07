package com.terraformation.backend.search.api

import com.terraformation.backend.api.SearchEndpoint
import com.terraformation.backend.api.csvResponse
import com.terraformation.backend.api.writeNext
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.search.SearchFieldNotExportableException
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.constraints.NotEmpty
import jakarta.ws.rs.BadRequestException
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/search")
@RestController
@SearchEndpoint
class SearchController(
    private val clock: Clock,
    private val messages: Messages,
    private val searchService: SearchService,
    private val searchTables: SearchTables,
) {
  private val organizationsTable = searchTables.organizations

  @Operation(summary = "Searches for data matching a supplied set of search criteria.")
  @PostMapping
  fun search(
      @RequestBody
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
          content =
              [
                  Content(
                      examples =
                          [
                              ExampleObject(
                                  name = "example1",
                                  description =
                                      "Search for all accessions of species \"Species Name\" " +
                                          "whose remaining seeds weigh between 100 and 200 " +
                                          "milligrams or that have between 30 and 40 seeds " +
                                          "remaining.",
                                  // This value is parsed by the schema generator and rendered
                                  // as JSON or YAML, so its formatting is irrelevant.
                                  value =
                                      """{ "prefix": "accessions",
                                           "fields": ["accessionNumber", "remainingQuantity", "remainingUnits"],
                                           "search": {
                                             "operation": "and", "children": [
                                               { "operation": "field", "field": "species", "values": ["Species Name"] },
                                               { "operation": "or", "children": [
                                                   { "operation": "field", "field": "remainingGrams", "type": "Range", "values": ["100 Milligrams", "200 Milligrams"] },
                                                   { "operation": "and", "children": [
                                                       { "operation": "field", "field": "remainingUnits", "values": ["Seeds"] },
                                                       { "operation": "field", "field": "remainingQuantity", "type": "Range", "values": ["30", "40"] } ] } ] } ] } }""",
                              )
                          ]
                  )
              ]
      )
      payload: SearchRequestPayload
  ): SearchResponsePayload {
    val rootPrefix = resolvePrefix(payload.prefix)
    val count = if (payload.count > 0) payload.count else Int.MAX_VALUE

    validateFilterPrefixes(payload, rootPrefix)

    return SearchResponsePayload(
        searchService.search(
            rootPrefix,
            payload.fields.map { rootPrefix.resolve(it) },
            payload.toSearchCriteria(rootPrefix),
            payload.getSearchSortFields(rootPrefix),
            payload.cursor,
            count,
        )
    )
  }

  @Operation(
      summary = "Get the total count of values matching a set of search criteria.",
      description =
          "Note that fields, sortOrder, cursor, and count in the payload are unused in the count query and thus can be included or left out.",
  )
  @PostMapping("/count")
  fun searchCount(@RequestBody payload: SearchRequestPayload): SearchCountResponsePayload {
    val rootPrefix = resolvePrefix(payload.prefix)

    return SearchCountResponsePayload(
        searchService.searchCount(rootPrefix, payload.toSearchCriteria(rootPrefix))
    )
  }

  @ApiResponse(
      responseCode = "200",
      content =
          [Content(mediaType = "text/csv", schema = Schema(type = "string", format = "binary"))],
  )
  @Operation(
      summary = "Exports selected fields from data matching a set of search criteria.",
      description =
          "If a sublist field has multiple values, they are separated with line breaks in the " +
              "exported file.",
  )
  @PostMapping(produces = ["text/csv"])
  fun export(@RequestBody payload: SearchRequestPayload): ResponseEntity<ByteArray> {
    val rootPrefix = resolvePrefix(payload.prefix)
    val count = if (payload.count > 0) payload.count else Int.MAX_VALUE
    val fields = payload.fields.map { rootPrefix.resolve(it) }

    val searchResults =
        searchService.search(
            rootPrefix,
            fields,
            mapOf(rootPrefix to payload.toSearchNode(rootPrefix)),
            payload.getSearchSortFields(rootPrefix),
            payload.cursor,
            count,
        )

    val dateAndTime =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now(clock))
    val filename = "terraware-$dateAndTime.csv"

    val columnNames =
        payload.getSearchFieldPaths(rootPrefix).map { fieldPath ->
          if (fieldPath.searchField.exportable) fieldPath.searchField.getDisplayName(messages)
          else throw SearchFieldNotExportableException(fieldPath.searchField.fieldName)
        }

    return csvResponse(filename, columnNames) { csvWriter ->
      searchResults.flattenForCsv().results.forEach { result ->
        csvWriter.writeNext(payload.fields.map { result[it] })
      }
    }
  }

  @Operation(summary = "Search for distinct values from data matching a set of search criteria.")
  @PostMapping("/values")
  fun searchDistinctValues(
      @RequestBody payload: SearchRequestPayload
  ): SearchValuesResponsePayload {
    val rootPrefix = resolvePrefix(payload.prefix)

    val count = if (payload.count > 0) payload.count else Int.MAX_VALUE
    val fields = payload.fields.map { rootPrefix.resolve(it) }
    if (fields.any { it.isNested }) {
      throw BadRequestException("Cannot list values of nested fields. Consider flattened fields. ")
    }

    return SearchValuesResponsePayload(
        fields.associateBy(
            { it.toString() },
            { searchField ->
              val fetchResult =
                  searchService.fetchValues(
                      rootPrefix,
                      searchField,
                      mapOf(rootPrefix to payload.toSearchNode(rootPrefix)),
                      payload.cursor,
                      count,
                  )
              FieldValuesPayload(fetchResult, fetchResult.size > count)
            },
        )
    )
  }

  private fun resolvePrefix(prefix: String?): SearchFieldPrefix {
    val table =
        if (prefix != null) {
          searchTables[prefix] ?: organizationsTable.resolveTable(prefix)
        } else {
          organizationsTable
        }

    return SearchFieldPrefix(table)
  }

  private fun validateFilterPrefixes(payload: SearchRequestPayload, rootPrefix: SearchFieldPrefix) {
    payload.filters?.forEach { filter ->
      rootPrefix.relativeSublistPrefix(filter.prefix)
          ?: throw IllegalArgumentException(
              "Filter prefix ${filter.prefix} is not a sublist of ${payload.prefix}"
          )
    }
  }
}

data class SearchRequestPayload(
    @Schema(
        description =
            "Prefix for field names. This determines how field names are interpreted, and also " +
                "how results are structured. Each element in the \"results\" array in the " +
                "response will be an instance of whatever entity the prefix points to. This is " +
                "the name of a search table.",
        defaultValue = "organizations",
        example = "accessions",
    )
    val prefix: String? = null,
    @NotEmpty
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "List of fields to return. Field names should be relative to the prefix. " +
                        "They may navigate the data hierarchy using '.' or '_' as delimiters.",
                example =
                    """["processingStartDate","viabilityTests.seedsTested","facility_name"]""",
            )
    )
    override val fields: List<String>,
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "How to sort the search results. This controls both the order of the " +
                        "top-level results and the order of any lists of child objects."
            )
    )
    override val sortOrder: List<SearchSortOrderElement>? = null,
    @Schema(
        description =
            "Search criteria to apply. Determines which prefix-level results are returned. If " +
                "you search on a field in a child object, you will get the prefix-level objects " +
                "that have at least one child matching your filter, but you will get the full " +
                "list of children (both matching the filter and not) for each of those " +
                "prefix-level objects.",
        example =
            // This value is parsed by the schema generator and rendered as JSON or YAML, so its
            // formatting is irrelevant.
            """{ "operation": "and", "children": [
                   { "operation": "field", "field": "species", "values": ["Species Name"] },
                   { "operation": "or", "children": [
                       { "operation": "field", "field": "remainingGrams", "type": "Range", "values": ["100 Milligrams", "200 Milligrams"] },
                       { "operation": "and", "children": [
                           { "operation": "field", "field": "remainingUnits", "values": ["Seeds"] },
                           { "operation": "field", "field": "remainingQuantity", "type": "Range", "values": ["30", "40"] } ] } ] } ] }""",
    )
    override val search: SearchNodePayload? = null,
    @Schema(
        description =
            "Search criteria to apply, only to the specified prefix. If the prefix is an empty " +
                "string, apply the search to all results. If prefix is a sublist (no matter how " +
                "nested), apply the search to the sublist results without affecting the top level " +
                "results.",
        example =
            // This value is parsed by the schema generator and rendered as JSON or YAML, so its
            // formatting is irrelevant.
            """[
                  { "prefix": "species", "search": { "operation": "field", "field": "name", "values": ["Species Name"] } },
                  { "prefix": "viabilityTestResults", "search": { "operation": "field", "field": "seedsGerminated", "type": "Range", "values": ["30", "40"] } } ]""",
    )
    override val filters: List<PrefixedSearch>? = null,
    @Schema(
        description =
            "Maximum number of top-level search results to return. The system may impose a limit " +
                "on this value. A separate system-imposed limit may also be applied to lists of " +
                "child objects inside the top-level results. Use a value of 0 to return the " +
                "maximum number of allowed results.",
        defaultValue = "25",
    )
    val count: Int = 25,
    @Schema(
        description =
            "Starting point for search results. If present, a previous search will be continued " +
                "from where it left off. This should be the value of the cursor that was " +
                "returned in the response to a previous search."
    )
    val cursor: String? = null,
) : HasSearchFields, HasSearchNode, HasSortOrder, HasSearchCriteria
