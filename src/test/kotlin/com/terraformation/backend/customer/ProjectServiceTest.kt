package com.terraformation.backend.customer

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class ProjectServiceTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(ORGANIZATIONS, PROJECTS)

  private val otherUserId = UserId(100)

  private val clock: Clock = mockk()

  private lateinit var projectStore: ProjectStore
  private lateinit var permissionStore: PermissionStore
  private lateinit var service: ProjectService

  @BeforeEach
  fun setUp() {
    projectStore = ProjectStore(clock, dslContext, projectsDao, projectTypeSelectionsDao)
    permissionStore = PermissionStore(dslContext)
    service = ProjectService(dslContext, permissionStore, projectStore)

    every { clock.instant() } returns Instant.EPOCH

    every { user.canReadProject(projectId) } returns true
    every { user.canAddProjectUser(projectId) } returns true
    every { user.projectRoles } returns mapOf(projectId to Role.OWNER)

    insertSiteData()
  }

  @Test
  fun `addUser throws exception if user has no permission to add user to project`() {
    every { user.canAddProjectUser(projectId) } returns false
    assertThrows<AccessDeniedException> { service.addUser(projectId, otherUserId) }
  }

  @Test
  fun `addUser throws exception if user has no permission to read project`() {
    every { user.canAddProjectUser(projectId) } returns false
    every { user.canReadProject(projectId) } returns false
    assertThrows<ProjectNotFoundException> { service.addUser(projectId, otherUserId) }
  }

  @Test
  fun `addUser throws exception if target user does not belong to the project's organization`() {
    insertUser(otherUserId)

    assertThrows<IllegalArgumentException> { service.addUser(projectId, otherUserId) }
  }

  @Test
  fun `addUser adds user to project when permissions and membership are valid`() {
    insertUser(otherUserId)

    assertFalse(
        projectId in permissionStore.fetchProjectRoles(otherUserId),
        "User should not have project roles for project that user was not added to")

    insertOrganizationUser(otherUserId)

    service.addUser(projectId, otherUserId)

    assertTrue(
        projectId in permissionStore.fetchProjectRoles(otherUserId),
        "User should have a project role for project that user was added to")
  }
}
