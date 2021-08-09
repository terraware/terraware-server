package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.FacilitiesDao
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.daos.ProjectsDao
import com.terraformation.backend.db.tables.daos.SitesDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.FacilitiesRow
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.pojos.ProjectsRow
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.db.tables.pojos.UsersRow
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PermissionStoreTest : DatabaseTest() {
  private lateinit var facilitiesDao: FacilitiesDao
  private lateinit var organizationsDao: OrganizationsDao
  private lateinit var permissionStore: PermissionStore
  private lateinit var projectsDao: ProjectsDao
  private lateinit var sitesDao: SitesDao
  private lateinit var usersDao: UsersDao

  @BeforeEach
  fun setUp() {
    val config = dslContext.configuration()

    permissionStore = PermissionStore(dslContext)

    facilitiesDao = FacilitiesDao(config)
    organizationsDao = OrganizationsDao(config)
    projectsDao = ProjectsDao(config)
    sitesDao = SitesDao(config)
    usersDao = UsersDao(config)
  }

  @Test
  fun `fetchFacilityRoles only includes projects the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(FacilityId(1000) to Role.MANAGER, FacilityId(1001) to Role.MANAGER),
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
  fun `fetchProjectRoles only includes projects the user is in`() {
    insertTestData()
    assertEquals(mapOf(ProjectId(10) to Role.MANAGER), permissionStore.fetchProjectRoles(UserId(5)))
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
  fun `fetchSiteRoles only includes sites from projects the user is in`() {
    insertTestData()
    assertEquals(
        mapOf(SiteId(100) to Role.MANAGER, SiteId(101) to Role.MANAGER),
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

    structure.forEach { (organizationId, projects) ->
      organizationsDao.insert(
          OrganizationsRow(
              id = organizationId,
              name = "Organization $organizationId",
              createdTime = Instant.EPOCH,
              modifiedTime = Instant.EPOCH))

      projects.forEach { (projectId, sites) ->
        projectsDao.insert(
            ProjectsRow(
                id = projectId,
                organizationId = organizationId,
                name = "Project $projectId",
                createdTime = Instant.EPOCH,
                modifiedTime = Instant.EPOCH))

        sites.forEach { (siteId, facilities) ->
          sitesDao.insert(
              SitesRow(
                  id = siteId,
                  projectId = projectId,
                  name = "Site $siteId",
                  enabled = true,
                  latitude = BigDecimal.ONE,
                  longitude = BigDecimal.TEN))

          facilities.forEach { facilityId ->
            facilitiesDao.insert(
                FacilitiesRow(
                    id = facilityId,
                    siteId = siteId,
                    name = "Facility $facilityId",
                    typeId = FacilityType.SeedBank,
                    enabled = true))
          }
        }
      }
    }

    insertUser(5, mapOf(1 to Role.MANAGER), listOf(10))
    insertUser(6, mapOf(2 to Role.OWNER), emptyList())
    insertUser(7, mapOf(1 to Role.CONTRIBUTOR, 2 to Role.MANAGER), listOf(10, 11, 20))
  }

  private fun insertUser(id: Long, roles: Map<Int, Role>, projects: List<Int>) {
    val userId = UserId(id)

    usersDao.insert(
        UsersRow(
            id = userId,
            authId = "auth$id",
            email = "user$id@terraformation.com",
            userTypeId = UserType.Individual,
            createdTime = Instant.EPOCH,
            modifiedTime = Instant.EPOCH))

    roles.forEach { (orgId, role) ->
      with(ORGANIZATION_USERS) {
        dslContext
            .insertInto(ORGANIZATION_USERS)
            .set(USER_ID, userId)
            .set(ORGANIZATION_ID, OrganizationId(orgId.toLong()))
            .set(CREATED_TIME, Instant.EPOCH)
            .set(MODIFIED_TIME, Instant.EPOCH)
            .set(ROLE_ID, role.id)
            .execute()
      }
    }

    projects.forEach { projectId ->
      with(PROJECT_USERS) {
        dslContext
            .insertInto(PROJECT_USERS)
            .set(USER_ID, userId)
            .set(PROJECT_ID, ProjectId(projectId.toLong()))
            .set(CREATED_TIME, Instant.EPOCH)
            .set(MODIFIED_TIME, Instant.EPOCH)
            .execute()
      }
    }
  }
}
