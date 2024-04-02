package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectRemovedEvent
import com.terraformation.backend.accelerator.model.ProjectCohortData
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.NewProjectModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNameInUseException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectDocumentSettingsRow
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.net.URI
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
  private val eventPublisher = TestEventPublisher()
  private val store: ProjectStore by lazy {
    ProjectStore(clock, dslContext, eventPublisher, projectsDao)
  }

  @BeforeEach
  fun setUp() {
    every { user.canCreateProject(any()) } returns true
    every { user.canDeleteProject(any()) } returns true
    every { user.canReadCohort(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canUpdateProject(any()) } returns true
    every { user.canUpdateProjectDocumentSettings(any()) } returns true

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
      val currentUserId = user.userId

      val expected =
          ExistingProjectModel(
              createdBy = currentUserId,
              createdTime = Instant.EPOCH,
              description = "Description 1",
              id = projectId,
              modifiedBy = currentUserId,
              modifiedTime = Instant.EPOCH,
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
      val currentUserId = user.userId
      insertOrganization(otherOrganizationId)
      insertProject(name = "Other org project", organizationId = otherOrganizationId)

      val expected =
          setOf(
              ExistingProjectModel(
                  createdBy = currentUserId,
                  createdTime = Instant.EPOCH,
                  description = "Description 1",
                  id = projectId1,
                  modifiedBy = currentUserId,
                  modifiedTime = Instant.EPOCH,
                  name = "Project 1",
                  organizationId = organizationId,
              ),
              ExistingProjectModel(
                  createdBy = currentUserId,
                  createdTime = Instant.EPOCH,
                  id = projectId2,
                  name = "Project 2",
                  modifiedBy = currentUserId,
                  modifiedTime = Instant.EPOCH,
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
      val currentUserId = user.userId

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
                  createdBy = currentUserId,
                  createdTime = Instant.EPOCH,
                  id = projectId1,
                  modifiedBy = currentUserId,
                  modifiedTime = Instant.EPOCH,
                  name = "Project 1",
                  organizationId = organizationId,
              ),
              ExistingProjectModel(
                  createdBy = currentUserId,
                  createdTime = Instant.EPOCH,
                  id = projectId2,
                  modifiedBy = currentUserId,
                  modifiedTime = Instant.EPOCH,
                  name = "Project 2",
                  organizationId = organizationId,
              ),
              ExistingProjectModel(
                  createdBy = currentUserId,
                  createdTime = Instant.EPOCH,
                  id = projectId3,
                  modifiedBy = currentUserId,
                  modifiedTime = Instant.EPOCH,
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
      val currentUserId = user.userId

      val before = projectsDao.fetchOneById(projectId)!!

      store.update(projectId) {
        ExistingProjectModel(
            description = "New description",
            id = ProjectId(-1),
            name = "New name",
            organizationId = OrganizationId(-1),
        )
      }

      val expected =
          before.copy(
              createdBy = currentUserId,
              createdTime = Instant.EPOCH,
              description = "New description",
              modifiedBy = currentUserId,
              modifiedTime = clock.instant,
              name = "New name",
          )
      val actual = projectsDao.fetchOneById(projectId)

      assertEquals(expected, actual)

      eventPublisher.assertEventPublished(ProjectRenamedEvent(projectId, before.name!!, "New name"))
    }

    @Test
    fun `does not publish ProjectRenamedEvent if name does not change`() {
      store.update(projectId) { it.copy(description = "New description") }

      eventPublisher.assertEventNotPublished<ProjectRenamedEvent>()
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

  @Nested
  inner class UpdateParticipant {
    @BeforeEach
    fun setUp() {
      every { user.canReadParticipant(any()) } returns true
    }

    @Test
    fun `sets participant`() {
      val participantId = insertParticipant()

      every { user.canAddParticipantProject(any(), any()) } returns true

      store.updateParticipant(projectId, participantId)

      assertEquals(
          participantId, projectsDao.fetchOneById(projectId)!!.participantId, "Participant ID")
      eventPublisher.assertEventPublished(
          ParticipantProjectAddedEvent(user.userId, participantId, projectId))
    }

    @Test
    fun `clears participant`() {
      val participantId = insertParticipant()
      val projectIdWithParticipant = insertProject(participantId = participantId)

      every { user.canDeleteParticipantProject(any(), any()) } returns true

      store.updateParticipant(projectIdWithParticipant, null)

      assertNull(
          projectsDao.fetchOneById(projectIdWithParticipant)!!.participantId, "Participant ID")
      eventPublisher.assertEventPublished(
          ParticipantProjectRemovedEvent(participantId, projectIdWithParticipant, user.userId))
    }

    @Test
    fun `throws exception if no permission to set participant`() {
      val participantId = insertParticipant()

      assertThrows<AccessDeniedException> { store.updateParticipant(projectId, participantId) }
    }

    @Test
    fun `throws exception if no permission to clear participant`() {
      val participantId = insertParticipant()
      val projectIdWithParticipant = insertProject(participantId = participantId)

      assertThrows<AccessDeniedException> {
        store.updateParticipant(projectIdWithParticipant, null)
      }
    }
  }

  @Nested
  inner class UpdateDocumentSettings {
    @Test
    fun `saves initial settings`() {
      val projectId = insertProject()

      val fileNaming = "naming"
      val googleFolderUrl = URI("https://google.com/")
      val dropboxFolderPath = "/x/y/z"

      store.updateDocumentSettings(projectId, fileNaming, googleFolderUrl, dropboxFolderPath)

      assertEquals(
          listOf(
              ProjectDocumentSettingsRow(
                  projectId, fileNaming, googleFolderUrl, dropboxFolderPath)),
          projectDocumentSettingsDao.findAll())
    }

    @Test
    fun `updates existing settings`() {
      val projectId = insertProject()

      projectDocumentSettingsDao.insert(
          ProjectDocumentSettingsRow(projectId, "old naming", URI("https://old"), "/old/path"))

      val fileNaming = "naming"
      val googleFolderUrl = URI("https://google.com/")
      val dropboxFolderPath = "/x/y/z"

      store.updateDocumentSettings(projectId, fileNaming, googleFolderUrl, dropboxFolderPath)

      assertEquals(
          listOf(
              ProjectDocumentSettingsRow(
                  projectId, fileNaming, googleFolderUrl, dropboxFolderPath)),
          projectDocumentSettingsDao.findAll())
    }

    @Test
    fun `throws exception if project does not exist`() {
      assertThrows<ProjectNotFoundException> {
        store.updateDocumentSettings(ProjectId(5000L), "naming", URI("file:///"), "/path")
      }
    }

    @Test
    fun `throws exception if no permission`() {
      val projectId = insertProject()

      every { user.canUpdateProjectDocumentSettings(projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.updateDocumentSettings(projectId, "naming", URI("file:///"), "/path")
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `publishes ProjectDeletionStartedEvent`() {
      val projectId = insertProject()

      store.delete(projectId)

      eventPublisher.assertEventPublished(ProjectDeletionStartedEvent(projectId))
    }
  }

  @Nested
  inner class FetchCohortData {
    @Test
    fun `fetches project's cohort data`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId1 = insertProject(participantId = participantId)

      val expected =
          ProjectCohortData(cohortId = cohortId, cohortPhase = CohortPhase.Phase0DueDiligence)
      val actual = store.fetchCohortData(projectId1)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns null if no permission to read cohort`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId1 = insertProject(participantId = participantId)

      every { user.canReadCohort(any()) } returns false

      val actual = store.fetchCohortData(projectId1)
      assertNull(actual)
    }

    @Test
    fun `returns null if the project is not associated to a participant which is associated to a cohort`() {
      val participantId = insertParticipant()
      val projectId1 = insertProject(participantId = participantId)

      val actual = store.fetchCohortData(projectId1)
      assertNull(actual)
    }

    @Test
    fun `returns null if the project is not associated to a participant`() {
      val actual = store.fetchCohortData(projectId)
      assertNull(actual)
    }
  }
}
