package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.csvResponse
import com.terraformation.backend.api.writeNext
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.api.HasSearchFields
import com.terraformation.backend.search.api.HasSearchFilters
import com.terraformation.backend.search.api.HasSearchNode
import com.terraformation.backend.search.api.HasSortOrder
import com.terraformation.backend.search.api.SearchFilter
import com.terraformation.backend.search.api.SearchNodePayload
import com.terraformation.backend.search.api.SearchResponsePayload
import com.terraformation.backend.search.api.SearchSortOrderElement
import com.terraformation.backend.search.table.SearchTables
import com.terraformation.backend.seedbank.search.AccessionSearchService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.validation.constraints.NotEmpty
import javax.ws.rs.BadRequestException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/search")
@RestController
@SeedBankAppEndpoint
class AccessionSearchController(
    private val accessionSearchService: AccessionSearchService,
    private val clock: Clock,
    tables: SearchTables,
) {
  private val accessionsPrefix = SearchFieldPrefix(tables.accessions)

  @Operation(summary = "Searches for accessions based on filter criteria.")
  @PostMapping
  fun searchAccessions(
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
                                      """{ "fields": ["accessionNumber", "remainingQuantity", "remainingUnits"],
                                           "search": {
                                             "operation": "and", "children": [
                                               { "operation": "field", "field": "species", "values": ["Species Name"] },
                                               { "operation": "or", "children": [
                                                   { "operation": "field", "field": "remainingGrams", "type": "Range", "values": ["100 Milligrams", "200 Milligrams"] },
                                                   { "operation": "and", "children": [
                                                       { "operation": "field", "field": "remainingUnits", "values": ["Seeds"] },
                                                       { "operation": "field", "field": "remainingQuantity", "type": "Range", "values": ["30", "40"] } ] } ] } ] } }""")])])
      payload: SearchAccessionsRequestPayload
  ): SearchResponsePayload {
    return SearchResponsePayload(
        accessionSearchService.search(
            payload.facilityId,
            payload.getSearchFieldPaths(accessionsPrefix),
            payload.toSearchNode(accessionsPrefix),
            payload.getSearchSortFields(accessionsPrefix),
            payload.cursor,
            payload.count))
  }

  @ApiResponse(
      responseCode = "200",
      description = "Export succeeded.",
      content =
          [Content(mediaType = "text/csv", schema = Schema(type = "string", format = "binary"))])
  @Operation(summary = "Exports the results of a search as a downloadable CSV file.")
  @PostMapping("/export", produces = ["text/csv"])
  fun exportAccessions(
      @RequestBody payload: ExportAccessionsRequestPayload
  ): ResponseEntity<ByteArray> {
    val fields = payload.getSearchFieldPaths(accessionsPrefix)
    if (fields.any { it.isNested }) {
      throw BadRequestException("Nested fields are not supported for CSV export.")
    }

    val searchResults =
        accessionSearchService.search(
            payload.facilityId,
            fields,
            payload.toSearchNode(accessionsPrefix),
            payload.getSearchSortFields(accessionsPrefix))

    val dateAndTime =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now(clock))
    val filename = "seedbank-$dateAndTime.csv"

    val columnNames =
        payload.getSearchFieldPaths(accessionsPrefix).map { it.searchField.displayName }

    return csvResponse(filename, columnNames) { csvWriter ->
      searchResults.results.forEach { result ->
        csvWriter.writeNext(payload.fields.map { result[it] })
      }
    }
  }
}

data class SearchAccessionsRequestPayload(
    val facilityId: FacilityId,
    @NotEmpty override val fields: List<String>,
    override val sortOrder: List<SearchSortOrderElement>? = null,
    override val filters: List<SearchFilter>? = null,
    override val search: SearchNodePayload? = null,
    val cursor: String? = null,
    @Schema(
        defaultValue = "10",
    )
    val count: Int = 10
) : HasSearchFields, HasSearchNode, HasSortOrder, HasSearchFilters

data class ExportAccessionsRequestPayload(
    val facilityId: FacilityId,
    @NotEmpty override val fields: List<String>,
    override val sortOrder: List<SearchSortOrderElement>? = null,
    override val filters: List<SearchFilter>? = null,
    override val search: SearchNodePayload? = null,
) : HasSearchFields, HasSearchNode, HasSortOrder, HasSearchFilters
