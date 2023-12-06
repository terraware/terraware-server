package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.NewProjectModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNameInUseException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ProjectStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val projectId: ProjectId by lazy {
    insertProject(description = "Description 1", name = "Project 1")
  }

  private val clock = TestClock()
  private val store: ProjectStore by lazy { ProjectStore(clock, dslContext, projectsDao) }

  @BeforeEach
  fun setUp() {
    every { user.canCreateProject(any()) } returns true
    every { user.canDeleteProject(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canUpdateProject(any()) } returns true

    insertSiteData()
  }

  @Nested
  inner class Create {
    @Test
    fun `creates project`() {
      clock.instant = Instant.ofEpochSecond(123)
      val newProjectId =
          store.create(
              NewProjectModel(
                  description = "Project description",
                  id = null,
                  name = "Project name",
                  organizationId = organizationId))

      val expected =
          ProjectsRow(
              createdBy = user.userId,
              createdTime = clock.instant,
              description = "Project description",
              id = newProjectId,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              organizationId = organizationId,
              name = "Project name",
          )
      val actual = projectsDao.fetchOneById(newProjectId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canCreateProject(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.create(NewProjectModel(id = null, name = "Name", organizationId = organizationId))
      }
    }

    @Test
    fun `throws exception on duplicate name`() {
      insertProject()

      assertThrows<ProjectNameInUseException> {
        store.create(
            NewProjectModel(id = null, name = "Project 1", organizationId = organizationId))
      }
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `fetches project`() {
      val expected =
          ExistingProjectModel(
              description = "Description 1",
              id = projectId,
              name = "Project 1",
              organizationId = organizationId,
          )
      val actual = store.fetchOneById(projectId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canReadProject(any()) } returns false

      assertThrows<ProjectNotFoundException> { store.fetchOneById(projectId) }
    }
  }

  @Nested
  inner class FetchByOrganizationId {
    @Test
    fun `fetches projects`() {
      val projectId1 = insertProject(description = "Description 1", name = "Project 1")
      val projectId2 = insertProject(name = "Project 2")
      val otherOrganizationId = OrganizationId(2)
      insertOrganization(otherOrganizationId)
      insertProject(name = "Other org project", organizationId = otherOrganizationId)

      val expected =
          setOf(
              ExistingProjectModel(
                  description = "Description 1",
                  id = projectId1,
                  name = "Project 1",
                  organizationId = organizationId,
              ),
              ExistingProjectModel(
                  id = projectId2,
                  name = "Project 2",
                  organizationId = organizationId,
              ),
          )

      val actual = store.fetchByOrganizationId(organizationId).toSet()

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canReadOrganization(any()) } returns false

      assertThrows<OrganizationNotFoundException> { store.fetchByOrganizationId(organizationId) }
    }
  }

  @Nested
  inner class FindAll {
    @Test
    fun `fetches projects across organizations`() {
      val otherUserOrganizationId = OrganizationId(2)
      val nonMemberOrganizationId = OrganizationId(3)

      insertOrganization(otherUserOrganizationId)
      insertOrganization(nonMemberOrganizationId)
      insertOrganizationUser(organizationId = otherUserOrganizationId)

      every { user.organizationRoles } returns
          mapOf(organizationId to Role.Contributor, otherUserOrganizationId to Role.Contributor)

      val projectId1 = insertProject(name = "Project 1", organizationId = organizationId)
      val projectId2 = insertProject(name = "Project 2", organizationId = organizationId)
      val projectId3 = insertProject(name = "Project 3", organizationId = otherUserOrganizationId)
      insertProject(organizationId = nonMemberOrganizationId)

      val expected =
          setOf(
              ExistingProjectModel(
                  id = projectId1,
                  name = "Project 1",
                  organizationId = organizationId,
              ),
              ExistingProjectModel(
                  id = projectId2,
                  name = "Project 2",
                  organizationId = organizationId,
              ),
              ExistingProjectModel(
                  id = projectId3,
                  name = "Project 3",
                  organizationId = otherUserOrganizationId,
              ),
          )

      val actual = store.findAll().toSet()

      assertEquals(expected, actual)
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates editable fields`() {
      clock.instant = Instant.ofEpochSecond(123)

      val before = projectsDao.fetchOneById(projectId)!!

      store.update(projectId) {
        ExistingProjectModel(
            description = "New description",
            id = projectId,
            name = "New name",
            organizationId = OrganizationId(-1),
        )
      }

      val expected =
          before.copy(
              description = "New description",
              modifiedTime = clock.instant,
              name = "New name",
          )
      val actual = projectsDao.fetchOneById(projectId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canUpdateProject(any()) } returns false

      assertThrows<AccessDeniedException> { store.update(projectId) { it } }
    }

    @Test
    fun `throws exception on duplicate name`() {
      insertProject(name = "Existing name")
      val projectId2 = insertProject()

      assertThrows<ProjectNameInUseException> {
        store.update(projectId2) { it.copy(name = "Existing name") }
      }
    }
  }
}
