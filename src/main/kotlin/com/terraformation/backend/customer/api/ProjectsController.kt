package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.NewProjectModel
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/projects")
@RestController
class ProjectsController(private val projectStore: ProjectStore) {
  @GetMapping
  @Operation(summary = "Lists accessible projects.")
  fun listProjects(
      @Parameter(
          description =
              "If specified, list projects in this organization. If absent, list projects in all " +
                  "the user's organizations.")
      @RequestParam(required = false)
      organizationId: OrganizationId? = null
  ): ListProjectsResponsePayload {
    val projects =
        organizationId?.let { projectStore.fetchByOrganizationId(organizationId) }
            ?: projectStore.findAll()

    return ListProjectsResponsePayload(projects.map { ProjectPayload(it) })
  }

  @GetMapping("/{id}")
  @Operation(summary = "Gets information about a specific project.")
  fun getProject(@PathVariable id: ProjectId): GetProjectResponsePayload {
    val project = projectStore.fetchOneById(id)

    return GetProjectResponsePayload(ProjectPayload(project))
  }

  @Operation(summary = "Creates a new project.")
  @PostMapping
  fun createProject(
      @RequestBody payload: CreateProjectRequestPayload
  ): CreateProjectResponsePayload {
    val projectId =
        projectStore.create(
            NewProjectModel(
                description = payload.description,
                id = null,
                name = payload.name,
                organizationId = payload.organizationId))

    return CreateProjectResponsePayload(projectId)
  }

  @DeleteMapping("/{id}")
  @Operation(
      summary = "Deletes an existing project.",
      description =
          "Any accessions, seedling batches, or planting sites that were assigned to the project " +
              "will no longer be assigned to any project.")
  fun deleteProject(@PathVariable id: ProjectId): SimpleSuccessResponsePayload {
    projectStore.delete(id)

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Updates information about an existing project.")
  @PutMapping("/{id}")
  fun updateProject(
      @PathVariable id: ProjectId,
      @RequestBody payload: UpdateProjectRequestPayload
  ): SimpleSuccessResponsePayload {
    projectStore.update(id, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ProjectPayload(
    val description: String?,
    val id: ProjectId,
    val organizationId: OrganizationId,
    val name: String,
) {
  constructor(
      model: ExistingProjectModel
  ) : this(
      description = model.description,
      id = model.id,
      organizationId = model.organizationId,
      name = model.name,
  )
}

data class CreateProjectRequestPayload(
    val description: String?,
    val name: String,
    val organizationId: OrganizationId,
)

data class CreateProjectResponsePayload(val id: ProjectId) : SuccessResponsePayload

data class UpdateProjectRequestPayload(
    val description: String?,
    val name: String,
) {
  fun applyTo(model: ExistingProjectModel) = model.copy(description = description, name = name)
}

data class GetProjectResponsePayload(val project: ProjectPayload) : SuccessResponsePayload

data class ListProjectsResponsePayload(val projects: List<ProjectPayload>) : SuccessResponsePayload
