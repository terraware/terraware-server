package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.customer.model.toModel
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.UserAlreadyInProjectException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.pojos.ProjectsRow
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException

@ManagedBean
class ProjectStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val projectsDao: ProjectsDao
) {
  private val log = perClassLogger()

  /** Returns all the projects the user has access to. */
  fun fetchAll(): List<ProjectModel> {
    val user = currentUser()
    val projectIds = user.projectRoles.keys
    return projectsDao.fetchById(*projectIds.toTypedArray()).map { it.toModel() }
  }

  /** Returns a project if the user has access to it. */
  fun fetchById(projectId: ProjectId): ProjectModel? {
    return if (projectId in currentUser().projectRoles) {
      projectsDao.fetchOneById(projectId)?.toModel()
    } else {
      log.warn("User ${currentUser().userId} attempted to fetch project $projectId")
      null
    }
  }

  /** Returns all the projects the user has access to in an organization. */
  fun fetchByOrganization(organizationId: OrganizationId): List<ProjectModel> {
    requirePermissions { listProjects(organizationId) }

    val accessibleProjects = currentUser().projectRoles
    return projectsDao
        .fetchByOrganizationId(organizationId)
        .filter { it.id in accessibleProjects }
        .map { it.toModel() }
  }

  fun create(organizationId: OrganizationId, name: String): ProjectModel {
    requirePermissions { createProject(organizationId) }

    val projectsRow =
        ProjectsRow(
            organizationId = organizationId,
            name = name,
            createdTime = clock.instant(),
            modifiedTime = clock.instant())

    projectsDao.insert(projectsRow)
    return projectsRow.toModel()
  }

  fun update(projectId: ProjectId, name: String) {
    requirePermissions { updateProject(projectId) }

    val existing = projectsDao.fetchOneById(projectId) ?: throw ProjectNotFoundException(projectId)

    projectsDao.update(existing.copy(name = name))
  }

  fun addUser(projectId: ProjectId, userId: UserId) {
    requirePermissions { addProjectUser(projectId) }

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

  fun removeUser(projectId: ProjectId, userId: UserId): Boolean {
    requirePermissions { removeProjectUser(projectId) }

    val rowsDeleted =
        dslContext
            .deleteFrom(PROJECT_USERS)
            .where(PROJECT_USERS.USER_ID.eq(userId))
            .and(PROJECT_USERS.PROJECT_ID.eq(projectId))
            .execute()
    return rowsDeleted > 0
  }
}
