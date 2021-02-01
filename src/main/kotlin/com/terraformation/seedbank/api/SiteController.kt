package com.terraformation.seedbank.api

import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.db.tables.daos.SiteDao
import com.terraformation.seedbank.db.tables.pojos.Site
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/site")
@Hidden // Hide from Swagger docs while iterating on the seed bank app's API
@PreAuthorize("isAuthenticated()")
@SecurityRequirement(name = "ApiKey")
class SiteController(private val siteDao: SiteDao) {
  @GetMapping
  @Hidden
  @Operation(summary = "List all of an organization's sites")
  @ApiResponses(
      ApiResponse(
          responseCode = "400",
          description = "Client is not associated with an organization.",
      ),
  )
  fun listSites(@AuthenticationPrincipal clientIdentity: ClientIdentity): ListSitesResponse {
    // TODO: Super admins should be able to specify an organization ID
    val organizationId = clientIdentity.organizationId ?: throw NoOrganizationException()

    val elements = siteDao.fetchByOrganizationId(organizationId).map { ListSitesElement(it) }
    return ListSitesResponse(elements)
  }

  /**
   * Returns details about a particular site. The client must be a member of the organization that
   * owns the site.
   */
  @GetMapping("/{siteId}")
  @ApiResponses(
      ApiResponse(
          responseCode = "400",
          description = "Client is not associated with an organization and is not a site admin",
      ),
      ApiResponse(responseCode = "404", description = "No site with the requested ID exists."),
  )
  fun getSite(
      @AuthenticationPrincipal clientIdentity: ClientIdentity,
      @PathVariable siteId: Long
  ): GetSiteResponse {
    val organizationId = clientIdentity.organizationId
    if (organizationId == null && !clientIdentity.isSuperAdmin) {
      throw NoOrganizationException()
    }

    val site = siteDao.fetchOneById(siteId) ?: throw NotFoundException()
    if (site.organizationId != organizationId && !clientIdentity.isSuperAdmin) {
      throw WrongOrganizationException()
    }

    return GetSiteResponse(site)
  }
}

data class ListSitesElement(val id: Long, val name: String) {
  constructor(site: Site) : this(site.id!!, site.name!!)
}

data class ListSitesResponse(val sites: List<ListSitesElement>)

@Schema(requiredProperties = ["id"])
data class GetSiteResponse(
    val id: Long,
    val name: String,
    val latitude: String,
    val longitude: String,
    val locale: String?,
    val timezone: String?
) {
  constructor(
      record: Site
  ) : this(
      record.id!!,
      record.name!!,
      record.latitude!!.toPlainString(),
      record.longitude!!.toPlainString(),
      record.locale,
      record.timezone)
}
