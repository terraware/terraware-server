package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.ProjectOrganizationWideException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.tables.pojos.ProjectUsersRow
import com.terraformation.backend.mockUser
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
  override val user: TerrawareUser = mockUser()

  private val clock: Clock = mockk()
  private lateinit var store: ProjectStore

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    every { user.canCreateProject(any()) } returns true
    every { user.canListProjects(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canUpdateProject(any()) } returns true
    every { user.canAddProjectUser(any()) } returns true
    every { user.canRemoveProjectUser(any(), any()) } returns true

    store = ProjectStore(clock, dslContext, projectsDao, projectTypeSelectionsDao)

    insertSiteData()
  }

  @Test
  fun `addProjectUser adds user to project`() {
    val userId = UserId(100)
    insertUser(userId)
    insertOrganizationUser(userId)

    store.addUser(projectId, userId)

    val expected =
        listOf(
            ProjectUsersRow(
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                projectId = projectId,
                userId = userId))
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
    insertOrganizationUser(userId)

    projectsDao.update(projectsDao.fetchOneById(projectId)!!.copy(organizationWide = true))

    assertThrows<ProjectOrganizationWideException> { store.addUser(projectId, userId) }
  }

  @Test
  fun `removeProjectUser removes user from project`() {
    val otherProjectId = ProjectId(3)
    insertProject(otherProjectId)

    val userId = UserId(100)
    insertUser(userId)
    insertOrganizationUser(userId)
    insertProjectUser(userId)
    insertProjectUser(userId, otherProjectId)

    store.removeUser(projectId, userId)

    val expected =
        listOf(
            ProjectUsersRow(
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                projectId = otherProjectId,
                userId = userId))
    val actual = projectUsersDao.fetchByUserId(userId)

    assertEquals(expected, actual)
  }

  @Test
  fun `removeProjectUser throws exception if no permission to remove users`() {
    every { user.canRemoveProjectUser(any(), any()) } returns false

    assertThrows<AccessDeniedException> { store.removeUser(projectId, UserId(1)) }
  }

  @Test
  fun `removeProjectUser throws exception if user is not a member of project`() {
    val userId = UserId(100)
    insertUser(userId)

    assertThrows<UserNotFoundException> { store.removeUser(projectId, userId) }
  }

  @Test
  fun `countUsers includes admins and project members`() {
    val adminUserId = UserId(100)
    val contributorUserId = UserId(101)
    val otherProjectId = ProjectId(3)

    insertProject(otherProjectId)
    insertUser(adminUserId)
    insertUser(contributorUserId)
    insertOrganizationUser(adminUserId, role = Role.ADMIN)
    insertOrganizationUser(contributorUserId, role = Role.CONTRIBUTOR)
    insertProjectUser(contributorUserId)

    val expected = mapOf(projectId to 2, otherProjectId to 1)
    val actual = store.countUsers(listOf(projectId, otherProjectId))

    assertEquals(expected, actual)
  }

  @Test
  fun `countUsers returns organization user count for organization-wide projects`() {
    val adminUserId = UserId(100)
    val contributorUserId = UserId(101)
    val orgWideProjectId = ProjectId(3)

    insertProject(orgWideProjectId, organizationWide = true)
    insertUser(adminUserId)
    insertUser(contributorUserId)
    insertOrganizationUser(adminUserId, role = Role.ADMIN)
    insertOrganizationUser(contributorUserId, role = Role.CONTRIBUTOR)

    val expected = mapOf(projectId to 1, orgWideProjectId to 2)
    val actual = store.countUsers(listOf(projectId, orgWideProjectId))

    assertEquals(expected, actual)
  }

  @Test
  fun `countUsers does not double-count admins who were added to project`() {
    val adminUserId = UserId(100)
    val contributorUserId = UserId(101)

    insertUser(adminUserId)
    insertUser(contributorUserId)
    insertOrganizationUser(adminUserId, role = Role.ADMIN)
    insertOrganizationUser(contributorUserId, role = Role.CONTRIBUTOR)
    insertProjectUser(adminUserId)
    insertProjectUser(contributorUserId)

    val actual = store.countUsers(projectId)

    assertEquals(2, actual)
  }

  @Test
  fun `countUsers throws exception if user does not have permission to read projects`() {
    every { user.canReadProject(projectId) } returns false

    assertThrows<ProjectNotFoundException>("countUsers with single project ID") {
      store.countUsers(projectId)
    }
    assertThrows<ProjectNotFoundException>("countUsers with project ID list") {
      store.countUsers(listOf(projectId))
    }
  }

  @Test
  fun `fetchEmailRecipients only returns opted-in project members and opted-in admins`() {
    val optedInAdmin = UserId(100)
    val optedOutAdmin = UserId(101)
    val optedInNonMember = UserId(102)
    val optedInMember = UserId(103)
    val optedOutMember = UserId(104)

    insertUser(optedInAdmin, email = "optedInAdmin@x.com", emailNotificationsEnabled = true)
    insertUser(optedOutAdmin, email = "optedOutAdmin@x.com")
    insertUser(optedInNonMember, email = "optedInNonMember@x.com", emailNotificationsEnabled = true)
    insertUser(optedInMember, email = "optedInMember@x.com", emailNotificationsEnabled = true)
    insertUser(optedOutMember, email = "optedOutMember@x.com")

    insertOrganizationUser(optedInAdmin, role = Role.ADMIN)
    insertOrganizationUser(optedOutAdmin, role = Role.ADMIN)
    insertOrganizationUser(optedInNonMember, role = Role.CONTRIBUTOR)
    insertOrganizationUser(optedInMember, role = Role.CONTRIBUTOR)
    insertOrganizationUser(optedOutMember, role = Role.CONTRIBUTOR)

    insertProjectUser(optedInMember)
    insertProjectUser(optedOutMember)

    val expected = setOf("optedInAdmin@x.com", "optedInMember@x.com")
    val actual = store.fetchEmailRecipients(projectId).toSet()

    assertEquals(expected, actual)
  }
}
