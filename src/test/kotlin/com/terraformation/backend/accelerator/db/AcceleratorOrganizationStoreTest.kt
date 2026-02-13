package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.default_schema.GlobalRole
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
  inner class FindAll {
    @Test
    fun `finds projects with applications, plus assigned and unassigned projects under orgs with projects in phases`() {
      val acceleratorOrgId1 = insertOrganization()
      val nonAcceleratorOrgId = insertOrganization()
      val untaggedOrgId = insertOrganization()
      val cohortId = insertCohort()
      val currentUserId = user.userId

      insertOrganizationInternalTag(nonAcceleratorOrgId, InternalTagIds.Internal)

      insertProject(organizationId = nonAcceleratorOrgId)
      insertProject(organizationId = untaggedOrgId)
      val assignedProjectId =
          insertProject(
              organizationId = acceleratorOrgId1,
              name = "D",
              cohortId = cohortId,
              phase = CohortPhase.Phase0DueDiligence,
          )
      val unassignedProjectId1 = insertProject(organizationId = acceleratorOrgId1, name = "A")

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
                          cohortId = cohortId,
                          createdBy = currentUserId,
                          createdTime = Instant.EPOCH,
                          id = assignedProjectId,
                          modifiedBy = currentUserId,
                          modifiedTime = Instant.EPOCH,
                          name = "D",
                          organizationId = acceleratorOrgId1,
                          phase = CohortPhase.Phase0DueDiligence,
                      ),
                  ),
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
