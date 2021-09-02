package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.NoOrganizationException
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.WrongOrganizationException
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
  fun listSites(): ListSitesResponse {
    // TODO: Super admins should be able to specify an organization ID
    val organizationId = currentUser().defaultOrganizationId() ?: throw NoOrganizationException()

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
  fun getSite(@PathVariable siteId: SiteId): GetSiteResponse {
    if (!currentUser().canReadSite(siteId)) {
      throw WrongOrganizationException()
    }

    val site = sitesDao.fetchOneById(siteId) ?: throw NotFoundException()

    return GetSiteResponse(site)
  }
}

data class ListSitesElement(val id: SiteId, val name: String) {
  constructor(site: SitesRow) : this(site.id!!, site.name!!)
}

data class ListSitesResponse(val sites: List<ListSitesElement>)

@Schema(requiredProperties = ["id"])
data class GetSiteResponse(
    val id: SiteId,
    val name: String,
    val latitude: String,
    val longitude: String,
    val locale: String?,
    val timezone: String?
) {
  constructor(
      record: SitesRow
  ) : this(
      record.id!!,
      record.name!!,
      record.latitude!!.toPlainString(),
      record.longitude!!.toPlainString(),
      record.locale,
      record.timezone)
}
