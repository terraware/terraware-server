package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.search.SearchField
import com.terraformation.seedbank.search.SearchFilter
import com.terraformation.seedbank.search.SearchService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.NotEmpty
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/search")
@RestController
@SeedBankAppEndpoint
class SearchController(private val searchService: SearchService) {
  @Operation(summary = "Searches for accessions based on filter criteria.")
  @PostMapping
  fun search(@RequestBody payload: SearchRequestPayload): SearchResponsePayload {
    return searchService.search(payload)
  }
}

enum class SearchDirection {
  Ascending,
  Descending
}

data class SearchSortOrderElement(
    val field: SearchField<*>,
    @Schema(defaultValue = "Ascending") val direction: SearchDirection? = SearchDirection.Ascending
)

data class SearchRequestPayload(
    @NotEmpty val fields: List<SearchField<*>>,
    val sortOrder: List<SearchSortOrderElement>? = null,
    val filters: List<SearchFilter>? = null,
    val cursor: String? = null,
    @Schema(defaultValue = "10") val count: Int = 10
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SearchResponsePayload(val results: List<Map<String, String>>, val cursor: String?)
