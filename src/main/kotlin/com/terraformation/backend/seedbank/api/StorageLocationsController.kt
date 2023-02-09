package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.tables.pojos.StorageLocationsRow
import com.terraformation.backend.seedbank.db.AccessionStore
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
  fun listStorageLocations(
      @RequestParam(required = true) facilityId: FacilityId
  ): ListStorageLocationsResponsePayload {
    val locations = facilityStore.fetchStorageLocations(facilityId)
    val counts = accessionStore.countActiveByStorageLocation(facilityId)

    return ListStorageLocationsResponsePayload(
        locations.map { StorageLocationPayload(it, counts[it.id!!]) })
  }

  @GetMapping("/{id}")
  fun getStorageLocation(
      @PathVariable("id") id: StorageLocationId
  ): GetStorageLocationResponsePayload {
    val location = facilityStore.fetchStorageLocation(id)
    val count = accessionStore.countActiveInStorageLocation(id)

    return GetStorageLocationResponsePayload(location, count)
  }

  @ApiResponse200
  @ApiResponse409(
      description = "A storage location with the requested name already exists at the facility.")
  @PostMapping
  fun createStorageLocation(
      @RequestBody payload: CreateStorageLocationRequestPayload
  ): GetStorageLocationResponsePayload {
    val id = facilityStore.createStorageLocation(payload.facilityId, payload.name)

    return GetStorageLocationResponsePayload(
        StorageLocationPayload(
            activeAccessions = 0, facilityId = payload.facilityId, id = id, name = payload.name))
  }

  @ApiResponse200
  @ApiResponse409(
      description = "A storage location with the requested name already exists at the facility.")
  @PutMapping("/{id}")
  fun updateStorageLocation(
      @PathVariable("id") id: StorageLocationId,
      @RequestBody payload: UpdateStorageLocationRequestPayload
  ): SimpleSuccessResponsePayload {
    facilityStore.updateStorageLocation(id, payload.name)

    return SimpleSuccessResponsePayload()
  }

  @DeleteMapping("/{id}")
  fun deleteStorageLocation(
      @PathVariable("id") id: StorageLocationId
  ): SimpleSuccessResponsePayload {
    facilityStore.deleteStorageLocation(id)

    return SimpleSuccessResponsePayload()
  }
}

data class StorageLocationPayload(
    val activeAccessions: Int,
    val facilityId: FacilityId,
    val id: StorageLocationId,
    val name: String,
) {
  constructor(
      row: StorageLocationsRow,
      activeAccessions: Int?
  ) : this(activeAccessions ?: 0, row.facilityId!!, row.id!!, row.name!!)
}

data class ListStorageLocationsResponsePayload(val storageLocations: List<StorageLocationPayload>) :
    SuccessResponsePayload

data class GetStorageLocationResponsePayload(val storageLocation: StorageLocationPayload) :
    SuccessResponsePayload {
  constructor(
      row: StorageLocationsRow,
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
