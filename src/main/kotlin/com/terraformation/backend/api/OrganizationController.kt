package com.terraformation.backend.api

import com.terraformation.backend.db.tables.daos.OrganizationsDao
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/organization")
@Hidden // Hide from Swagger docs while iterating on the seed bank app's API
@PreAuthorize("isAuthenticated()")
class OrganizationController(private val organizationsDao: OrganizationsDao) {
  @GetMapping
  @Hidden
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  @Operation(
      summary = "List all organizations",
      description = "List all the known organizations. Client must be a super admin.",
  )
  fun listAll(): ListOrganizationsResponse {
    val elements =
        organizationsDao.findAll().map { record ->
          ListOrganizationsElement(record.id!!, record.name!!)
        }
    return ListOrganizationsResponse(elements)
  }
}

data class ListOrganizationsElement(val id: Long, val name: String)

data class ListOrganizationsResponse(val organizations: List<ListOrganizationsElement>)
