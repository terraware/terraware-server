package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.mockUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PermissionStoreTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private lateinit var permissionStore: PermissionStore

  @BeforeEach
  fun setUp() {
    permissionStore = PermissionStore(dslContext)
  }

  @Test
  fun `fetchFacilityRoles includes facilities in all projects in organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(
            FacilityId(1000) to Role.MANAGER,
            FacilityId(1001) to Role.MANAGER,
            FacilityId(1100) to Role.MANAGER),
        permissionStore.fetchFacilityRoles(UserId(5)))
  }

  @Test
  fun `fetchFacilityRoles includes organization-wide projects`() {
    insertTestData()
    projectsDao.update(projectsDao.fetchOneById(ProjectId(11))!!.copy(organizationWide = true))

    assertEquals(
        mapOf(
            FacilityId(1000) to Role.MANAGER,
            FacilityId(1001) to Role.MANAGER,
            FacilityId(1100) to Role.MANAGER),
        permissionStore.fetchFacilityRoles(UserId(5)))
  }

  @Test
  fun `fetchFacilityRoles includes facilities from multiple organizations`() {
    insertTestData()
    assertEquals(
        mapOf(
            FacilityId(1000) to Role.CONTRIBUTOR,
            FacilityId(1001) to Role.CONTRIBUTOR,
            FacilityId(1100) to Role.CONTRIBUTOR,
            FacilityId(2000) to Role.MANAGER),
        permissionStore.fetchFacilityRoles(UserId(7)))
  }

  @Test
  fun `fetchOrganizationRoles only includes organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(OrganizationId(2) to Role.OWNER), permissionStore.fetchOrganizationRoles(UserId(6)))
  }

  @Test
  fun `fetchOrganizationRoles includes all organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(OrganizationId(1) to Role.CONTRIBUTOR, OrganizationId(2) to Role.MANAGER),
        permissionStore.fetchOrganizationRoles(UserId(7)))
  }

  @Test
  fun `fetchProjectRoles includes all projects in the organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(ProjectId(10) to Role.MANAGER, ProjectId(11) to Role.MANAGER),
        permissionStore.fetchProjectRoles(UserId(5)))
  }

  @Test
  fun `fetchProjectRoles includes organization-wide projects`() {
    val organizationWideProjectId = ProjectId(11)

    insertTestData()
    projectsDao.update(
        projectsDao.fetchOneById(organizationWideProjectId)!!.copy(organizationWide = true))

    assertEquals(
        mapOf(ProjectId(10) to Role.MANAGER, organizationWideProjectId to Role.MANAGER),
        permissionStore.fetchProjectRoles(UserId(5)))
  }

  @Test
  fun `fetchProjectRoles includes all projects the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(
            ProjectId(10) to Role.CONTRIBUTOR,
            ProjectId(11) to Role.CONTRIBUTOR,
            ProjectId(20) to Role.MANAGER),
        permissionStore.fetchProjectRoles(UserId(7)))
  }

  @Test
  fun `fetchSiteRoles includes all sites from organizations the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(
            SiteId(100) to Role.MANAGER, SiteId(101) to Role.MANAGER, SiteId(110) to Role.MANAGER),
        permissionStore.fetchSiteRoles(UserId(5)))
  }

  @Test
  fun `fetchSiteRoles includes sites from organization-wide projects`() {
    insertTestData()
    projectsDao.update(projectsDao.fetchOneById(ProjectId(11))!!.copy(organizationWide = true))

    assertEquals(
        mapOf(
            SiteId(100) to Role.MANAGER, SiteId(101) to Role.MANAGER, SiteId(110) to Role.MANAGER),
        permissionStore.fetchSiteRoles(UserId(5)))
  }

  @Test
  fun `fetchSiteRoles includes all sites from all projects the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(
            SiteId(100) to Role.CONTRIBUTOR,
            SiteId(101) to Role.CONTRIBUTOR,
            SiteId(110) to Role.CONTRIBUTOR,
            SiteId(200) to Role.MANAGER),
        permissionStore.fetchSiteRoles(UserId(7)))
  }

  /**
   * Inserts some test data to exercise the fetch methods. The data set:
   *
   * ```
   * - Organization 1
   *   - Project 10
   *     - Site 100
   *       - Facility 1000
   *       - Facility 1001
   *     - Site 101
   *   - Project 11
   *     - Site 110
   *       - Facility 1100
   * - Organization 2
   *   - Project 20
   *     - Site 200
   *       - Facility 2000
   *
   * - User 5
   *   - Org 1 role: manager
   *     - Member of project 10
   * - User 6
   *   - Org 2 role: owner
   * - User 7
   *   - Org 1 role: contributor
   *     - Member of project 10
   *     - Member of project 11
   *   - Org 2 role: manager
   *     - Member of project 20
   * ```
   */
  private fun insertTestData() {
    val structure =
        mapOf(
            OrganizationId(1) to
                mapOf(
                    ProjectId(10) to
                        mapOf(
                            SiteId(100) to
                                listOf(
                                    FacilityId(1000),
                                    FacilityId(1001),
                                ),
                            SiteId(101) to emptyList(),
                        ),
                    ProjectId(11) to
                        mapOf(
                            SiteId(110) to listOf(FacilityId(1100)),
                        ),
                ),
            OrganizationId(2) to
                mapOf(
                    ProjectId(20) to
                        mapOf(
                            SiteId(200) to
                                listOf(
                                    FacilityId(2000),
                                ))))

    insertUser()

    structure.forEach { (organizationId, projects) ->
      insertOrganization(organizationId)

      projects.forEach { (projectId, sites) ->
        insertProject(projectId, organizationId)

        sites.forEach { (siteId, facilities) ->
          insertSite(siteId, projectId)

          facilities.forEach { facilityId -> insertFacility(facilityId, siteId, organizationId) }
        }
      }
    }

    configureUser(5, mapOf(1 to Role.MANAGER), listOf(10))
    configureUser(6, mapOf(2 to Role.OWNER), emptyList())
    configureUser(7, mapOf(1 to Role.CONTRIBUTOR, 2 to Role.MANAGER), listOf(10, 11, 20))
  }

  private fun configureUser(userId: Long, roles: Map<Int, Role>, projects: List<Int>) {
    insertUser(userId)
    roles.forEach { (orgId, role) -> insertOrganizationUser(userId, orgId, role) }
    projects.forEach { projectId -> insertProjectUser(userId, projectId) }
  }
}
