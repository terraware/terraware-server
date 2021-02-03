package com.terraformation.seedbank.api.seedbank

import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.StorageLocationFetcher
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/values")
@RestController
class ValuesController(private val storageLocationFetcher: StorageLocationFetcher) {
  @GetMapping("/storageLocation")
  fun getStorageLocations(): StorageLocationsResponsePayload {
    return StorageLocationsResponsePayload(
        storageLocationFetcher.fetchStorageConditionsByLocationName().map {
          StorageLocationDetails(it.key, it.value)
        })
  }
}

data class StorageLocationsResponsePayload(val locations: List<StorageLocationDetails>) :
    SuccessResponsePayload

data class StorageLocationDetails(
    val storageLocation: String,
    val storageCondition: StorageCondition
)
