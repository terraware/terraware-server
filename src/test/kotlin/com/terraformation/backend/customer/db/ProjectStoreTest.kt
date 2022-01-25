package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectOrganizationWideException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.tables.daos.ProjectTypeSelectionsDao
import com.terraformation.backend.db.tables.daos.ProjectUsersDao
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.pojos.ProjectUsersRow
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class ProjectStoreTest : DatabaseTest(), RunsAsUser {
  override val user: UserModel = mockk()

  private val clock: Clock = mockk()
  private lateinit var projectsDao: ProjectsDao
  private lateinit var projectTypeSelectionsDao: ProjectTypeSelectionsDao
  private lateinit var projectUsersDao: ProjectUsersDao
  private lateinit var store: ProjectStore

  private val organizationId = OrganizationId(1)
  private val projectId = ProjectId(2)

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    every { user.canCreateProject(any()) } returns true
    every { user.canListProjects(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canUpdateProject(any()) } returns true
    every { user.canAddProjectUser(any()) } returns true
    every { user.canRemoveProjectUser(any()) } returns true

    val jooqConfig = dslContext.configuration()

    projectsDao = ProjectsDao(jooqConfig)
    projectTypeSelectionsDao = ProjectTypeSelectionsDao(jooqConfig)
    projectUsersDao = ProjectUsersDao(jooqConfig)
    store = ProjectStore(clock, dslContext, projectsDao, projectTypeSelectionsDao)

    insertOrganization(organizationId)
    insertProject(projectId, organizationId = organizationId)
  }

  @Test
  fun `addProjectUser adds user to project`() {
    val userId = UserId(100)
    insertUser(userId)
    insertOrganizationUser(userId, organizationId)

    store.addUser(projectId, userId)

    val expected = listOf(ProjectUsersRow(userId, projectId, Instant.EPOCH, Instant.EPOCH))
    val actual = projectUsersDao.fetchByProjectId(projectId)

    assertEquals(expected, actual)
  }

  @Test
  fun `addProjectUser throws exception if no permission to add users`() {
    every { user.canAddProjectUser(any()) } returns false

    assertThrows<AccessDeniedException> { store.addUser(projectId, UserId(1)) }
  }

  @Test
  fun `addProjectUser throws exception if user is not a member of organization`() {
    val userId = UserId(100)
    insertUser(userId)

    assertThrows<UserNotFoundException> { store.addUser(projectId, userId) }
  }

  @Test
  fun `addProjectUser throws exception if project is organization-wide`() {
    val userId = UserId(100)
    insertUser(userId)
    insertOrganizationUser(userId, organizationId)

    projectsDao.update(projectsDao.fetchOneById(projectId)!!.copy(organizationWide = true))

    assertThrows<ProjectOrganizationWideException> { store.addUser(projectId, userId) }
  }

  @Test
  fun `removeProjectUser removes user from project`() {
    val otherProjectId = ProjectId(3)
    insertProject(otherProjectId, organizationId = organizationId)

    val userId = UserId(100)
    insertUser(userId)
    insertOrganizationUser(userId, organizationId)
    insertProjectUser(userId, projectId)
    insertProjectUser(userId, otherProjectId)

    store.removeUser(projectId, userId)

    val expected = listOf(ProjectUsersRow(userId, otherProjectId, Instant.EPOCH, Instant.EPOCH))
    val actual = projectUsersDao.fetchByUserId(userId)

    assertEquals(expected, actual)
  }

  @Test
  fun `removeProjectUser throws exception if no permission to remove users`() {
    every { user.canRemoveProjectUser(any()) } returns false

    assertThrows<AccessDeniedException> { store.removeUser(projectId, UserId(1)) }
  }

  @Test
  fun `removeProjectUser throws exception if user is not a member of project`() {
    val userId = UserId(100)
    insertUser(userId)

    assertThrows<UserNotFoundException> { store.removeUser(projectId, userId) }
  }
}
