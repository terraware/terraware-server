package com.terraformation.backend.customer.api

import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.db.SubLocationNotFoundException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.tables.pojos.SubLocationsRow
import com.terraformation.backend.seedbank.db.AccessionStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/facilities/{facilityId}/subLocations")
class SubLocationsController(
    private val accessionStore: AccessionStore,
    private val facilityStore: FacilityStore,
) {
  @GetMapping
  @Operation(summary = "Gets a list of sub-locations at a facility.")
  fun listSubLocations(@PathVariable facilityId: FacilityId): ListSubLocationsResponsePayload {
    val locations = facilityStore.fetchSubLocations(facilityId)
    val accessionCounts = accessionStore.countActiveBySubLocation(facilityId)

    return ListSubLocationsResponsePayload(
        locations.map { SubLocationPayload(it, accessionCounts[it.id!!], null) }
    )
  }

  @GetMapping("/{subLocationId}")
  @Operation(summary = "Gets information about a specific sub-location at a facility.")
  fun getSubLocation(
      @PathVariable facilityId: FacilityId,
      @PathVariable subLocationId: SubLocationId,
  ): GetSubLocationResponsePayload {
    val location = facilityStore.fetchSubLocation(subLocationId)
    val accessionCount = accessionStore.countActiveInSubLocation(subLocationId)

    return GetSubLocationResponsePayload(location, accessionCount, null)
  }

  @ApiResponse200
  @ApiResponse409(
      description = "A sub-location with the requested name already exists at the facility."
  )
  @Operation(summary = "Creates a new sub-location at a facility.")
  @PostMapping
  fun createSubLocation(
      @PathVariable facilityId: FacilityId,
      @RequestBody payload: CreateSubLocationRequestPayload,
  ): GetSubLocationResponsePayload {
    val facility = facilityStore.fetchOneById(facilityId)
    val id = facilityStore.createSubLocation(facilityId, payload.name)
    val accessionCount = if (facility.type == FacilityType.SeedBank) 0 else null

    return GetSubLocationResponsePayload(
        SubLocationPayload(
            activeAccessions = accessionCount,
            facilityId = facilityId,
            id = id,
            name = payload.name,
        )
    )
  }

  @ApiResponse200
  @ApiResponse409(
      description = "A sub-location with the requested name already exists at the facility."
  )
  @Operation(summary = "Updates the name of a sub-location at a facility.")
  @PutMapping("/{subLocationId}")
  fun updateSubLocation(
      @PathVariable facilityId: FacilityId,
      @PathVariable subLocationId: SubLocationId,
      @RequestBody payload: UpdateSubLocationRequestPayload,
  ): SimpleSuccessResponsePayload {
    val location = facilityStore.fetchSubLocation(subLocationId)
    if (location.facilityId != facilityId) {
      throw SubLocationNotFoundException(subLocationId)
    }

    facilityStore.updateSubLocation(subLocationId, payload.name)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse409(
      description = "The sub-location is in use, e.g., there are seeds or seedlings stored there."
  )
  @DeleteMapping("/{subLocationId}")
  @Operation(
      summary = "Deletes a sub-location from a facility.",
      description = "The sub-location must not be in use.",
  )
  fun deleteSubLocation(
      @PathVariable facilityId: FacilityId,
      @PathVariable subLocationId: SubLocationId,
  ): SimpleSuccessResponsePayload {
    val location = facilityStore.fetchSubLocation(subLocationId)
    if (location.facilityId != facilityId) {
      throw SubLocationNotFoundException(subLocationId)
    }

    facilityStore.deleteSubLocation(subLocationId)

    return SimpleSuccessResponsePayload()
  }
}

data class SubLocationPayload(
    @Schema(
        description =
            "If this sub-location is at a seed bank, the number of active accessions stored there."
    )
    val activeAccessions: Int?,
    @Schema(
        description =
            "If this sub-location is at a nursery, the number of batches stored there that have " +
                "seedlings."
    )
    val activeBatches: Int? = null,
    val facilityId: FacilityId,
    val id: SubLocationId,
    val name: String,
) {
  constructor(
      row: SubLocationsRow,
      activeAccessions: Int?,
      activeBatches: Int?,
  ) : this(
      activeAccessions = activeAccessions,
      activeBatches = activeBatches,
      facilityId = row.facilityId!!,
      id = row.id!!,
      name = row.name!!,
  )
}

data class CreateSubLocationRequestPayload(
    val name: String,
)

data class UpdateSubLocationRequestPayload(
    val name: String,
)

data class GetSubLocationResponsePayload(val subLocation: SubLocationPayload) :
    SuccessResponsePayload {
  constructor(
      row: SubLocationsRow,
      activeAccessions: Int?,
      activeBatches: Int?,
  ) : this(SubLocationPayload(row, activeAccessions, activeBatches))
}

data class ListSubLocationsResponsePayload(val subLocations: List<SubLocationPayload>) :
    SuccessResponsePayload
