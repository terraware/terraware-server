package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.FacilityStore
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
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
  fun listAll(): ListFacilitiesResponse {
    val user = currentUser()
    val facilityRoles = user.facilityRoles
    val facilities = facilityStore.fetchAll()

    val elements =
        facilities.map { facility ->
          val role =
              facilityRoles[facility.id]
                  ?: throw IllegalStateException(
                      "BUG! No role for facility that was selected based on the presence of a role")
          ListFacilitiesElement(facility.id.value, facility.name, facility.type.id, role.name)
        }

    return ListFacilitiesResponse(elements)
  }
}

data class ListFacilitiesElement(val id: Long, val name: String, val type: Int, val role: String)

data class ListFacilitiesResponse(val facilities: List<ListFacilitiesElement>)
