package com.terraformation.seedbank.api

import com.terraformation.seedbank.auth.isSuperAdmin
import com.terraformation.seedbank.auth.organizationId
import com.terraformation.seedbank.db.tables.daos.SiteDao
import com.terraformation.seedbank.db.tables.pojos.Site
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import javax.inject.Singleton

@Controller("/api/v1/site")
@Secured(SecurityRule.IS_AUTHENTICATED)
@Singleton
class SiteController(private val siteDao: SiteDao) {
  /**
   * Lists all the sites for an organization.
   *
   * @return The list of sites.
   */
  @Get
  @ApiResponses(
      ApiResponse(
          responseCode = "400", description = "Client is not associated with an organization."))
  fun listSites(auth: Authentication): ListSitesResponse {
    // TODO: Super admins should be able to specify an organization ID
    val organizationId = auth.organizationId ?: throw NoOrganizationException()

    val elements = siteDao.fetchByOrganizationId(organizationId).map { ListSitesElement(it) }
    return ListSitesResponse(elements)
  }

  /**
   * Returns details about a particular site. The client must be a member of the organization that
   * owns the site.
   */
  @Get("/{siteId}")
  @ApiResponses(
      ApiResponse(
          responseCode = "400",
          description = "Client is not associated with an organization and is not a site admin"),
      ApiResponse(responseCode = "404", description = "No site with the requested ID exists."))
  fun getSite(auth: Authentication, siteId: Long): GetSiteResponse {
    val organizationId = auth.organizationId
    if (organizationId == null && !auth.isSuperAdmin) {
      throw NoOrganizationException()
    }

    val site = siteDao.fetchOneById(siteId) ?: throw NotFoundException()
    if (site.organizationId != organizationId && !auth.isSuperAdmin) {
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
  constructor(record: Site) : this(
      record.id!!,
      record.name!!,
      record.latitude!!.toPlainString(),
      record.longitude!!.toPlainString(),
      record.locale,
      record.timezone)
}
