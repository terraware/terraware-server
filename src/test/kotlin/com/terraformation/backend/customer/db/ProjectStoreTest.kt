package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectRemovedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.NewProjectModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNameInUseException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ProjectStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: ProjectStore by lazy {
    ProjectStore(clock, dslContext, eventPublisher, projectsDao)
  }

  private lateinit var organizationId: OrganizationId
  private val projectId: ProjectId by lazy {
    insertProject(description = "Description 1", name = "Project 1")
  }

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Admin)
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
      insertOrganizationUser(role = Role.Contributor)

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
      deleteOrganizationUser()

      assertThrows<ProjectNotFoundException> { store.fetchOneById(projectId) }
    }
  }

  @Nested
  inner class FetchByOrganizationId {
    @Test
    fun `fetches projects`() {
      val currentUserId = user.userId
      val projectId1 = insertProject(description = "Description 1", name = "Project 1")
      val projectId2 = insertProject(name = "Project 2")
      insertOrganization()
      insertProject(name = "Other org project")

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
      deleteOrganizationUser()

      assertThrows<OrganizationNotFoundException> { store.fetchByOrganizationId(organizationId) }
    }
  }

  @Nested
  inner class FindAll {
    @Test
    fun `fetches projects across organizations`() {
      val currentUserId = user.userId
      val otherUserOrganizationId = insertOrganization()
      val nonMemberOrganizationId = insertOrganization()

      insertOrganizationUser(organizationId = organizationId, role = Role.Contributor)
      insertOrganizationUser(organizationId = otherUserOrganizationId, role = Role.Contributor)

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
      insertOrganizationUser(role = Role.Contributor)

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
    @Test
    fun `sets participant`() {
      val participantId = insertParticipant()

      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

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

      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      store.updateParticipant(projectIdWithParticipant, null)

      assertNull(
          projectsDao.fetchOneById(projectIdWithParticipant)!!.participantId, "Participant ID")
      eventPublisher.assertEventPublished(
          ParticipantProjectRemovedEvent(participantId, projectIdWithParticipant, user.userId))
    }

    @Test
    fun `throws exception if no permission to set participant`() {
      val participantId = insertParticipant()

      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertThrows<AccessDeniedException> { store.updateParticipant(projectId, participantId) }
    }

    @Test
    fun `throws exception if no permission to clear participant`() {
      val participantId = insertParticipant()
      val projectIdWithParticipant = insertProject(participantId = participantId)

      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertThrows<AccessDeniedException> {
        store.updateParticipant(projectIdWithParticipant, null)
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
}
