package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.model.SiteModel
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SiteNotFoundException
import com.terraformation.backend.db.tables.pojos.SitesRow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.validation.Valid
import javax.validation.constraints.NotEmpty
import net.postgis.jdbc.geometry.Point
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/sites")
class SitesController(private val siteStore: SiteStore) {
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
    val site =
        siteStore.fetchById(siteId, srid ?: SRID.LONG_LAT) ?: throw SiteNotFoundException(siteId)

    return GetSiteResponsePayload(SiteElement(site))
  }

  @ApiResponse(responseCode = "200", description = "Site created.")
  @PostMapping
  @Operation(summary = "Creates a new site.")
  fun createSite(@RequestBody @Valid payload: CreateSiteRequestPayload): CreateSiteResponsePayload {
    val site = siteStore.create(payload.toRow())
    return CreateSiteResponsePayload(site.id)
  }

  @ApiResponseSimpleSuccess
  @Operation(summary = "Updates information about a site.")
  @PutMapping("/{siteId}")
  fun updateSite(
      @PathVariable siteId: SiteId,
      @RequestBody @Valid payload: UpdateSiteRequestPayload
  ): SimpleSuccessResponsePayload {
    siteStore.update(payload.toRow(siteId))
    return SimpleSuccessResponsePayload()
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
    val createdTime: Instant,
    val description: String?,
    val id: SiteId,
    val name: String,
    val projectId: ProjectId,
    val location: Point?,
    val locale: String?,
    val timezone: String?,
    val facilities: List<FacilityPayload>?,
) {
  constructor(
      model: SiteModel
  ) : this(
      createdTime = model.createdTime.truncatedTo(ChronoUnit.SECONDS),
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

data class CreateSiteRequestPayload(
    val description: String?,
    val location: Point?,
    val locale: String?,
    @field:NotEmpty val name: String,
    val projectId: ProjectId,
    val timezone: String?,
) {
  fun toRow(): SitesRow {
    return SitesRow(
        description = description,
        location = location,
        locale = locale,
        name = name,
        projectId = projectId,
        timezone = timezone,
    )
  }
}

data class CreateSiteResponsePayload(val id: SiteId) : SuccessResponsePayload

data class UpdateSiteRequestPayload(
    val description: String?,
    val location: Point?,
    val locale: String?,
    @field:NotEmpty val name: String,
    @Schema(
        description =
            "If present, move the site to this project. Project must be owned by the same " +
                "organization as the site's current project. User must have permission to add " +
                "sites to the new project and remove them from the existing one.")
    val projectId: ProjectId?,
    val timezone: String?,
) {
  fun toRow(id: SiteId): SitesRow {
    return SitesRow(
        description = description,
        id = id,
        location = location,
        locale = locale,
        name = name,
        projectId = projectId,
        timezone = timezone,
    )
  }
}
