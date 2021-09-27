package com.terraformation.backend.customer.api

import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/sites")
class SiteController(private val siteStore: SiteStore) {
  @ApiResponse(responseCode = "200", description = "Retrieved list of sites.")
  @GetMapping
  @Operation(summary = "Gets all of the sites the current user can access.")
  fun listAllSites(): ListSitesResponsePayload {
    val sites = siteStore.fetchAll()

    return ListSitesResponsePayload(sites.map { SiteElement(it) })
  }

  @ApiResponse(responseCode = "200", description = "Site retrieved.")
  @ApiResponse404
  @GetMapping("/{siteId}")
  @Operation(summary = "Gets information about a particular site.")
  fun getSite(@PathVariable siteId: SiteId): GetSiteResponsePayload {
    val site = siteStore.fetchById(siteId) ?: throw NotFoundException()

    return GetSiteResponsePayload(SiteElement(site))
  }
}

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/projects/{projectId}/sites")
class ProjectSitesController(private val siteStore: SiteStore) {
  @ApiResponse(responseCode = "200", description = "Retrieved list of sites.")
  @ApiResponse404(description = "The project does not exist or is not accessible.")
  @GetMapping
  @Operation(summary = "Gets all of the sites associated with a project.")
  fun listProjectSites(@PathVariable projectId: ProjectId): ListSitesResponsePayload {
    val sites = siteStore.fetchByProjectId(projectId)

    return ListSitesResponsePayload(sites.map { SiteElement(it) })
  }
}

data class SiteElement(
    val id: SiteId,
    val name: String,
    val projectId: ProjectId,
    val latitude: String,
    val longitude: String,
    val locale: String?,
    val timezone: String?
) {
  constructor(
      site: SiteModel
  ) : this(
      id = site.id,
      name = site.name,
      projectId = site.projectId,
      latitude = site.latitude.toPlainString(),
      longitude = site.longitude.toPlainString(),
      locale = site.locale,
      timezone = site.timezone,
  )
}

data class ListSitesResponsePayload(val sites: List<SiteElement>) : SuccessResponsePayload

data class GetSiteResponsePayload(val site: SiteElement) : SuccessResponsePayload
