package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.ProjectStatus
import com.terraformation.backend.db.ProjectType
import com.terraformation.backend.db.UserAlreadyInProjectException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.tables.daos.ProjectTypeSelectionsDao
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.pojos.ProjectTypeSelectionsRow
import com.terraformation.backend.db.tables.pojos.ProjectsRow
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_TYPE_SELECTIONS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import java.time.LocalDate
import javax.annotation.ManagedBean
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class ProjectStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val projectsDao: ProjectsDao,
    private val projectTypeSelectionsDao: ProjectTypeSelectionsDao,
) {
  private val log = perClassLogger()

  /** Returns all the projects the user has access to. */
  fun fetchAll(): List<ProjectModel> {
    val projectIds = currentUser().projectRoles.keys

    return if (projectIds.isEmpty()) {
      emptyList()
    } else {
      fetch(PROJECTS.ID.`in`(projectIds))
    }
  }

  /** Returns a project if the user has access to it. */
  fun fetchById(projectId: ProjectId): ProjectModel? {
    return if (projectId in currentUser().projectRoles) {
      fetch(PROJECTS.ID.eq(projectId)).firstOrNull()
    } else {
      log.warn("User ${currentUser().userId} attempted to fetch project $projectId")
      null
    }
  }

  /** Returns all the projects the user has access to in an organization. */
  fun fetchByOrganization(organizationId: OrganizationId): List<ProjectModel> {
    requirePermissions { listProjects(organizationId) }

    val projectIds = currentUser().projectRoles.keys

    return if (projectIds.isEmpty()) {
      emptyList()
    } else {
      fetch(PROJECTS.ORGANIZATION_ID.eq(organizationId).and(PROJECTS.ID.`in`(projectIds)))
    }
  }

  private fun fetch(condition: Condition): List<ProjectModel> {
    val projectTypesMultiset =
        DSL.multiset(
                DSL.select(PROJECT_TYPE_SELECTIONS.PROJECT_TYPE_ID)
                    .from(PROJECT_TYPE_SELECTIONS)
                    .where(PROJECT_TYPE_SELECTIONS.PROJECT_ID.eq(PROJECTS.ID)))
            .convertFrom { result -> result.map { it.value1() } }

    return dslContext
        .select(PROJECTS.asterisk(), projectTypesMultiset)
        .from(PROJECTS)
        .where(condition)
        .fetch { row -> ProjectModel(row, null, projectTypesMultiset) }
  }

  fun create(
      organizationId: OrganizationId,
      name: String,
      description: String? = null,
      startDate: LocalDate? = null,
      status: ProjectStatus? = null,
      types: Collection<ProjectType> = emptyList()
  ): ProjectModel {
    requirePermissions { createProject(organizationId) }

    val projectsRow =
        ProjectsRow(
            createdTime = clock.instant(),
            description = description,
            modifiedTime = clock.instant(),
            name = name,
            organizationId = organizationId,
            startDate = startDate,
            statusId = status,
        )

    dslContext.transaction { _ ->
      projectsDao.insert(projectsRow)
      types.forEach { type ->
        projectTypeSelectionsDao.insert(ProjectTypeSelectionsRow(projectsRow.id, type))
      }
    }

    return projectsRow.toModel(types.toSet())
  }

  fun update(
      projectId: ProjectId,
      description: String?,
      name: String,
      startDate: LocalDate?,
      status: ProjectStatus?,
      types: Collection<ProjectType>
  ) {
    requirePermissions { updateProject(projectId) }

    val existing = fetchById(projectId) ?: throw ProjectNotFoundException(projectId)
    val typesSet = types.toSet()

    dslContext.transaction { _ ->
      with(PROJECTS) {
        dslContext
            .update(PROJECTS)
            .set(DESCRIPTION, description)
            .set(MODIFIED_TIME, clock.instant())
            .set(NAME, name)
            .set(START_DATE, startDate)
            .set(STATUS_ID, status)
            .where(ID.eq(projectId))
            .execute()
      }

      with(PROJECT_TYPE_SELECTIONS) {
        val typesToRemove = existing.types - typesSet
        if (typesToRemove.isNotEmpty()) {
          dslContext
              .deleteFrom(PROJECT_TYPE_SELECTIONS)
              .where(PROJECT_ID.eq(projectId))
              .and(PROJECT_TYPE_ID.`in`(typesToRemove))
              .execute()
        }

        val typesToInsert = typesSet - existing.types
        typesToInsert.forEach { type ->
          projectTypeSelectionsDao.insert(ProjectTypeSelectionsRow(projectId, type))
        }
      }
    }
  }

  /**
   * Adds a user to a project. The user must already be a member of the project's organization.
   *
   * @throws UserAlreadyInProjectException The user is already a member of the project.
   * @throws UserNotFoundException The user is not in the organization.
   */
  fun addUser(projectId: ProjectId, userId: UserId) {
    requirePermissions { addProjectUser(projectId) }

    val isInOrganization =
        dslContext
            .selectOne()
            .from(ORGANIZATION_USERS)
            .join(PROJECTS)
            .on(ORGANIZATION_USERS.ORGANIZATION_ID.eq(PROJECTS.ORGANIZATION_ID))
            .where(ORGANIZATION_USERS.USER_ID.eq(userId))
            .and(PROJECTS.ID.eq(projectId))
            .fetch()
            .isNotEmpty
    if (!isInOrganization) {
      throw UserNotFoundException(userId)
    }

    try {
      with(PROJECT_USERS) {
        dslContext
            .insertInto(PROJECT_USERS)
            .set(PROJECT_ID, projectId)
            .set(USER_ID, userId)
            .set(CREATED_TIME, clock.instant())
            .set(MODIFIED_TIME, clock.instant())
            .execute()
      }
    } catch (e: DuplicateKeyException) {
      throw UserAlreadyInProjectException(userId, projectId)
    }

    log.info("Added user $userId to project $projectId")
  }

  /**
   * Removes a user from a project.
   *
   * @throws UserNotFoundException The user is not a member of the project.
   */
  fun removeUser(projectId: ProjectId, userId: UserId) {
    requirePermissions { removeProjectUser(projectId) }

    val rowsDeleted =
        dslContext
            .deleteFrom(PROJECT_USERS)
            .where(PROJECT_USERS.USER_ID.eq(userId))
            .and(PROJECT_USERS.PROJECT_ID.eq(projectId))
            .execute()
    if (rowsDeleted < 1) {
      throw UserNotFoundException(userId)
    }
  }
}
