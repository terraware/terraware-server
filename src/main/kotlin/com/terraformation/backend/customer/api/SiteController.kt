package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import net.postgis.jdbc.geometry.Point
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/sites")
class SiteController(private val siteStore: SiteStore) {
  @ApiResponse(responseCode = "200", description = "Retrieved list of sites.")
  @GetMapping
  @Operation(summary = "Gets all of the sites the current user can access.")
  fun listAllSites(
      @RequestParam("srid")
      @Schema(
          description = "Spatial reference system identifier to use for locations.",
          defaultValue = "4326")
      srid: Int? = null
  ): ListSitesResponsePayload {
    val sites = siteStore.fetchAll(srid ?: SRID.LONG_LAT)

    return ListSitesResponsePayload(sites.map { SiteElement(it) })
  }

  @ApiResponse(responseCode = "200", description = "Site retrieved.")
  @ApiResponse404
  @GetMapping("/{siteId}")
  @Operation(summary = "Gets information about a particular site.")
  fun getSite(
      @PathVariable siteId: SiteId,
      @RequestParam("srid")
      @Schema(
          description = "Spatial reference system identifier to use for locations.",
          defaultValue = "4326")
      srid: Int? = null
  ): GetSiteResponsePayload {
    val site = siteStore.fetchById(siteId, srid ?: SRID.LONG_LAT) ?: throw NotFoundException()

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
  fun listProjectSites(
      @PathVariable projectId: ProjectId,
      @RequestParam("srid")
      @Schema(
          description = "Spatial reference system identifier to use for locations.",
          defaultValue = "4326")
      srid: Int? = null
  ): ListSitesResponsePayload {
    val sites = siteStore.fetchByProjectId(projectId, srid ?: SRID.LONG_LAT)

    return ListSitesResponsePayload(sites.map { SiteElement(it) })
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SiteElement(
    val description: String?,
    val id: SiteId,
    val name: String,
    val projectId: ProjectId,
    val location: Point,
    val locale: String?,
    val timezone: String?,
    val facilities: List<FacilityPayload>?,
) {
  constructor(
      model: SiteModel
  ) : this(
      description = model.description,
      id = model.id,
      name = model.name,
      projectId = model.projectId,
      location = model.location,
      locale = model.locale,
      timezone = model.timezone,
      facilities = model.facilities?.map { FacilityPayload(it) },
  )
}

data class ListSitesResponsePayload(val sites: List<SiteElement>) : SuccessResponsePayload

data class GetSiteResponsePayload(val site: SiteElement) : SuccessResponsePayload
