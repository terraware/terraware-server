package com.terraformation.backend.customer.api

import com.terraformation.backend.api.*
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.tables.pojos.ProjectsRow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.*

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
    val project = projectStore.create(payload.organizationId, payload.name)

    return GetProjectResponsePayload(ProjectPayload(project))
  }

  @PutMapping("/{id}")
  fun updateProject(
      @PathVariable("id") projectId: ProjectId,
      @RequestBody payload: UpdateProjectRequestPayload
  ): SimpleSuccessResponsePayload {
    val row = payload.toRow(projectId)
    projectStore.update(row)
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
    if (!currentUser().canListProjects(organizationId)) {
      throw NotFoundException()
    }

    val projects = projectStore.fetchByOrganization(organizationId)

    return ListProjectsResponsePayload(projects.map { ProjectPayload(it) })
  }
}

data class ProjectPayload(
    val id: ProjectId,
    val name: String,
    val organizationId: OrganizationId,
) {
  constructor(
      row: ProjectModel
  ) : this(
      id = row.id,
      name = row.name,
      organizationId = row.organizationId,
  )
}

data class CreateProjectRequestPayload(
    val name: String,
    val organizationId: OrganizationId,
)

data class GetProjectResponsePayload(val project: ProjectPayload) : SuccessResponsePayload

data class ListProjectsResponsePayload(val projects: List<ProjectPayload>) : SuccessResponsePayload

data class UpdateProjectRequestPayload(val name: String) {
  fun toRow(id: ProjectId) = ProjectsRow(id = id, name = name)
}
