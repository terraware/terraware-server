package com.terraformation.seedbank.api.seedbank

import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.StorageLocationFetcher
import com.terraformation.seedbank.search.SearchField
import com.terraformation.seedbank.search.SearchFilter
import com.terraformation.seedbank.search.SearchService
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/values")
@RestController
@SeedBankAppEndpoint
class ValuesController(
    private val storageLocationFetcher: StorageLocationFetcher,
    private val searchService: SearchService
) {
  @GetMapping("/storageLocation")
  fun getStorageLocations(): StorageLocationsResponsePayload {
    return StorageLocationsResponsePayload(
        storageLocationFetcher.fetchStorageConditionsByLocationName().map {
          StorageLocationDetails(it.key, it.value)
        })
  }

  @PostMapping
  fun listFieldValues(
      @RequestBody payload: ListFieldValuesRequestPayload
  ): ListFieldValuesResponsePayload {
    val limit = 20
    val filters = payload.filters ?: emptyList()

    val values =
        payload.fields.associateWith { searchField ->
          val values = searchService.fetchFieldValues(searchField, filters, limit)
          val partial = values.size > limit
          FieldValuesPayload(values.take(limit), partial)
        }

    return ListFieldValuesResponsePayload(values)
  }
}

data class StorageLocationsResponsePayload(val locations: List<StorageLocationDetails>) :
    SuccessResponsePayload

data class StorageLocationDetails(
    val storageLocation: String,
    val storageCondition: StorageCondition
)

data class FieldValuesPayload(
    @ArraySchema(
        schema = Schema(nullable = true),
        arraySchema =
            Schema(
                description =
                    "List of values in the matching accessions. If there are accessions where " +
                        "the field has no value, this list will contain null (an actual null " +
                        "value, not the string \"null\")."))
    val values: List<String?>,
    val partial: Boolean
)

data class ListFieldValuesRequestPayload(
    val fields: List<SearchField<*>>,
    val filters: List<SearchFilter>?
)

data class ListFieldValuesResponsePayload(val results: Map<SearchField<*>, FieldValuesPayload>) :
    SuccessResponsePayload
