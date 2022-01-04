package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectStatus
import com.terraformation.backend.db.ProjectType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/projects")
@RestController
class ProjectsController(private val projectStore: ProjectStore) {
  @GetMapping
  @Operation(summary = "Gets a list of all projects the user can access.")
  fun listAllProjects(): ListProjectsResponsePayload {
    val projects = projectStore.fetchAll()

    return ListProjectsResponsePayload(projects.map { ProjectPayload(it) })
  }

  @ApiResponse(responseCode = "200", description = "Project retrieved.")
  @ApiResponse404
  @GetMapping("/{id}")
  @Operation(summary = "Gets information about a single project.")
  fun getProject(@PathVariable("id") projectId: ProjectId): GetProjectResponsePayload {
    val project = projectStore.fetchById(projectId) ?: throw NotFoundException()

    return GetProjectResponsePayload(ProjectPayload(project))
  }

  @ApiResponse(responseCode = "200", description = "Project created.")
  @Operation(summary = "Creates a new project in an existing organization.")
  @PostMapping
  fun createProject(@RequestBody payload: CreateProjectRequestPayload): GetProjectResponsePayload {
    val project =
        projectStore.create(
            payload.organizationId,
            payload.name,
            payload.description,
            payload.startDate,
            payload.status,
            payload.types ?: emptyList())

    return GetProjectResponsePayload(ProjectPayload(project))
  }

  @Operation(
      summary = "Updates information about an existing project.",
      description =
          "Overwrites existing values; if a payload field is null, any existing value is removed " +
              "from the project.")
  @PutMapping("/{id}")
  fun updateProject(
      @PathVariable("id") projectId: ProjectId,
      @RequestBody payload: UpdateProjectRequestPayload
  ): SimpleSuccessResponsePayload {
    projectStore.update(
        projectId,
        payload.description,
        payload.name,
        payload.startDate,
        payload.status,
        payload.types ?: emptyList())
    return SimpleSuccessResponsePayload()
  }
}

@CustomerEndpoint
@RequestMapping("/api/v1/organizations/{organizationId}/projects")
@RestController
class OrganizationProjectsController(private val projectStore: ProjectStore) {
  @ApiResponse(responseCode = "200", description = "Projects retrieved.")
  @ApiResponse404(
      description =
          "The user is not a member of the organization, or the organization does not exist.")
  @GetMapping
  @Operation(
      summary = "Gets a list of the projects in an organization.",
      description = "Only projects that are accessible by the current user are included.")
  fun listOrganizationProjects(
      @PathVariable organizationId: OrganizationId
  ): ListProjectsResponsePayload {
    try {
      val projects = projectStore.fetchByOrganization(organizationId)

      return ListProjectsResponsePayload(projects.map { ProjectPayload(it) })
    } catch (e: OrganizationNotFoundException) {
      throw NotFoundException()
    }
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProjectPayload(
    val description: String?,
    val id: ProjectId,
    val name: String,
    val organizationId: OrganizationId,
    val sites: List<SiteElement>?,
    val startDate: LocalDate?,
    val status: ProjectStatus?,
    val types: Set<ProjectType>?,
) {
  constructor(
      model: ProjectModel
  ) : this(
      description = model.description,
      id = model.id,
      name = model.name,
      organizationId = model.organizationId,
      sites = model.sites?.map { SiteElement(it) },
      startDate = model.startDate,
      status = model.status,
      types = model.types,
  )
}

data class CreateProjectRequestPayload(
    val description: String?,
    val name: String,
    val organizationId: OrganizationId,
    val startDate: LocalDate?,
    val status: ProjectStatus?,
    val types: Set<ProjectType>?,
)

data class GetProjectResponsePayload(val project: ProjectPayload) : SuccessResponsePayload

data class ListProjectsResponsePayload(val projects: List<ProjectPayload>) : SuccessResponsePayload

data class UpdateProjectRequestPayload(
    val description: String?,
    val name: String,
    val startDate: LocalDate?,
    val status: ProjectStatus?,
    val types: Set<ProjectType>?,
)
