package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleErrorResponsePayload
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.ProjectOrganizationWideException
import com.terraformation.backend.db.ProjectStatus
import com.terraformation.backend.db.ProjectType
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.ws.rs.ClientErrorException
import javax.ws.rs.NotFoundException
import javax.ws.rs.core.Response
import org.springframework.web.bind.annotation.DeleteMapping
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
    val project = projectStore.fetchById(projectId) ?: throw ProjectNotFoundException(projectId)
    return GetProjectResponsePayload(ProjectPayload(project))
  }

  @ApiResponse(responseCode = "200", description = "Project created.")
  @Operation(summary = "Creates a new project in an existing organization.")
  @PostMapping
  fun createProject(@RequestBody payload: CreateProjectRequestPayload): GetProjectResponsePayload {
    val project =
        projectStore.create(
            description = payload.description,
            name = payload.name,
            organizationId = payload.organizationId,
            startDate = payload.startDate,
            status = payload.status,
            types = payload.types ?: emptyList())

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
        description = payload.description,
        name = payload.name,
        projectId = projectId,
        startDate = payload.startDate,
        status = payload.status,
        types = payload.types ?: emptyList())
    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404("The user does not exist or is not a member of the organization.")
  @ApiResponse(
      responseCode = "409",
      description =
          "The user is already a member of the project, or the project is organization-wide.",
      content = [Content(schema = Schema(implementation = SimpleErrorResponsePayload::class))])
  @Operation(
      summary = "Adds a user to a project.",
      description =
          "The user must already be a member of, or already be invited to, the organization.")
  @PostMapping("/{projectId}/users/{userId}")
  fun addProjectUser(
      @PathVariable("projectId") projectId: ProjectId,
      @PathVariable("userId") userId: UserId
  ): SimpleSuccessResponsePayload {
    try {
      projectStore.addUser(projectId, userId)
    } catch (e: ProjectOrganizationWideException) {
      throw ClientErrorException("Project is organization-wide", Response.Status.CONFLICT)
    }

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404("The user does not exist or is not a member of the project.")
  @Operation(summary = "Removes a user from a project.")
  @DeleteMapping("/{projectId}/users/{userId}")
  fun deleteProjectUser(
      @PathVariable("projectId") projectId: ProjectId,
      @PathVariable("userId") userId: UserId
  ): SimpleSuccessResponsePayload {
    try {
      projectStore.removeUser(projectId, userId)
    } catch (e: UserNotFoundException) {
      throw NotFoundException("User is not a member of the project")
    }
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
    val projects = projectStore.fetchByOrganization(organizationId)

    return ListProjectsResponsePayload(projects.map { ProjectPayload(it) })
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProjectPayload(
    val createdTime: Instant,
    val description: String?,
    val id: ProjectId,
    val name: String,
    val organizationId: OrganizationId,
    @Schema(
        description =
            "If false, the project is accessible by the entire organization and users may not " +
                "be added. If true, the project is only accessible by users who are specifically " +
                "added to it (as well as to admins and owners).",
    )
    val organizationWide: Boolean,
    val sites: List<SiteElement>?,
    val startDate: LocalDate?,
    val status: ProjectStatus?,
    val types: Set<ProjectType>?,
) {
  constructor(
      model: ProjectModel
  ) : this(
      createdTime = model.createdTime.truncatedTo(ChronoUnit.SECONDS),
      description = model.description,
      id = model.id,
      name = model.name,
      organizationId = model.organizationId,
      organizationWide = model.organizationWide,
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
