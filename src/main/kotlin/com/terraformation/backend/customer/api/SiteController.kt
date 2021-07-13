package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.NoOrganizationException
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.WrongOrganizationException
import com.terraformation.backend.auth.ClientIdentity
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.pojos.SitesRow
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/site")
class SiteController(private val projectsDao: ProjectsDao, private val sitesDao: SitesDao) {
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

    val projectIds =
        projectsDao.fetchByOrganizationId(organizationId).mapNotNull { it.id }.toTypedArray()
    val elements = sitesDao.fetchByProjectId(*projectIds).map { ListSitesElement(it) }
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

    if (!currentUser().canReadSite(SiteId(siteId))) {
      throw WrongOrganizationException()
    }

    val site = sitesDao.fetchOneById(SiteId(siteId)) ?: throw NotFoundException()

    return GetSiteResponse(site)
  }
}

data class ListSitesElement(val id: Long, val name: String) {
  constructor(site: SitesRow) : this(site.id!!.value, site.name!!)
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
      record: SitesRow
  ) : this(
      record.id!!.value,
      record.name!!,
      record.latitude!!.toPlainString(),
      record.longitude!!.toPlainString(),
      record.locale,
      record.timezone)
}
