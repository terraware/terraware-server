package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.tables.pojos.StorageLocationsRow
import com.terraformation.backend.seedbank.model.NewStorageLocationModel
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
class StorageLocationsController(private val facilityStore: FacilityStore) {
  @GetMapping
  fun listStorageLocations(
      @RequestParam(required = true) facilityId: FacilityId
  ): ListStorageLocationsResponsePayload {
    val locations = facilityStore.fetchStorageLocations(facilityId)

    return ListStorageLocationsResponsePayload(locations.map { StorageLocationPayload(it) })
  }

  @GetMapping("/{id}")
  fun getStorageLocation(
      @PathVariable("id") id: StorageLocationId
  ): GetStorageLocationResponsePayload {
    val location = facilityStore.fetchStorageLocation(id)

    return GetStorageLocationResponsePayload(location)
  }

  @PostMapping
  fun createStorageLocation(
      @RequestBody payload: CreateStorageLocationRequestPayload
  ): GetStorageLocationResponsePayload {
    val id =
        facilityStore.createStorageLocation(
            payload.facilityId, payload.name, StorageCondition.Freezer)

    return GetStorageLocationResponsePayload(
        StorageLocationPayload(payload.facilityId, id, payload.name))
  }

  @PutMapping("/{id}")
  fun updateStorageLocation(
      @PathVariable("id") id: StorageLocationId,
      @RequestBody payload: UpdateStorageLocationRequestPayload
  ): SimpleSuccessResponsePayload {
    facilityStore.updateStorageLocation(id, payload.name, StorageCondition.Freezer)

    return SimpleSuccessResponsePayload()
  }
}

data class StorageLocationPayload(
    val facilityId: FacilityId,
    val id: StorageLocationId,
    val name: String,
) {
  constructor(row: StorageLocationsRow) : this(row.facilityId!!, row.id!!, row.name!!)
}

data class ListStorageLocationsResponsePayload(val storageLocations: List<StorageLocationPayload>) :
    SuccessResponsePayload

data class GetStorageLocationResponsePayload(val storageLocation: StorageLocationPayload) :
    SuccessResponsePayload {
  constructor(row: StorageLocationsRow) : this(StorageLocationPayload(row))
}

data class CreateStorageLocationRequestPayload(
    val facilityId: FacilityId,
    val name: String,
) {
  fun toModel() = NewStorageLocationModel(facilityId = facilityId, id = null, name = name)
}

data class UpdateStorageLocationRequestPayload(
    val name: String,
)
