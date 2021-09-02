package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.OrganizationId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/organization")
class OrganizationController(private val organizationStore: OrganizationStore) {
  @GetMapping
  @Operation(
      summary = "List all organizations",
      description = "List all organizations the user can access.",
  )
  fun listAll(): ListOrganizationsResponse {
    val elements =
        organizationStore.fetchAll().map { model -> ListOrganizationsElement(model.id, model.name) }
    return ListOrganizationsResponse(elements)
  }
}

data class ListOrganizationsElement(val id: OrganizationId, val name: String)

data class ListOrganizationsResponse(val organizations: List<ListOrganizationsElement>)
