package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.accelerator.db.ProjectCohortFetcher
import com.terraformation.backend.accelerator.model.ProjectCohortData
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.ProjectService
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.NewProjectModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectInternalUsersRow
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.tracking.PlantingSiteId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import java.time.Instant
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
class ProjectsController(
    private val projectCohortFetcher: ProjectCohortFetcher,
    private val projectService: ProjectService,
    private val projectStore: ProjectStore,
    private val userStore: UserStore,
) {
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

    return ListProjectsResponsePayload(
        projects.map { ProjectPayload(it, projectCohortFetcher.fetchCohortData(it.id)) })
  }

  @GetMapping("/{id}")
  @Operation(summary = "Gets information about a specific project.")
  fun getProject(@PathVariable id: ProjectId): GetProjectResponsePayload {
    val project = projectStore.fetchOneById(id)
    val cohortData = projectCohortFetcher.fetchCohortData(id)

    return GetProjectResponsePayload(ProjectPayload(project, cohortData))
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

  @Operation(
      summary = "Assigns multiple entities to a project.",
      description = "Overwrites any existing project assignments.")
  @PostMapping("/{id}/assign")
  fun assignProject(
      @PathVariable id: ProjectId,
      @RequestBody payload: AssignProjectRequestPayload
  ): SimpleSuccessResponsePayload {
    projectService.assignProject(
        id,
        payload.accessionIds ?: emptyList(),
        payload.batchIds ?: emptyList(),
        payload.plantingSiteIds ?: emptyList())

    return SimpleSuccessResponsePayload()
  }

  @Operation(summary = "Get all internal project users for a project.")
  @GetMapping("/{id}/userRoles")
  fun getUserRoles(@PathVariable id: ProjectId): ListProjectUserRolesResponsePayload {
    val projectInternalUsers =
        projectStore.fetchInternalUsers(projectId = id).associateBy { it.userId }
    val users = userStore.fetchManyById(projectInternalUsers.keys.filterNotNull())

    return ListProjectUserRolesResponsePayload(
        users.map { ProjectUserRoleResponsePayload(it, projectInternalUsers[it.userId]!!) })
  }

  @Operation(summary = "Assign a global user as a specific role for a project.")
  @PutMapping("/{id}/assignRole")
  fun assignRole(
      @PathVariable id: ProjectId,
      @RequestBody payload: AssignProjectInternalUserRequestPayload
  ): SimpleSuccessResponsePayload {
    projectService.addInternalUserRole(id, payload.userId, payload.role, payload.roleName)

    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class ProjectPayload(
    val cohortId: CohortId?,
    val cohortPhase: CohortPhase?,
    val createdBy: UserId?,
    val createdTime: Instant?,
    val description: String?,
    val id: ProjectId,
    val modifiedBy: UserId?,
    val modifiedTime: Instant?,
    val name: String,
    val organizationId: OrganizationId,
    val participantId: ParticipantId?,
) {
  constructor(
      model: ExistingProjectModel,
      cohortData: ProjectCohortData? = null
  ) : this(
      cohortId = cohortData?.cohortId,
      cohortPhase = cohortData?.cohortPhase,
      createdBy = model.createdBy,
      createdTime = model.createdTime,
      description = model.description,
      id = model.id,
      modifiedBy = model.modifiedBy,
      modifiedTime = model.modifiedTime,
      name = model.name,
      organizationId = model.organizationId,
      participantId = model.participantId,
  )
}

data class AssignProjectRequestPayload(
    val accessionIds: List<AccessionId>?,
    val batchIds: List<BatchId>?,
    val plantingSiteIds: List<PlantingSiteId>?,
)

data class AssignProjectInternalUserRequestPayload(
    val userId: UserId,
    val role: ProjectInternalRole? = null,
    val roleName: String? = null
)

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

data class ProjectUserRoleResponsePayload(
    val userId: UserId,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val role: ProjectInternalRole? = null,
    val roleName: String? = null
) {
  constructor(
      user: TerrawareUser,
      projectInternalUser: ProjectInternalUsersRow
  ) : this(
      userId = user.userId,
      email = user.email,
      firstName = user.firstName,
      lastName = user.lastName,
      role = projectInternalUser.projectInternalRoleId,
      roleName = projectInternalUser.roleName,
  )
}

data class ListProjectUserRolesResponsePayload(val users: List<ProjectUserRoleResponsePayload>) :
    SuccessResponsePayload
