package com.terraformation.seedbank.api

import com.terraformation.seedbank.db.tables.references.ORGANIZATION
import com.terraformation.seedbank.services.perClassLogger
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import org.jooq.DSLContext

@Controller("/api/v1/organization")
@Secured(SecurityRule.IS_AUTHENTICATED)
class OrganizationController(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  /** Lists all the organizations. The client must be a super admin. */
  @Get
  @Secured("SUPER_ADMIN")
  fun listAll(): ListOrganizationsResponse {
    val organizations =
        dslContext.selectFrom(ORGANIZATION).fetch { record ->
          ListOrganizationsElement(record.id!!, record.name!!)
        }
    return ListOrganizationsResponse(organizations)
  }
}

data class ListOrganizationsElement(val id: Int, val name: String)

data class ListOrganizationsResponse(val organizations: List<ListOrganizationsElement>)
