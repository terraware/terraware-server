package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.DuplicateNameException
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.db.StorageLocationStore
import com.terraformation.backend.seedbank.search.SearchField
import com.terraformation.backend.seedbank.search.SearchService
import com.terraformation.backend.species.db.SpeciesStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.dao.DuplicateKeyException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/values")
@RestController
@SeedBankAppEndpoint
class ValuesController(
    private val accessionStore: AccessionStore,
    private val storageLocationStore: StorageLocationStore,
    private val searchService: SearchService,
    private val speciesStore: SpeciesStore,
) {
  @Operation(deprecated = true, description = "Use /api/v1/species instead.")
  @GetMapping("/species")
  fun listSpecies(): ListSpeciesResponsePayload {
    return ListSpeciesResponsePayload(
        speciesStore.findAllSortedByName().map { SpeciesDetails(it.id!!.value, it.name!!) })
  }

  @ApiResponses(
      ApiResponse(responseCode = "200", description = "Species created."),
      ApiResponse(
          responseCode = "409", description = "A species with the requested name already exists."))
  @Operation(deprecated = true, description = "Use /api/v1/species instead.")
  @PostMapping("/species")
  fun createSpecies(
      @RequestBody payload: CreateSpeciesRequestPayload
  ): CreateSpeciesResponsePayload {
    try {
      val row = SpeciesRow(name = payload.name)
      val speciesId = speciesStore.createSpecies(row)
      return CreateSpeciesResponsePayload(SpeciesDetails(speciesId.value, payload.name))
    } catch (e: DuplicateKeyException) {
      throw DuplicateNameException("A species with that name already exists.")
    }
  }

  @ApiResponse(
      responseCode = "200", description = "Species updated or merged with an existing species.")
  @ApiResponse404
  @Operation(
      summary = "Updates an existing species.",
      description =
          "If the species is being renamed and the species name in the payload already exists, " +
              "the existing species replaces the one in the request (thus merging the requested " +
              "species into the one that already had the name) and its ID is returned.")
  @PostMapping("/species/{id}")
  fun updateSpecies(
      @RequestBody payload: CreateSpeciesRequestPayload,
      @PathVariable id: Long
  ): UpdateSpeciesResponsePayload {
    try {
      val newId = accessionStore.updateSpecies(SpeciesId(id), payload.name)
      return UpdateSpeciesResponsePayload(newId?.value)
    } catch (e: SpeciesNotFoundException) {
      throw NotFoundException("Species not found.")
    }
  }

  @GetMapping("/storageLocation")
  fun getStorageLocations(): StorageLocationsResponsePayload {
    val facilityId = currentUser().defaultFacilityId()
    return StorageLocationsResponsePayload(
        storageLocationStore.fetchStorageConditionsByLocationName(facilityId).map {
          StorageLocationDetails(it.key, it.value)
        })
  }

  @Operation(
      summary =
          "List the values of a set of search fields for a set of accessions matching certain " +
              "filter criteria.")
  @PostMapping
  fun listFieldValues(
      @RequestBody payload: ListFieldValuesRequestPayload
  ): ListFieldValuesResponsePayload {
    val limit = 20

    val values =
        payload.fields.associateWith { searchField ->
          val values = searchService.fetchValues(searchField, payload.toSearchNode(), limit)
          val partial = values.size > limit
          FieldValuesPayload(values.take(limit), partial)
        }

    return ListFieldValuesResponsePayload(values)
  }

  @Operation(summary = "List the possible values of a set of search fields.")
  @PostMapping("/all")
  fun listAllFieldValues(
      @RequestBody payload: ListAllFieldValuesRequestPayload
  ): ListAllFieldValuesResponsePayload {
    val limit = 100

    val values =
        payload.fields.associateWith { searchField ->
          val values = searchService.fetchAllValues(searchField, limit)
          val partial = values.size > limit
          AllFieldValuesPayload(values.take(limit), partial)
        }

    return ListAllFieldValuesResponsePayload(values)
  }
}

data class SpeciesDetails(val id: Long, val name: String)

data class ListSpeciesResponsePayload(val values: List<SpeciesDetails>) : SuccessResponsePayload

data class CreateSpeciesRequestPayload(val name: String)

data class CreateSpeciesResponsePayload(val details: SpeciesDetails) : SuccessResponsePayload

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UpdateSpeciesResponsePayload(
    @Schema(
        description =
            "If the requested species name already existed, the ID of the existing species. " +
                "Will not be present if the requested species name did not already exist.")
    val mergedWithSpeciesId: Long?
) : SuccessResponsePayload

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
    @Schema(
        description =
            "If true, the list of values is too long to return in its entirety and \"values\" is " +
                "a partial list.")
    val partial: Boolean
)

data class ListFieldValuesRequestPayload(
    val fields: List<SearchField<*>>,
    override val filters: List<SearchFilter>?,
    override val search: SearchNodePayload?,
) : HasSearchNode

data class ListFieldValuesResponsePayload(val results: Map<SearchField<*>, FieldValuesPayload>) :
    SuccessResponsePayload

data class AllFieldValuesPayload(
    @ArraySchema(
        arraySchema =
            Schema(
                description =
                    "All the values this field could possibly have, whether or not any " +
                        "accessions have them. For fields that allow the user to enter arbitrary " +
                        "values, this is equivalent to querying the list of values without any " +
                        "filter criteria, that is, it's a list of all the user-entered values."))
    val values: List<String?>,
    @Schema(
        description =
            "If true, the list of values is too long to return in its entirety and \"values\" is " +
                "a partial list.")
    val partial: Boolean
)

data class ListAllFieldValuesRequestPayload(val fields: List<SearchField<*>>)

data class ListAllFieldValuesResponsePayload(
    val results: Map<SearchField<*>, AllFieldValuesPayload>
) : SuccessResponsePayload
