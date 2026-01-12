package com.terraformation.backend.customer.db

import com.terraformation.backend.accelerator.event.CohortProjectAddedEvent
import com.terraformation.backend.accelerator.event.CohortProjectRemovedEvent
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.event.ProjectCreatedEvent
import com.terraformation.backend.customer.event.ProjectDeletedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectInternalUserAddedEvent
import com.terraformation.backend.customer.event.ProjectInternalUserRemovedEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.customer.event.ProjectRenamedEventValues
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.NewProjectModel
import com.terraformation.backend.customer.model.ProjectInternalUserModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectNameInUseException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectInternalUsersRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_INTERNAL_USERS
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException

@Named
class ProjectStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val parentStore: ParentStore,
    private val projectsDao: ProjectsDao,
) {
  fun fetchOneById(projectId: ProjectId): ExistingProjectModel {
    requirePermissions { readProject(projectId) }

    return projectsDao.fetchOneById(projectId)?.let { ProjectModel.of(it) }
        ?: throw ProjectNotFoundException(projectId)
  }

  fun fetchByOrganizationId(organizationId: OrganizationId): List<ExistingProjectModel> {
    requirePermissions { readOrganization(organizationId) }

    return projectsDao.fetchByOrganizationId(organizationId).map { ProjectModel.of(it) }
  }

  fun findAll(): List<ExistingProjectModel> {
    return projectsDao
        .fetchByOrganizationId(*currentUser().organizationRoles.keys.toTypedArray())
        .map { ProjectModel.of(it) }
  }

  fun create(model: NewProjectModel): ProjectId {
    requirePermissions { createProject(model.organizationId) }

    val row =
        ProjectsRow(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            description = model.description,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = model.name,
            organizationId = model.organizationId,
        )

    try {
      projectsDao.insert(row)
    } catch (e: DuplicateKeyException) {
      throw ProjectNameInUseException(model.name)
    }

    val projectId = row.id!!

    eventPublisher.publishEvent(
        ProjectCreatedEvent(
            name = model.name,
            organizationId = model.organizationId,
            projectId = projectId,
        )
    )

    return projectId
  }

  fun delete(projectId: ProjectId) {
    requirePermissions { deleteProject(projectId) }

    val organizationId =
        parentStore.getOrganizationId(projectId) ?: throw ProjectNotFoundException(projectId)

    dslContext.transaction { _ ->
      eventPublisher.publishEvent(ProjectDeletionStartedEvent(projectId))

      projectsDao.deleteById(projectId)

      eventPublisher.publishEvent(
          ProjectDeletedEvent(organizationId = organizationId, projectId = projectId)
      )
    }
  }

  fun update(projectId: ProjectId, updateFunc: (ExistingProjectModel) -> ExistingProjectModel) {
    requirePermissions { updateProject(projectId) }

    val existing = fetchOneById(projectId)
    val updated = updateFunc(existing)

    dslContext.transaction { _ ->
      try {
        dslContext
            .update(PROJECTS)
            .set(PROJECTS.DESCRIPTION, updated.description)
            .set(PROJECTS.MODIFIED_BY, currentUser().userId)
            .set(PROJECTS.MODIFIED_TIME, clock.instant())
            .set(PROJECTS.NAME, updated.name)
            .where(PROJECTS.ID.eq(projectId))
            .execute()
      } catch (e: DuplicateKeyException) {
        throw ProjectNameInUseException(updated.name)
      }

      if (existing.name != updated.name) {
        eventPublisher.publishEvent(
            ProjectRenamedEvent(
                changedFrom = ProjectRenamedEventValues(name = existing.name),
                changedTo = ProjectRenamedEventValues(name = updated.name),
                organizationId = existing.organizationId,
                projectId = projectId,
            )
        )
      }
    }
  }

  fun addInternalUsers(projectId: ProjectId, users: Collection<ProjectInternalUserModel>) {
    requirePermissions { updateProjectInternalUsers(projectId) }

    val organizationId =
        parentStore.getOrganizationId(projectId) ?: throw ProjectNotFoundException(projectId)

    dslContext.transaction { _ ->
      with(PROJECT_INTERNAL_USERS) {
        val currentUserId = currentUser().userId
        val now = clock.instant()
        dslContext
            .insertInto(
                this,
                PROJECT_ID,
                USER_ID,
                PROJECT_INTERNAL_ROLE_ID,
                ROLE_NAME,
                CREATED_BY,
                CREATED_TIME,
                MODIFIED_BY,
                MODIFIED_TIME,
            )
            .apply {
              users.forEach {
                values(
                    projectId,
                    it.userId,
                    it.role,
                    it.roleName,
                    currentUserId,
                    now,
                    currentUserId,
                    now,
                )
              }
            }
            .onConflict(PROJECT_ID, USER_ID)
            .doUpdate()
            .set(PROJECT_INTERNAL_ROLE_ID, DSL.excluded(PROJECT_INTERNAL_ROLE_ID))
            .set(ROLE_NAME, DSL.excluded(ROLE_NAME))
            .set(MODIFIED_BY, DSL.excluded(MODIFIED_BY))
            .execute()

        users.forEach { user ->
          eventPublisher.publishEvent(
              ProjectInternalUserAddedEvent(
                  projectId,
                  organizationId,
                  user.userId,
                  user.role,
                  user.roleName,
              )
          )
        }
      }
    }
  }

  fun removeInternalUsers(projectId: ProjectId, userIds: Collection<UserId>) {
    requirePermissions { updateProjectInternalUsers(projectId) }

    val organizationId =
        parentStore.getOrganizationId(projectId) ?: throw ProjectNotFoundException(projectId)
    dslContext.transaction { _ ->
      with(PROJECT_INTERNAL_USERS) {
        val userIdsDeleted =
            dslContext
                .deleteFrom(this)
                .where(PROJECT_ID.eq(projectId))
                .and(USER_ID.`in`(userIds))
                .returning(USER_ID)
                .fetch(USER_ID.asNonNullable())

        userIdsDeleted.forEach { userId ->
          eventPublisher.publishEvent(
              ProjectInternalUserRemovedEvent(projectId, organizationId, userId)
          )
        }
      }
    }
  }

  fun fetchInternalUsers(
      projectId: ProjectId,
      role: ProjectInternalRole? = null,
  ): List<ProjectInternalUsersRow> {
    requirePermissions { readProject(projectId) }

    val conditions =
        listOfNotNull(
            PROJECT_INTERNAL_USERS.PROJECT_ID.eq(projectId),
            role?.let { PROJECT_INTERNAL_USERS.PROJECT_INTERNAL_ROLE_ID.eq(it) },
        )

    return dslContext
        .selectFrom(PROJECT_INTERNAL_USERS)
        .where(conditions)
        .fetchInto(ProjectInternalUsersRow::class.java)
  }

  /**
   * Sets or clears a project's cohort ID.
   *
   * This is a separate function from the regular [update] method because its permission structure
   * is different.
   */
  fun updateCohort(projectId: ProjectId, cohortId: CohortId?) {
    val existingRow =
        projectsDao.fetchOneById(projectId) ?: throw ProjectNotFoundException(projectId)
    val existingCohortId = existingRow.cohortId

    if (existingCohortId == cohortId) {
      return
    }

    if (existingCohortId != null) {
      requirePermissions { deleteCohortProject(existingCohortId, projectId) }
    }
    if (cohortId != null) {
      requirePermissions { addCohortProject(cohortId, projectId) }
    }

    dslContext
        .update(PROJECTS)
        .set(PROJECTS.MODIFIED_BY, currentUser().userId)
        .set(PROJECTS.MODIFIED_TIME, clock.instant())
        .set(PROJECTS.COHORT_ID, cohortId)
        .where(PROJECTS.ID.eq(projectId))
        .execute()

    if (existingCohortId != null) {
      eventPublisher.publishEvent(
          CohortProjectRemovedEvent(
              cohortId = existingCohortId,
              projectId = projectId,
              removedBy = currentUser().userId,
          )
      )
    }
    if (cohortId != null) {
      eventPublisher.publishEvent(
          CohortProjectAddedEvent(
              addedBy = currentUser().userId,
              cohortId = cohortId,
              projectId = projectId,
          )
      )
    }
  }
}
