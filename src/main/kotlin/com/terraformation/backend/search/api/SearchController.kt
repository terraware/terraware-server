package com.terraformation.backend.search.api

import com.terraformation.backend.api.SearchEndpoint
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.namespace.SearchFieldNamespaces
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.NotEmpty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/search")
@RestController
@SearchEndpoint
class SearchController(
    namespaces: SearchFieldNamespaces,
    private val searchService: SearchService
) {
  private val organizationsNamespace = namespaces.organizations

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
                                      """{ "prefix": "projects.sites.facilities.accessions",
                                           "fields": ["accessionNumber", "remainingQuantity", "remainingUnits"],
                                           "search": {
                                             "operation": "and", "children": [
                                               { "operation": "field", "field": "species", "values": ["Species Name"] },
                                               { "operation": "or", "children": [
                                                   { "operation": "field", "field": "remainingGrams", "type": "Range", "values": ["100 Milligrams", "200 Milligrams"] },
                                                   { "operation": "and", "children": [
                                                       { "operation": "field", "field": "remainingUnits", "values": ["Seeds"] },
                                                       { "operation": "field", "field": "remainingQuantity", "type": "Range", "values": ["30", "40"] } ] } ] } ] } }""")])])
      payload: SearchRequestPayload
  ): SearchResponsePayload {
    val namespace =
        if (payload.prefix != null) {
          organizationsNamespace.resolveNamespace(payload.prefix)
        } else {
          organizationsNamespace
        }

    val rootPrefix = SearchFieldPrefix(namespace)

    return SearchResponsePayload(
        searchService.search(
            rootPrefix,
            payload.fields.map { rootPrefix.resolve(it) },
            payload.toSearchNode(rootPrefix),
            payload.getSearchSortFields(rootPrefix),
            payload.cursor,
            payload.count))
  }
}

data class SearchRequestPayload(
    @Schema(
        description =
            "Prefix for field names. This determines how field names are interpreted, and also " +
                "how results are structured. Each element in the \"results\" array in the " +
                "response will be an instance of whatever entity the prefix points to. Always " +
                "evaluated starting from the \"organizations\" level. If not present, the search " +
                "will return a list of organizations.",
        example = "projects.sites.facilities.accessions")
    val prefix: String? = null,
    @NotEmpty
    @Schema(
        description =
            "List of fields to return. Field names should be relative to the prefix. They may " +
                "navigate the data hierarchy using '.' or '_' as delimiters.",
        example = """["processingStartDate","germinationTests.seedsSown","facility_name"]""")
    val fields: List<String>,
    @Schema(
        description =
            "How to sort the search results. This controls both the order of the top-level " +
                "results and the order of any lists of child objects.")
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
                           { "operation": "field", "field": "remainingQuantity", "type": "Range", "values": ["30", "40"] } ] } ] } ] }""")
    override val search: SearchNodePayload? = null,
    @Schema(
        description =
            "Maximum number of top-level search results to return. The system may impose a limit " +
                "on this value. A separate system-imposed limit may also be applied to lists of " +
                "child objects inside the top-level results.",
        defaultValue = "25",
    )
    val count: Int = 25,
    @Schema(
        description =
            "Starting point for search results. If present, a previous search will be continued " +
                "from where it left off. This should be the value of the cursor that was " +
                "returned in the response to a previous search.")
    val cursor: String? = null,
) : HasSearchNode, HasSortOrder
