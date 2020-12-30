package com.terraformation.seedbank.api

import com.terraformation.seedbank.db.OrganizationFetcher
import com.terraformation.seedbank.services.perClassLogger
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule

@Controller("/api/v1/organization")
@Secured(SecurityRule.IS_AUTHENTICATED)
class OrganizationController(private val organizationFetcher: OrganizationFetcher) {
  private val log = perClassLogger()

  /** Lists all the organizations. The client must be a super admin. */
  @Get
  @Secured("SUPER_ADMIN")
  fun listAll(): ListOrganizationsResponse {
    val elements =
        organizationFetcher.getAllOrganizations().map { record ->
          ListOrganizationsElement(record.id!!, record.name!!)
        }
    return ListOrganizationsResponse(elements)
  }
}

data class ListOrganizationsElement(val id: Int, val name: String)

data class ListOrganizationsResponse(val organizations: List<ListOrganizationsElement>)
