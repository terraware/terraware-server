package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.GlobalRole
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class AcceleratorOrganizationStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: AcceleratorOrganizationStore by lazy {
    AcceleratorOrganizationStore(dslContext)
  }

  @BeforeEach
  fun setUp() {
    insertUserGlobalRole(role = GlobalRole.ReadOnly)
  }

  @Nested
  inner class FetchWithUnassignedProjects {
    @Test
    fun `only returns unassigned projects in Accelerator tagged organizations`() {
      val acceleratorOrgId1 = insertOrganization()
      val acceleratorOrgId2 = insertOrganization()
      val nonAcceleratorOrgId = insertOrganization()
      val untaggedOrgId = insertOrganization()
      val participantId = insertParticipant()
      val currentUserId = user.userId

      insertOrganizationInternalTag(acceleratorOrgId1, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(acceleratorOrgId2, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(nonAcceleratorOrgId, InternalTagIds.Internal)

      insertProject(organizationId = nonAcceleratorOrgId)
      insertProject(organizationId = untaggedOrgId)
      insertProject(organizationId = acceleratorOrgId1, participantId = participantId)
      val unassignedProjectId1 = insertProject(organizationId = acceleratorOrgId1, name = "A")
      val unassignedProjectId2 = insertProject(organizationId = acceleratorOrgId2, name = "C")
      val unassignedProjectId3 = insertProject(organizationId = acceleratorOrgId2, name = "B")

      assertEquals(
          mapOf(
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = acceleratorOrgId1,
                  name = "Organization 1",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = unassignedProjectId1,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "A",
                          organizationId = acceleratorOrgId1,
                      ),
                  ),
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = acceleratorOrgId2,
                  name = "Organization 2",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = unassignedProjectId3,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "B",
                          organizationId = acceleratorOrgId2,
                      ),
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = unassignedProjectId2,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "C",
                          organizationId = acceleratorOrgId2,
                      ),
                  ),
          ),
          store.fetchWithUnassignedProjects(),
      )
    }

    @Test
    fun `throws exception if no permission to read internal tags`() {
      deleteUserGlobalRole(role = GlobalRole.ReadOnly)

      assertThrows<AccessDeniedException> { store.fetchWithUnassignedProjects() }
    }
  }

  @Nested
  inner class FindAllWithAcceleratorTag {
    @Test
    fun `returns both assigned and unassigned projects in Accelerator tagged organizations`() {
      val acceleratorOrgId1 = insertOrganization()
      val acceleratorOrgId2 = insertOrganization()
      val acceleratorOrgIdWithoutProjects = insertOrganization()
      val nonAcceleratorOrgId = insertOrganization()
      val untaggedOrgId = insertOrganization()
      val participantId = insertParticipant()
      val currentUserId = user.userId

      insertOrganizationInternalTag(acceleratorOrgId1, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(acceleratorOrgId2, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(acceleratorOrgIdWithoutProjects, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(nonAcceleratorOrgId, InternalTagIds.Internal)

      insertProject(organizationId = nonAcceleratorOrgId)
      insertProject(organizationId = untaggedOrgId)
      val assignedProjectId =
          insertProject(
              organizationId = acceleratorOrgId1,
              name = "D",
              participantId = participantId,
          )
      val unassignedProjectId1 = insertProject(organizationId = acceleratorOrgId1, name = "A")
      val unassignedProjectId2 = insertProject(organizationId = acceleratorOrgId2, name = "C")

      assertEquals(
          mapOf(
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = acceleratorOrgId1,
                  name = "Organization 1",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = unassignedProjectId1,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "A",
                          organizationId = acceleratorOrgId1,
                      ),
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = assignedProjectId,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "D",
                          organizationId = acceleratorOrgId1,
                          participantId = participantId,
                      ),
                  ),
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = acceleratorOrgId2,
                  name = "Organization 2",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = unassignedProjectId2,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "C",
                          organizationId = acceleratorOrgId2,
                      ),
                  ),
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = acceleratorOrgIdWithoutProjects,
                  name = "Organization 3",
                  totalUsers = 0,
              ) to emptyList(),
          ),
          store.findAllWithAcceleratorTag(),
      )
    }

    @Test
    fun `throws exception if no permission to read internal tags`() {
      deleteUserGlobalRole(role = GlobalRole.ReadOnly)

      assertThrows<AccessDeniedException> { store.fetchWithUnassignedProjects() }
    }
  }

  @Nested
  inner class FindAllWithProjectApplication {
    @Test
    fun `returns all organizations with a project that has an application`() {
      val projectApplicationOrg1Id = insertOrganization(name = "ProjectApplicationOrg1")
      val projectApplicationOrg1ProjectId = insertProject(organizationId = projectApplicationOrg1Id)
      insertApplication(
          internalName = "Org1ProjectApplication",
          projectId = projectApplicationOrg1ProjectId,
      )

      val projectApplicationOrg2Id = insertOrganization(name = "ProjectApplicationOrg2")
      val projectApplicationOrg2ProjectId = insertProject(organizationId = projectApplicationOrg2Id)
      insertApplication(
          internalName = "Org2ProjectApplication",
          projectId = projectApplicationOrg2ProjectId,
      )

      val otherOrgIdWithoutProjectApplication =
          insertOrganization(name = "OtherOrgWithoutProjectApplication")
      insertProject(organizationId = otherOrgIdWithoutProjectApplication)

      insertOrganization(name = "OtherOrgWithoutProjects")

      val currentUserId = user.userId

      assertEquals(
          mapOf(
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = projectApplicationOrg1Id,
                  name = "ProjectApplicationOrg1",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = projectApplicationOrg1ProjectId,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "Project 1",
                          organizationId = projectApplicationOrg1Id,
                      ),
                  ),
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = projectApplicationOrg2Id,
                  name = "ProjectApplicationOrg2",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = projectApplicationOrg2ProjectId,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "Project 2",
                          organizationId = projectApplicationOrg2Id,
                      ),
                  ),
          ),
          store.findAllWithProjectApplication(),
      )
    }

    @Test
    fun `throws exception if no permission to read all accelerator details (read only and higher)`() {
      deleteUserGlobalRole(role = GlobalRole.ReadOnly)

      assertThrows<AccessDeniedException> { store.findAllWithProjectApplication() }
    }
  }

  @Nested
  inner class FindAll {
    @Test
    fun `finds projects with applications, plus assigned and unassigned projects under orgs with tag`() {
      val acceleratorOrgId1 = insertOrganization()
      val acceleratorOrgId2 = insertOrganization()
      val acceleratorOrgIdWithoutProjects = insertOrganization()
      val nonAcceleratorOrgId = insertOrganization()
      val untaggedOrgId = insertOrganization()
      val participantId = insertParticipant()
      val currentUserId = user.userId

      insertOrganizationInternalTag(acceleratorOrgId1, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(acceleratorOrgId2, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(acceleratorOrgIdWithoutProjects, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(nonAcceleratorOrgId, InternalTagIds.Internal)

      insertProject(organizationId = nonAcceleratorOrgId)
      insertProject(organizationId = untaggedOrgId)
      val assignedProjectId =
          insertProject(
              organizationId = acceleratorOrgId1,
              name = "D",
              participantId = participantId,
          )
      val unassignedProjectId1 = insertProject(organizationId = acceleratorOrgId1, name = "A")
      val unassignedProjectId2 = insertProject(organizationId = acceleratorOrgId2, name = "C")

      val projectApplicationOrg1Id = insertOrganization(name = "ProjectApplicationOrg1")
      val projectApplicationOrg1ProjectId = insertProject(organizationId = projectApplicationOrg1Id)
      insertApplication(
          internalName = "Org1ProjectApplication",
          projectId = projectApplicationOrg1ProjectId,
      )

      val projectApplicationOrg2Id = insertOrganization(name = "ProjectApplicationOrg2")
      val projectApplicationOrg2ProjectId = insertProject(organizationId = projectApplicationOrg2Id)
      insertApplication(
          internalName = "Org2ProjectApplication",
          projectId = projectApplicationOrg2ProjectId,
      )

      val otherOrgIdWithoutProjectApplication =
          insertOrganization(name = "OtherOrgWithoutProjectApplication")
      insertProject(organizationId = otherOrgIdWithoutProjectApplication)

      insertOrganization(name = "OtherOrgWithoutProjects")

      assertEquals(
          mapOf(
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = acceleratorOrgId1,
                  name = "Organization 1",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = unassignedProjectId1,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "A",
                          organizationId = acceleratorOrgId1,
                      ),
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = assignedProjectId,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "D",
                          organizationId = acceleratorOrgId1,
                          participantId = participantId,
                      ),
                  ),
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = acceleratorOrgId2,
                  name = "Organization 2",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = unassignedProjectId2,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "C",
                          organizationId = acceleratorOrgId2,
                      ),
                  ),
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = acceleratorOrgIdWithoutProjects,
                  name = "Organization 3",
                  totalUsers = 0,
              ) to emptyList(),
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = projectApplicationOrg1Id,
                  name = "ProjectApplicationOrg1",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = projectApplicationOrg1ProjectId,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "Project 3",
                          organizationId = projectApplicationOrg1Id,
                      ),
                  ),
              OrganizationModel(
                  createdTime = Instant.EPOCH,
                  id = projectApplicationOrg2Id,
                  name = "ProjectApplicationOrg2",
                  totalUsers = 0,
              ) to
                  listOf(
                      ExistingProjectModel(
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = projectApplicationOrg2ProjectId,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "Project 4",
                          organizationId = projectApplicationOrg2Id,
                      ),
                  ),
          ),
          store.findAll(),
      )
    }
  }
}
