package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class AcceleratorOrganizationStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val store: AcceleratorOrganizationStore by lazy {
    AcceleratorOrganizationStore(dslContext)
  }

  @BeforeEach
  fun setUp() {
    every { user.canReadInternalTags() } returns true
  }

  @Nested
  inner class FetchWithUnassignedProjects {
    @Test
    fun `only returns unassigned projects in Accelerator tagged organizations`() {
      val acceleratorOrgId1 = insertOrganization(1)
      val acceleratorOrgId2 = insertOrganization(2)
      val nonAcceleratorOrgId = insertOrganization(3)
      val untaggedOrgId = insertOrganization(4)
      val participantId = insertParticipant()
      val currentUserId = user.userId

      insertOrganizationInternalTag(acceleratorOrgId1, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(acceleratorOrgId2, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(nonAcceleratorOrgId, InternalTagIds.Internal)

      insertProject(organizationId = nonAcceleratorOrgId)
      insertProject(organizationId = untaggedOrgId)
      insertProject(organizationId = 1, participantId = participantId)
      val unassignedProjectId1 = insertProject(organizationId = 1, name = "A")
      val unassignedProjectId2 = insertProject(organizationId = 2, name = "C")
      val unassignedProjectId3 = insertProject(organizationId = 2, name = "B")

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
          store.fetchWithUnassignedProjects())
    }

    @Test
    fun `throws exception if no permission to read internal tags`() {
      every { user.canReadInternalTags() } returns false

      assertThrows<AccessDeniedException> { store.fetchWithUnassignedProjects() }
    }
  }

  @Nested
  inner class FindAll {
    @Test
    fun `returns both assigned and unassigned projects in Accelerator tagged organizations`() {
      val acceleratorOrgId1 = insertOrganization(1)
      val acceleratorOrgId2 = insertOrganization(2)
      val acceleratorOrgIdWithoutProjects = insertOrganization(3)
      val nonAcceleratorOrgId = insertOrganization(4)
      val untaggedOrgId = insertOrganization(5)
      val participantId = insertParticipant()
      val currentUserId = user.userId

      insertOrganizationInternalTag(acceleratorOrgId1, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(acceleratorOrgId2, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(acceleratorOrgIdWithoutProjects, InternalTagIds.Accelerator)
      insertOrganizationInternalTag(nonAcceleratorOrgId, InternalTagIds.Internal)

      insertProject(organizationId = nonAcceleratorOrgId)
      insertProject(organizationId = untaggedOrgId)
      val assignedProjectId =
          insertProject(organizationId = 1, name = "D", participantId = participantId)
      val unassignedProjectId1 = insertProject(organizationId = 1, name = "A")
      val unassignedProjectId2 = insertProject(organizationId = 2, name = "C")

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
              ) to emptyList()),
          store.findAll())
    }

    @Test
    fun `throws exception if no permission to read internal tags`() {
      every { user.canReadInternalTags() } returns false

      assertThrows<AccessDeniedException> { store.fetchWithUnassignedProjects() }
    }
  }
}
