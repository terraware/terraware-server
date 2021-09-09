package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/facility")
class FacilityController(private val facilityStore: FacilityStore) {
  @GetMapping
  @Operation(
      summary = "List all accessible facilities",
      description = "List all the facilities the current user can access.",
  )
  fun listAllFacilities(): ListFacilitiesResponse {
    val user = currentUser()
    val facilityRoles = user.facilityRoles
    val facilities = facilityStore.fetchAll()

    val elements =
        facilities.map { facility ->
          val role =
              facilityRoles[facility.id]
                  ?: throw IllegalStateException(
                      "BUG! No role for facility that was selected based on the presence of a role")
          FacilityPayload(facility.id, facility.name, facility.type, role.name)
        }

    return ListFacilitiesResponse(elements)
  }

  @GetMapping("/{facilityId}")
  @Operation(summary = "Gets information about a single facility")
  fun getFacility(@PathVariable facilityId: FacilityId): GetFacilityResponse {
    val facility = facilityStore.fetchById(facilityId) ?: throw NotFoundException()
    val role =
        currentUser().facilityRoles[facilityId]
            ?: throw IllegalStateException("BUG! No role for facility")

    return GetFacilityResponse(FacilityPayload(facilityId, facility.name, facility.type, role.name))
  }
}

data class FacilityPayload(
    val id: FacilityId,
    val name: String,
    val type: FacilityType,
    @Schema(description = "The name of the role the current user has at the facility.")
    val role: String
)

data class GetFacilityResponse(val facility: FacilityPayload) : SuccessResponsePayload

data class ListFacilitiesResponse(val facilities: List<FacilityPayload>) : SuccessResponsePayload
