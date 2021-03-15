package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonInclude
import com.opencsv.CSVWriter
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.search.SearchDirection
import com.terraformation.seedbank.search.SearchField
import com.terraformation.seedbank.search.SearchFilter
import com.terraformation.seedbank.search.SearchResults
import com.terraformation.seedbank.search.SearchService
import com.terraformation.seedbank.search.SearchSortField
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.validation.constraints.NotEmpty
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/search")
@RestController
@SeedBankAppEndpoint
class SearchController(private val clock: Clock, private val searchService: SearchService) {
  @Operation(summary = "Searches for accessions based on filter criteria.")
  @PostMapping
  fun search(@RequestBody payload: SearchRequestPayload): SearchResponsePayload {
    return SearchResponsePayload(
        searchService.search(
            payload.fields,
            payload.filters ?: emptyList(),
            payload.searchSortFields ?: emptyList(),
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
  fun export(@RequestBody payload: ExportRequestPayload): ResponseEntity<ByteArray> {
    val searchResults =
        searchService.search(
            payload.fields, payload.filters ?: emptyList(), payload.searchSortFields ?: emptyList())
    return exportCsv(payload, searchResults)
  }

  private fun exportCsv(
      payload: ExportRequestPayload,
      searchResults: SearchResults
  ): ResponseEntity<ByteArray> {
    val dateAndTime =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now(clock))
    val filename = "seedbank-$dateAndTime.csv"
    val byteArrayOutputStream = ByteArrayOutputStream()

    // Write a UTF-8 BOM so Excel won't screw up the character encoding if there are non-ASCII
    // characters.
    byteArrayOutputStream.write(239)
    byteArrayOutputStream.write(187)
    byteArrayOutputStream.write(191)

    CSVWriter(OutputStreamWriter(byteArrayOutputStream, StandardCharsets.UTF_8)).use { csvWriter ->
      val header = payload.fields.map { it.displayName }.toTypedArray()
      val fieldNames = payload.fields.map { it.fieldName }
      csvWriter.writeNext(header, false)

      searchResults.results.forEach { result ->
        val values = fieldNames.map { fieldName -> result[fieldName] }.toTypedArray()
        csvWriter.writeNext(values, false)
      }
    }

    val value = byteArrayOutputStream.toByteArray()
    val headers = HttpHeaders()
    headers.contentLength = value.size.toLong()
    headers["Content-type"] = "text/csv;charset=UTF-8"
    headers.contentDisposition = ContentDisposition.attachment().filename(filename).build()

    return ResponseEntity(value, headers, HttpStatus.OK)
  }
}

private interface HasSortOrder {
  val sortOrder: List<SearchSortOrderElement>?
  val searchSortFields: List<SearchSortField>?
    @Hidden get() = sortOrder?.map { it.toSearchSortField() }
}

data class SearchRequestPayload(
    @NotEmpty val fields: List<SearchField<*>>,
    override val sortOrder: List<SearchSortOrderElement>? = null,
    val filters: List<SearchFilter>? = null,
    val cursor: String? = null,
    @Schema(defaultValue = "10") val count: Int = 10
) : HasSortOrder

data class ExportRequestPayload(
    @NotEmpty val fields: List<SearchField<*>>,
    override val sortOrder: List<SearchSortOrderElement>? = null,
    val filters: List<SearchFilter>? = null,
) : HasSortOrder

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SearchResponsePayload(val results: List<Map<String, String>>, val cursor: String?) {
  constructor(searchResults: SearchResults) : this(searchResults.results, searchResults.cursor)
}

data class SearchSortOrderElement(
    val field: SearchField<*>,
    @Schema(defaultValue = "Ascending") val direction: SearchDirection?
) {
  fun toSearchSortField() = SearchSortField(field, direction ?: SearchDirection.Ascending)
}
