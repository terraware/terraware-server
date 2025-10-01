package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectRemovedEvent
import com.terraformation.backend.customer.event.ProjectDeletionStartedEvent
import com.terraformation.backend.customer.event.ProjectInternalUserAddedEvent
import com.terraformation.backend.customer.event.ProjectInternalUserRemovedEvent
import com.terraformation.backend.customer.event.ProjectRenamedEvent
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.NewProjectModel
import com.terraformation.backend.customer.model.ProjectInternalUserModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNameInUseException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectInternalUsersRow
import com.terraformation.backend.db.default_schema.tables.pojos.ProjectsRow
import com.terraformation.backend.db.default_schema.tables.records.OrganizationUsersRecord
import com.terraformation.backend.db.default_schema.tables.records.ProjectInternalUsersRecord
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_INTERNAL_USERS
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

class ProjectStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val parentStore: ParentStore by lazy { ParentStore(dslContext) }
  private val store: ProjectStore by lazy {
    ProjectStore(
        clock,
        dslContext,
        eventPublisher,
        parentStore,
        projectsDao,
        projectInternalUsersDao,
    )
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
                  organizationId = organizationId,
              )
          )

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
            NewProjectModel(id = null, name = "Project 1", organizationId = organizationId)
        )
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
  inner class AddInternalUsers {
    @Test
    fun `throws exception if no permission`() {
      deleteOrganizationUser()
      assertThrows<ProjectNotFoundException> {
        store.addInternalUsers(
            projectId,
            listOf(ProjectInternalUserModel(user.userId, ProjectInternalRole.ProjectLead)),
        )
      }
    }

    @Test
    fun `throws exception if no project`() {
      assertThrows<ProjectNotFoundException> {
        store.addInternalUsers(
            ProjectId(Long.MAX_VALUE),
            listOf(ProjectInternalUserModel(user.userId, ProjectInternalRole.ProjectLead)),
        )
      }
    }

    @Test
    fun `throws exception if role and roleName are specified`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      assertThrows<DataIntegrityViolationException> {
        store.addInternalUsers(
            projectId,
            listOf(
                ProjectInternalUserModel(user.userId, ProjectInternalRole.ProjectLead, "SomeRole")
            ),
        )
      }
    }

    @Test
    fun `throws exception if neither role or roleName are specified`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      assertThrows<IllegalArgumentException> {
        store.addInternalUsers(projectId, listOf(ProjectInternalUserModel(user.userId, null, null)))
      }
    }

    @Test
    fun `adds internal user with role`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUser() // other user doesn't get added

      store.addInternalUsers(
          projectId,
          listOf(ProjectInternalUserModel(user.userId, ProjectInternalRole.ProjectLead)),
      )

      assertEquals(
          listOf(createProjectInternalRow(role = ProjectInternalRole.ProjectLead)),
          store.fetchInternalUsers(projectId),
          "Should have added user to internal users",
      )

      eventPublisher.assertEventPublished(
          ProjectInternalUserAddedEvent(
              projectId,
              organizationId,
              user.userId,
              role = ProjectInternalRole.ProjectLead,
          )
      )
    }

    @Test
    fun `adds internal users with roleName`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      val userId2 = insertUser()
      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUser() // other user doesn't get added

      store.addInternalUsers(
          projectId,
          listOf(
              ProjectInternalUserModel(user.userId, roleName = "TheBestRole"),
              ProjectInternalUserModel(userId2, roleName = "AnotherRole"),
          ),
      )

      assertEquals(
          listOf(
              createProjectInternalRow(roleName = "TheBestRole"),
              createProjectInternalRow(userId = userId2, roleName = "AnotherRole"),
          ),
          store.fetchInternalUsers(projectId),
          "Should have added user to internal users",
      )
      eventPublisher.assertEventsPublished(
          listOf(
              ProjectInternalUserAddedEvent(
                  projectId,
                  organizationId,
                  user.userId,
                  roleName = "TheBestRole",
              ),
              ProjectInternalUserAddedEvent(
                  projectId,
                  organizationId,
                  userId2,
                  roleName = "AnotherRole",
              ),
          )
      )
    }

    @Test
    fun `updates role if called more than once for the same user`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      store.addInternalUsers(
          projectId,
          listOf(ProjectInternalUserModel(user.userId, ProjectInternalRole.ProjectLead)),
      )
      // diff role
      store.addInternalUsers(
          projectId,
          listOf(ProjectInternalUserModel(user.userId, ProjectInternalRole.Consultant)),
      )

      assertTableEquals(createProjectInternalRecord(role = ProjectInternalRole.Consultant))

      // roleName instead of role
      store.addInternalUsers(
          projectId,
          listOf(ProjectInternalUserModel(user.userId, roleName = "A Different Role")),
      )

      assertTableEquals(createProjectInternalRecord(role = null, roleName = "A Different Role"))
      eventPublisher.assertExactEventsPublished(
          listOf(
              ProjectInternalUserAddedEvent(
                  projectId,
                  organizationId,
                  user.userId,
                  role = ProjectInternalRole.ProjectLead,
              ),
              ProjectInternalUserAddedEvent(
                  projectId,
                  organizationId,
                  user.userId,
                  role = ProjectInternalRole.Consultant,
              ),
              ProjectInternalUserAddedEvent(
                  projectId,
                  organizationId,
                  user.userId,
                  roleName = "A Different Role",
              ),
          ),
          "Should have published events in specific order",
      )
    }
  }

  @Nested
  inner class RemoveInternalUsers {
    @Test
    fun `throws exception if no permission`() {
      deleteOrganizationUser()
      assertThrows<ProjectNotFoundException> {
        store.removeInternalUsers(projectId, listOf(user.userId))
      }
    }

    @Test
    fun `throws exception if no project`() {
      assertThrows<ProjectNotFoundException> {
        store.removeInternalUsers(ProjectId(Long.MAX_VALUE), listOf(user.userId))
      }
    }

    @Test
    fun `removes internal users with role, doesn't remove TF Contact`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.TerraformationContact)
      insertProjectInternalUser(projectId = projectId, role = ProjectInternalRole.ProjectLead)
      val userId2 = insertUser()
      insertProjectInternalUser(role = ProjectInternalRole.SocialLead)

      store.removeInternalUsers(projectId, listOf(user.userId, userId2))

      assertTableEmpty(PROJECT_INTERNAL_USERS, "Should have no internal users")

      eventPublisher.assertEventsPublished(
          listOf(
              ProjectInternalUserRemovedEvent(projectId, organizationId, user.userId),
              ProjectInternalUserRemovedEvent(projectId, organizationId, userId2),
          )
      )

      assertTableEquals(
          OrganizationUsersRecord(
              userId = user.userId,
              organizationId = organizationId,
              roleId = Role.TerraformationContact,
              createdTime = clock.instant,
              modifiedTime = clock.instant,
              createdBy = user.userId,
              modifiedBy = user.userId,
          )
      )
    }

    @Test
    fun `removes internal users with roleName`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertProjectInternalUser(projectId = projectId, roleName = "TheBestRole")
      val userId2 = insertUser()
      insertProjectInternalUser(roleName = "AnotherRole")

      store.removeInternalUsers(projectId, listOf(user.userId, userId2))

      assertEquals(
          emptyList<ProjectInternalUsersRow>(),
          store.fetchInternalUsers(projectId),
          "Should have no internal users",
      )
      eventPublisher.assertEventsPublished(
          listOf(
              ProjectInternalUserRemovedEvent(projectId, organizationId, user.userId),
              ProjectInternalUserRemovedEvent(projectId, organizationId, userId2),
          )
      )
    }

    @Test
    fun `no event published if user wasn't already on project`() {
      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      store.removeInternalUsers(projectId, listOf(user.userId))

      assertEquals(
          emptyList<ProjectInternalUsersRow>(),
          store.fetchInternalUsers(projectId),
          "Should still have no internal users",
      )
      eventPublisher.assertEventNotPublished<ProjectInternalUserRemovedEvent>()
    }
  }

  @Nested
  inner class FetchInternalUsers {
    @Test
    fun `throws exception if no permission`() {
      deleteOrganizationUser()
      assertThrows<ProjectNotFoundException> { store.fetchInternalUsers(projectId) }
    }

    @Test
    fun `returns internal users`() {
      insertProjectInternalUser(projectId = projectId, role = ProjectInternalRole.ProjectLead)
      val userId2 = insertUser()
      insertProjectInternalUser(roleName = "Rock Star")

      assertEquals(
          listOf(
              createProjectInternalRow(role = ProjectInternalRole.ProjectLead),
              createProjectInternalRow(userId = userId2, roleName = "Rock Star"),
          ),
          store.fetchInternalUsers(projectId),
          "Should have 2 internal users",
      )
    }

    @Test
    fun `filters internal users by role`() {
      insertProjectInternalUser(projectId = projectId, role = ProjectInternalRole.ProjectLead)
      val userId2 = insertUser()
      insertProjectInternalUser(projectId = projectId, role = ProjectInternalRole.CarbonLead)
      insertUser()
      insertProjectInternalUser(roleName = "Rock Star")

      assertEquals(
          listOf(createProjectInternalRow(userId = userId2, role = ProjectInternalRole.CarbonLead)),
          store.fetchInternalUsers(projectId, ProjectInternalRole.CarbonLead),
      )
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
          participantId,
          projectsDao.fetchOneById(projectId)!!.participantId,
          "Participant ID",
      )
      eventPublisher.assertEventPublished(
          ParticipantProjectAddedEvent(user.userId, participantId, projectId)
      )
    }

    @Test
    fun `clears participant`() {
      val participantId = insertParticipant()
      val projectIdWithParticipant = insertProject(participantId = participantId)

      insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      store.updateParticipant(projectIdWithParticipant, null)

      assertNull(
          projectsDao.fetchOneById(projectIdWithParticipant)!!.participantId,
          "Participant ID",
      )
      eventPublisher.assertEventPublished(
          ParticipantProjectRemovedEvent(participantId, projectIdWithParticipant, user.userId)
      )
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

  private fun createProjectInternalRow(
      role: ProjectInternalRole? = null,
      roleName: String? = null,
      userId: UserId = user.userId,
  ) =
      ProjectInternalUsersRow(
          projectId = projectId,
          projectInternalRoleId = role,
          roleName = roleName,
          userId = userId,
          createdBy = user.userId,
          createdTime = clock.instant,
          modifiedBy = user.userId,
          modifiedTime = clock.instant,
      )

  private fun createProjectInternalRecord(
      role: ProjectInternalRole? = null,
      roleName: String? = null,
  ) =
      ProjectInternalUsersRecord(
          projectId = projectId,
          projectInternalRoleId = role,
          roleName = roleName,
          userId = user.userId,
          createdBy = user.userId,
          createdTime = clock.instant,
          modifiedBy = user.userId,
          modifiedTime = clock.instant,
      )
}
