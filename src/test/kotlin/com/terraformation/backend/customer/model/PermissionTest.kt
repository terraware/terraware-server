package com.terraformation.backend.customer.model

import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.UsersRow
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.resource.RealmResource

/**
 * Tests the permission business logic. This includes both the database interactions and the
 * in-memory computations.
 *
 * Most of the tests are done against a canned set of organization data that allows testing various
 * permutations of objects:
 *
 * ```
 * Organization 1 - Two of everything at all levels
 *   Project 10
 *     Site 100
 *       Facility 1000
 *       Facility 1001
 *     Site 101
 *       Facility 1010
 *       Facility 1011
 *   Project 11
 *     Site 110
 *       Facility 1100
 *       Facility 1101
 *     Site 111
 *       Facility 1110
 *       Facility 1101
 *
 * Organization 2 - Incomplete tree structure
 *   Project 20 - No sites
 *   Project 21 - No facilities
 *     Site 210
 *
 * Organization 3 - No projects
 * ```
 *
 * The basic structure of each test is to grant the user a role in an organization and possibly add
 * them to some projects, then assert which specific permissions they should have on each of the
 * above objects.
 *
 * At the end of each test, call [andNothingElse] which will check that the user doesn't have any
 * permissions other than the ones the test specifically said they should.
 */
internal class PermissionTest : DatabaseTest() {
  private lateinit var accessionsDao: AccessionsDao
  private lateinit var permissionStore: PermissionStore
  private lateinit var usersDao: UsersDao
  private lateinit var userStore: UserStore

  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)!!
  private val realmResource = mockk<RealmResource>()

  private val userId = UserId(1234)
  private val user: UserModel by lazy { userStore.fetchById(userId)!! }

  /*
   * Test data set; see class docs for a prettier version. This takes advantage of the default
   * "parent ID is our ID divided by 10" logic of the insert functions in DatabaseTest.
   * Items are removed from these sets by the various expect() methods.
   */
  private val organizationIds = listOf(1, 2, 3).map { OrganizationId(it.toLong()) }.toMutableSet()
  private val projectIds = listOf(10, 11, 20, 21).map { ProjectId(it.toLong()) }.toMutableSet()
  private val siteIds = listOf(100, 101, 110, 111, 210).map { SiteId(it.toLong()) }.toMutableSet()
  private val facilityIds =
      listOf(1000, 1001, 1010, 1011, 1100, 1101, 1110, 1111)
          .map { FacilityId(it.toLong()) }
          .toMutableSet()
  private val accessionIds = facilityIds.map { AccessionId(it.value) }.toMutableSet()

  @BeforeEach
  fun setUp() {
    accessionsDao = AccessionsDao(dslContext.configuration())
    permissionStore = PermissionStore(dslContext)
    usersDao = UsersDao(dslContext.configuration())
    userStore = UserStore(accessionsDao, clock, permissionStore, realmResource, usersDao)

    organizationIds.forEach { insertOrganization(it.value) }
    projectIds.forEach { insertProject(it.value) }
    siteIds.forEach { insertSite(it.value) }
    facilityIds.forEach { insertFacility(it.value) }

    facilityIds.forEach { facilityId ->
      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(facilityId.value),
              facilityId = facilityId,
              stateId = AccessionState.Pending,
              createdTime = Instant.EPOCH))
    }

    usersDao.insert(
        UsersRow(
            id = userId,
            authId = "dummyAuthId",
            email = "test@domain.com",
            userTypeId = UserType.Individual,
            createdTime = Instant.EPOCH,
            modifiedTime = Instant.EPOCH))
  }

  @Test
  fun `owner role grants all permissions in organization projects, sites, facilities and layers`() {
    givenRole(OrganizationId(1), Role.OWNER)

    expect(
        OrganizationId(1),
        addOrganizationUser = true,
        createProject = true,
        deleteOrganization = true,
        removeOrganizationUser = true)

    expect(
        ProjectId(10),
        ProjectId(11),
        addProjectUser = true,
        createSite = true,
        listSites = true,
        removeProjectUser = true)

    expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        createFacility = true,
        listFacilities = true,
        readSite = true)

    expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        FacilityId(1100),
        FacilityId(1101),
        FacilityId(1110),
        FacilityId(1111),
        createAccession = true)

    expect(
        AccessionId(1000),
        AccessionId(1001),
        AccessionId(1010),
        AccessionId(1011),
        AccessionId(1100),
        AccessionId(1101),
        AccessionId(1110),
        AccessionId(1111),
        readAccession = true,
        updateAccession = true)

    expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        createLayer = true,
        readLayer = true,
        updateLayer = true,
        deleteLayer = true)

    andNothingElse()
  }

  @Test
  fun `owner role in empty organization grants organization-level permissions`() {
    givenRole(OrganizationId(3), Role.OWNER)

    expect(
        OrganizationId(3),
        addOrganizationUser = true,
        createProject = true,
        deleteOrganization = true,
        removeOrganizationUser = true)

    andNothingElse()
  }

  @Test
  fun `admin role grants all permissions except deleting organization`() {
    givenRole(OrganizationId(1), Role.ADMIN)

    expect(
        OrganizationId(1),
        addOrganizationUser = true,
        createProject = true,
        removeOrganizationUser = true)

    expect(
        ProjectId(10),
        ProjectId(11),
        addProjectUser = true,
        createSite = true,
        listSites = true,
        removeProjectUser = true)

    expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        createFacility = true,
        listFacilities = true,
        readSite = true)

    expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        FacilityId(1100),
        FacilityId(1101),
        FacilityId(1110),
        FacilityId(1111),
        createAccession = true)

    expect(
        AccessionId(1000),
        AccessionId(1001),
        AccessionId(1010),
        AccessionId(1011),
        AccessionId(1100),
        AccessionId(1101),
        AccessionId(1110),
        AccessionId(1111),
        readAccession = true,
        updateAccession = true)

    expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        createLayer = true,
        readLayer = true,
        updateLayer = true,
        deleteLayer = true)

    andNothingElse()
  }

  @Test
  fun `managers have access to projects they are in`() {
    givenRole(OrganizationId(1), Role.MANAGER, ProjectId(10))

    expect(ProjectId(10), addProjectUser = true, listSites = true, removeProjectUser = true)

    expect(SiteId(100), SiteId(101), listFacilities = true, readSite = true)

    expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        createAccession = true)

    expect(
        AccessionId(1000),
        AccessionId(1001),
        AccessionId(1010),
        AccessionId(1011),
        readAccession = true,
        updateAccession = true)

    expect(
        SiteId(100),
        SiteId(101),
        createLayer = true,
        readLayer = true,
        updateLayer = true,
        deleteLayer = true)

    andNothingElse()
  }

  @Test
  fun `contributors have read access to public data and can do data entry`() {
    givenRole(OrganizationId(1), Role.CONTRIBUTOR, ProjectId(10))

    expect(ProjectId(10), listSites = true)

    expect(SiteId(100), SiteId(101), listFacilities = true, readSite = true)

    expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        createAccession = true)

    expect(
        AccessionId(1000),
        AccessionId(1001),
        AccessionId(1010),
        AccessionId(1011),
        readAccession = true,
        updateAccession = true)

    expect(
        SiteId(100),
        SiteId(101),
        createLayer = true,
        readLayer = true,
        updateLayer = true,
        deleteLayer = true)

    andNothingElse()
  }

  @Test
  fun `user with no organization memberships has no organization-level permissions`() {
    // No givenRole() here; user has no roles anywhere.
    andNothingElse()
  }

  private fun givenRole(organizationId: OrganizationId, role: Role, vararg projects: ProjectId) {
    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(USER_ID, userId)
          .set(ORGANIZATION_ID, organizationId)
          .set(ROLE_ID, role.id)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .execute()
    }

    projects.forEach { projectId ->
      with(PROJECT_USERS) {
        dslContext
            .insertInto(PROJECT_USERS)
            .set(USER_ID, userId)
            .set(PROJECT_ID, projectId)
            .set(CREATED_TIME, Instant.EPOCH)
            .set(MODIFIED_TIME, Instant.EPOCH)
            .execute()
      }
    }
  }

  private fun expect(
      vararg organizations: OrganizationId,
      addOrganizationUser: Boolean = false,
      createProject: Boolean = false,
      deleteOrganization: Boolean = false,
      removeOrganizationUser: Boolean = false,
  ) {
    organizations.forEach { organizationId ->
      assertEquals(
          addOrganizationUser,
          user.canAddOrganizationUser(organizationId),
          "Can add organization $organizationId user")
      assertEquals(
          createProject,
          user.canCreateProject(organizationId),
          "Can create project in organization $organizationId")
      assertEquals(
          deleteOrganization,
          user.canDeleteOrganization(organizationId),
          "Can delete organization $organizationId")
      assertEquals(
          removeOrganizationUser,
          user.canRemoveOrganizationUser(organizationId),
          "Can remove user from organization $organizationId")

      organizationIds.remove(organizationId)
    }
  }

  private fun expect(
      vararg projects: ProjectId,
      addProjectUser: Boolean = false,
      createSite: Boolean = false,
      listSites: Boolean = false,
      removeProjectUser: Boolean = false,
  ) {
    projects.forEach { projectId ->
      assertEquals(
          addProjectUser, user.canAddProjectUser(projectId), "Can add project $projectId user")
      assertEquals(
          createSite, user.canCreateSite(projectId), "Can create site in project $projectId")
      assertEquals(listSites, user.canListSites(projectId), "Can list sites in project $projectId")
      assertEquals(
          removeProjectUser,
          user.canRemoveProjectUser(projectId),
          "Can remove project $projectId user")

      projectIds.remove(projectId)
    }
  }

  private fun expect(
      vararg sites: SiteId,
      createFacility: Boolean = false,
      listFacilities: Boolean = false,
      readSite: Boolean = false,
  ) {
    sites.forEach { siteId ->
      assertEquals(
          createFacility, user.canCreateFacility(siteId), "Can create site $siteId facility")
      assertEquals(
          listFacilities, user.canListFacilities(siteId), "Can list site $siteId facilities")
      assertEquals(readSite, user.canReadSite(siteId), "Can read site $siteId")

      siteIds.remove(siteId)
    }
  }

  private fun expect(
      vararg facilities: FacilityId,
      createAccession: Boolean = false,
  ) {
    facilities.forEach { facilityId ->
      assertEquals(
          createAccession,
          user.canCreateAccession(facilityId),
          "Can create accession at facility $facilityId")

      facilityIds.remove(facilityId)
    }
  }

  private fun expect(
      vararg accessions: AccessionId,
      readAccession: Boolean = false,
      updateAccession: Boolean = false,
  ) {
    accessions.forEach { accessionId ->
      assertEquals(
          readAccession, user.canReadAccession(accessionId), "Can read accession $accessionId")
      assertEquals(
          updateAccession,
          user.canUpdateAccession(accessionId),
          "Can update accession $accessionId")

      accessionIds.remove(accessionId)
    }
  }

  private fun expect(
      vararg sites: SiteId,
      createLayer: Boolean = false,
      readLayer: Boolean = false,
      updateLayer: Boolean = false,
      deleteLayer: Boolean = false
  ) {
    sites.forEach { siteId ->
      assertEquals(createLayer, user.canCreateLayer(siteId), "Can create layer at site $siteId")
      assertEquals(readLayer, user.canReadLayer(siteId), "Can read layer at site $siteId")
      assertEquals(updateLayer, user.canUpdateLayer(siteId), "Can update layer at site $siteId")
      assertEquals(deleteLayer, user.canDeleteLayer(siteId), "Can delete layer at site $siteId")
    }
  }

  private fun andNothingElse() {
    expect(*organizationIds.toTypedArray())
    expect(*projectIds.toTypedArray())
    expect(*siteIds.toTypedArray())
    expect(*facilityIds.toTypedArray())
    expect(*accessionIds.toTypedArray())
  }
}
