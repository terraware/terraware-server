package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.opencsv.CSVWriter
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.seedbank.search.AndNode
import com.terraformation.backend.seedbank.search.FieldNode
import com.terraformation.backend.seedbank.search.NoConditionNode
import com.terraformation.backend.seedbank.search.NotNode
import com.terraformation.backend.seedbank.search.OrNode
import com.terraformation.backend.seedbank.search.SearchDirection
import com.terraformation.backend.seedbank.search.SearchField
import com.terraformation.backend.seedbank.search.SearchFilterType
import com.terraformation.backend.seedbank.search.SearchNode
import com.terraformation.backend.seedbank.search.SearchResults
import com.terraformation.backend.seedbank.search.SearchService
import com.terraformation.backend.seedbank.search.SearchSortField
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.validation.constraints.NotEmpty
import javax.ws.rs.BadRequestException
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
                                      """{ "fields": ["accessionNumber", "remainingQuantity", "remainingUnits"],
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
    return SearchResponsePayload(
        searchService.search(
            payload.facilityId,
            payload.fields,
            payload.toSearchNode(),
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
    if (payload.fields.any { it.fieldName.contains('.') }) {
      throw BadRequestException("Nested fields are not supported for CSV export.")
    }

    val searchResults =
        searchService.search(
            payload.facilityId,
            payload.fields,
            payload.toSearchNode(),
            payload.searchSortFields ?: emptyList())
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
        val values = fieldNames.map { fieldName -> result[fieldName]?.toString() }.toTypedArray()
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

interface HasSearchNode {
  val filters: List<SearchFilter>?
  val search: SearchNodePayload?

  fun toSearchNode(): SearchNode {
    val filters = this.filters
    val search = this.search

    return when {
      search != null -> search.toSearchNode()
      filters.isNullOrEmpty() -> NoConditionNode()
      else -> AndNode(filters.map { FieldNode(it.field, it.values, it.type) })
    }
  }
}

data class SearchRequestPayload(
    val facilityId: FacilityId,
    @NotEmpty val fields: List<SearchField>,
    override val sortOrder: List<SearchSortOrderElement>? = null,
    override val filters: List<SearchFilter>? = null,
    override val search: SearchNodePayload? = null,
    val cursor: String? = null,
    @Schema(
        defaultValue = "10",
    )
    val count: Int = 10
) : HasSearchNode, HasSortOrder

data class ExportRequestPayload(
    val facilityId: FacilityId,
    @NotEmpty val fields: List<SearchField>,
    override val sortOrder: List<SearchSortOrderElement>? = null,
    override val filters: List<SearchFilter>? = null,
    override val search: SearchNodePayload? = null,
) : HasSearchNode, HasSortOrder

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SearchResponsePayload(val results: List<Map<String, Any>>, val cursor: String?) {
  constructor(searchResults: SearchResults) : this(searchResults.results, searchResults.cursor)
}

data class SearchSortOrderElement(
    val field: SearchField,
    @Schema(
        defaultValue = "Ascending",
    )
    val direction: SearchDirection?
) {
  fun toSearchSortField() = SearchSortField(field, direction ?: SearchDirection.Ascending)
}

@JsonSubTypes(
    JsonSubTypes.Type(name = "and", value = AndNodePayload::class),
    JsonSubTypes.Type(name = "field", value = FieldNodePayload::class),
    JsonSubTypes.Type(name = "not", value = NotNodePayload::class),
    JsonSubTypes.Type(name = "or", value = OrNodePayload::class),
)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "operation")
@Schema(
    description =
        "A search criterion. The search will return results that match this criterion. The " +
            "criterion can be composed of other search criteria to form arbitrary Boolean " +
            "search expressions. TYPESCRIPT-OVERRIDE-TYPE-WITH-ANY",
    oneOf =
        [
            AndNodePayload::class,
            FieldNodePayload::class,
            NotNodePayload::class,
            OrNodePayload::class],
    discriminatorMapping =
        [
            DiscriminatorMapping(value = "and", schema = AndNodePayload::class),
            DiscriminatorMapping(value = "field", schema = FieldNodePayload::class),
            DiscriminatorMapping(value = "not", schema = NotNodePayload::class),
            DiscriminatorMapping(value = "or", schema = NotNodePayload::class),
        ])
interface SearchNodePayload {
  fun toSearchNode(): SearchNode
}

@JsonTypeName("or")
@Schema(
    description =
        "Search criterion that matches results that meet any of a set of other search criteria. " +
            "That is, if the list of children is x, y, and z, this will require x OR y OR z.")
data class OrNodePayload(
    @ArraySchema(minItems = 1) @NotEmpty val children: List<SearchNodePayload>
) : SearchNodePayload {
  override fun toSearchNode(): SearchNode {
    return OrNode(children.map { it.toSearchNode() })
  }
}

@JsonTypeName("and")
@Schema(
    description =
        "Search criterion that matches results that meet all of a set of other search criteria. " +
            "That is, if the list of children is x, y, and z, this will require x AND y AND z.")
data class AndNodePayload(
    @ArraySchema(minItems = 1) @NotEmpty val children: List<SearchNodePayload>
) : SearchNodePayload {
  override fun toSearchNode(): SearchNode {
    return AndNode(children.map { it.toSearchNode() })
  }
}

@JsonTypeName("not")
@Schema(
    description =
        "Search criterion that matches results that do not match a set of search criteria.")
data class NotNodePayload(val child: SearchNodePayload) : SearchNodePayload {
  override fun toSearchNode(): SearchNode {
    return NotNode(child.toSearchNode())
  }
}

@JsonTypeName("field")
data class FieldNodePayload(
    val field: SearchField,
    @ArraySchema(
        schema = Schema(nullable = true),
        minItems = 1,
        arraySchema =
            Schema(
                description =
                    "List of values to match. For exact and fuzzy searches, a list of at least " +
                        "one value to search for; the list may include null to match accessions " +
                        "where the field does not have a value. For range searches, the list " +
                        "must contain exactly two values, the minimum and maximum; one of the " +
                        "values may be null to search for all values above a minimum or below a " +
                        "maximum."))
    @NotEmpty
    val values: List<String?>,
    val type: SearchFilterType = SearchFilterType.Exact
) : SearchNodePayload {
  override fun toSearchNode(): SearchNode {
    return FieldNode(field, values, type)
  }
}

/**
 * A filter criterion to use when searching for accessions.
 *
 * @see SearchService
 */
data class SearchFilter(
    val field: SearchField,
    @ArraySchema(
        schema = Schema(nullable = true),
        arraySchema =
            Schema(
                minLength = 1,
                description =
                    "List of values to match. For exact and fuzzy searches, a list of at least " +
                        "one value to search for; the list may include null to match accessions " +
                        "where the field does not have a value. For range searches, the list " +
                        "must contain exactly two values, the minimum and maximum; one of the " +
                        "values may be null to search for all values above a minimum or below a " +
                        "maximum."))
    @NotEmpty
    val values: List<String?>,
    val type: SearchFilterType = SearchFilterType.Exact
)
