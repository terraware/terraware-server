package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.tables.pojos.SubLocationsRow
import com.terraformation.backend.seedbank.db.AccessionStore
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/storageLocations")
@RestController
@SeedBankAppEndpoint
class StorageLocationsController(
    private val accessionStore: AccessionStore,
    private val facilityStore: FacilityStore
) {
  @GetMapping
  @Operation(summary = "Gets a list of storage locations at a seed bank facility.")
  fun listStorageLocations(
      @RequestParam facilityId: FacilityId
  ): ListStorageLocationsResponsePayload {
    val locations = facilityStore.fetchSubLocations(facilityId)
    val counts = accessionStore.countActiveBySubLocation(facilityId)

    return ListStorageLocationsResponsePayload(
        locations.map { StorageLocationPayload(it, counts[it.id!!]) })
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Gets information about a specific storage location at a seed bank facility.")
  fun getStorageLocation(@PathVariable("id") id: SubLocationId): GetStorageLocationResponsePayload {
    val location = facilityStore.fetchSubLocation(id)
    val count = accessionStore.countActiveInSubLocation(id)

    return GetStorageLocationResponsePayload(location, count)
  }

  @ApiResponse200
  @ApiResponse409(
      description = "A storage location with the requested name already exists at the facility.")
  @Operation(summary = "Creates a new storage location at a seed bank facility.")
  @PostMapping
  fun createStorageLocation(
      @RequestBody payload: CreateStorageLocationRequestPayload
  ): GetStorageLocationResponsePayload {
    val id = facilityStore.createSubLocation(payload.facilityId, payload.name)

    return GetStorageLocationResponsePayload(
        StorageLocationPayload(
            activeAccessions = 0, facilityId = payload.facilityId, id = id, name = payload.name))
  }

  @ApiResponse200
  @ApiResponse409(
      description = "A storage location with the requested name already exists at the facility.")
  @Operation(summary = "Updates the name of a storage location at a seed bank facility.")
  @PutMapping("/{id}")
  fun updateStorageLocation(
      @PathVariable("id") id: SubLocationId,
      @RequestBody payload: UpdateStorageLocationRequestPayload
  ): SimpleSuccessResponsePayload {
    facilityStore.updateSubLocation(id, payload.name)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse409(
      description =
          "The storage location contains accessions. Move them to a different storage location " +
              "first.")
  @DeleteMapping("/{id}")
  @Operation(summary = "Deletes a storage location from a seed bank facility.")
  fun deleteStorageLocation(@PathVariable("id") id: SubLocationId): SimpleSuccessResponsePayload {
    facilityStore.deleteSubLocation(id)

    return SimpleSuccessResponsePayload()
  }
}

data class StorageLocationPayload(
    val activeAccessions: Int,
    val facilityId: FacilityId,
    val id: SubLocationId,
    val name: String,
) {
  constructor(
      row: SubLocationsRow,
      activeAccessions: Int?
  ) : this(activeAccessions ?: 0, row.facilityId!!, row.id!!, row.name!!)
}

data class ListStorageLocationsResponsePayload(val storageLocations: List<StorageLocationPayload>) :
    SuccessResponsePayload

data class GetStorageLocationResponsePayload(val storageLocation: StorageLocationPayload) :
    SuccessResponsePayload {
  constructor(
      row: SubLocationsRow,
      activeAccessions: Int
  ) : this(StorageLocationPayload(row, activeAccessions))
}

data class CreateStorageLocationRequestPayload(
    val facilityId: FacilityId,
    val name: String,
)

data class UpdateStorageLocationRequestPayload(
    val name: String,
)
