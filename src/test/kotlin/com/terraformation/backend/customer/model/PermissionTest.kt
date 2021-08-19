package com.terraformation.backend.customer.model

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.LayersDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.UsersRow
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.beans.factory.annotation.Autowired

/**
 * Tests the permission business logic. This includes both the database interactions and the
 * in-memory computations.
 *
 * Most of the tests are done against a canned set of organization data that allows testing various
 * permutations of objects:
 *
 * ```
 * Organization 1 - Two of everything at all levels, except features and layers
 *   Project 10
 *     Site 100
 *       Facility 1000
 *       Facility 1001
 *       Layer 1000
 *       Layer 1001
 *        Feature 10010
 *     Site 101
 *       Facility 1010
 *       Facility 1011
 *   Project 11
 *     Site 110
 *       Facility 1100
 *       Facility 1101
 *       Layer 1100
 *        Feature 11000
 *        Feature 11001
 *     Site 111
 *       Facility 1110
 *       Facility 1111
 *
 * Organization 2 - Incomplete tree structure
 *   Project 20 - No sites
 *   Project 21 - No facilities
 *     Site 210
 *     Layer 2100
 *        Feature 21000
 *
 * Organization 3 - No projects
 * ```
 *
 * The basic structure of each test is to:
 * ```
 *    grant the user a role in an organization and possibly add them to some projects
 *    create a PermissionsTracker() instance which should be used throughout the whole test
 *    use the permissions tracker to assert which specific permissions they should have on
 *        each of the above objects.
 *    call [andNothingElse] which will check that the user doesn't have any permissions other
 *      than the ones the test specifically said they should.
 * ```
 */
internal class PermissionTest : DatabaseTest() {
  private lateinit var accessionsDao: AccessionsDao
  private lateinit var featuresDao: FeaturesDao
  private lateinit var layersDao: LayersDao
  private lateinit var permissionStore: PermissionStore
  private lateinit var usersDao: UsersDao
  private lateinit var userStore: UserStore

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)!!
  private val realmResource: RealmResource = mockk()

  private val userId = UserId(1234)
  private val user: UserModel by lazy { userStore.fetchById(userId)!! }

  /*
   * Test data set; see class docs for a prettier version. This takes advantage of the default
   * "parent ID is our ID divided by 10" logic of the insert functions in DatabaseTest.
   */
  private val organizationIds = listOf(1, 2, 3).map { OrganizationId(it.toLong()) }.toMutableSet()
  private val projectIds = listOf(10, 11, 20, 21).map { ProjectId(it.toLong()) }.toMutableSet()
  private val siteIds = listOf(100, 101, 110, 111, 210).map { SiteId(it.toLong()) }.toMutableSet()
  private val facilityIds =
      listOf(1000, 1001, 1010, 1011, 1100, 1101, 1110, 1111)
          .map { FacilityId(it.toLong()) }
          .toMutableSet()
  private val accessionIds = facilityIds.map { AccessionId(it.value) }.toMutableSet()
  private val layerIds = listOf(1000, 1001, 1100, 2100).map { LayerId(it.toLong()) }.toMutableSet()
  private val featureIds =
      listOf(10010, 11000, 11001, 21000).map { FeatureId(it.toLong()) }.toMutableSet()

  @BeforeEach
  fun setUp() {
    every { realmResource.users() } returns mockk()

    val jooqConfig = dslContext.configuration()
    accessionsDao = AccessionsDao(jooqConfig)
    featuresDao = FeaturesDao(jooqConfig)
    layersDao = LayersDao(jooqConfig)
    permissionStore = PermissionStore(dslContext)
    usersDao = UsersDao(jooqConfig)
    userStore =
        UserStore(
            accessionsDao,
            clock,
            config,
            featuresDao,
            mockk(),
            layersDao,
            mockk(),
            mockk(),
            permissionStore,
            realmResource,
            usersDao)

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

    layerIds.forEach { insertLayer(it.value) }
    featureIds.forEach { insertFeature(it.value) }

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
  fun `owner role grants all permissions in organization projects, sites, facilities, and layers`() {
    givenRole(OrganizationId(1), Role.OWNER)
    val permissions = PermissionsTracker()

    permissions.expect(
        OrganizationId(1),
        addOrganizationUser = true,
        createProject = true,
        deleteOrganization = true,
        removeOrganizationUser = true)

    permissions.expect(
        ProjectId(10),
        ProjectId(11),
        addProjectUser = true,
        createSite = true,
        listSites = true,
        removeProjectUser = true)

    permissions.expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        createFacility = true,
        listFacilities = true,
        readSite = true,
        createLayer = true,
        readLayer = true,
        updateLayer = true,
        deleteLayer = true,
    )

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        LayerId(1100),
        createLayerData = true,
        updateLayerData = true,
    )

    permissions.expect(
        FeatureId(10010),
        FeatureId(11000),
        FeatureId(11001),
        readLayerData = true,
        deleteLayerData = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        FacilityId(1100),
        FacilityId(1101),
        FacilityId(1110),
        FacilityId(1111),
        createAccession = true)

    permissions.expect(
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

    permissions.andNothingElse()
  }

  @Test
  fun `owner role in empty organization grants organization-level permissions`() {
    givenRole(OrganizationId(3), Role.OWNER)

    val permissions = PermissionsTracker()

    permissions.expect(
        OrganizationId(3),
        addOrganizationUser = true,
        createProject = true,
        deleteOrganization = true,
        removeOrganizationUser = true)

    permissions.andNothingElse()
  }

  @Test
  fun `admin role grants all permissions except deleting organization`() {
    givenRole(OrganizationId(1), Role.ADMIN)

    val permissions = PermissionsTracker()

    permissions.expect(
        OrganizationId(1),
        addOrganizationUser = true,
        createProject = true,
        removeOrganizationUser = true)

    permissions.expect(
        ProjectId(10),
        ProjectId(11),
        addProjectUser = true,
        createSite = true,
        listSites = true,
        removeProjectUser = true)

    permissions.expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        createFacility = true,
        listFacilities = true,
        readSite = true,
        createLayer = true,
        readLayer = true,
        updateLayer = true,
        deleteLayer = true)

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        LayerId(1100),
        createLayerData = true,
        updateLayerData = true,
    )

    permissions.expect(
        FeatureId(10010),
        FeatureId(11000),
        FeatureId(11001),
        readLayerData = true,
        deleteLayerData = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        FacilityId(1100),
        FacilityId(1101),
        FacilityId(1110),
        FacilityId(1111),
        createAccession = true)

    permissions.expect(
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

    permissions.andNothingElse()
  }

  @Test
  fun `managers can add users to their project, access all data associated with their project`() {
    givenRole(OrganizationId(1), Role.MANAGER, ProjectId(10))

    val permissions = PermissionsTracker()

    permissions.expect(
        ProjectId(10), addProjectUser = true, listSites = true, removeProjectUser = true)

    permissions.expect(
        SiteId(100),
        SiteId(101),
        listFacilities = true,
        readSite = true,
        createLayer = true,
        readLayer = true,
        updateLayer = true,
        deleteLayer = true)

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        createLayerData = true,
        updateLayerData = true,
    )

    permissions.expect(FeatureId(10010), readLayerData = true, deleteLayerData = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        createAccession = true)

    permissions.expect(
        AccessionId(1000),
        AccessionId(1001),
        AccessionId(1010),
        AccessionId(1011),
        readAccession = true,
        updateAccession = true)

    permissions.andNothingElse()
  }

  @Test
  fun `contributors have access to data associated with their project(s)`() {
    givenRole(OrganizationId(1), Role.CONTRIBUTOR, ProjectId(10))

    val permissions = PermissionsTracker()

    permissions.expect(ProjectId(10), listSites = true)

    permissions.expect(
        SiteId(100),
        SiteId(101),
        listFacilities = true,
        readSite = true,
        createLayer = true,
        readLayer = true,
        updateLayer = true,
        deleteLayer = true)

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        createLayerData = true,
        updateLayerData = true,
    )

    permissions.expect(FeatureId(10010), readLayerData = true, deleteLayerData = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        createAccession = true)

    permissions.expect(
        AccessionId(1000),
        AccessionId(1001),
        AccessionId(1010),
        AccessionId(1011),
        readAccession = true,
        updateAccession = true)

    permissions.andNothingElse()
  }

  @Test
  fun `user with no organization memberships has no organization-level permissions`() {
    // No givenRole() here; user has no roles anywhere.
    PermissionsTracker().andNothingElse()
  }

  @Test
  fun `user with no organization memberships has no default organization ID`() {
    assertNull(userStore.fetchById(userId)!!.defaultOrganizationId())
  }

  @Test
  fun `user with multiple organization memberships has no default organization ID`() {
    givenRole(OrganizationId(1), Role.CONTRIBUTOR)
    givenRole(OrganizationId(2), Role.CONTRIBUTOR)

    assertNull(userStore.fetchById(userId)!!.defaultOrganizationId())
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

  inner class PermissionsTracker() {
    private val uncheckedOrgs = organizationIds
    private val uncheckedProjects = projectIds
    private val uncheckedSites = siteIds
    private val uncheckedFacilities = facilityIds
    private val uncheckedAccessions = accessionIds

    // All checks keyed on organization IDs go here
    fun expect(
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

        uncheckedOrgs.remove(organizationId)
      }
    }

    // All checks keyed on project IDs go here
    fun expect(
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
        assertEquals(
            listSites, user.canListSites(projectId), "Can list sites in project $projectId")
        assertEquals(
            removeProjectUser,
            user.canRemoveProjectUser(projectId),
            "Can remove project $projectId user")

        uncheckedProjects.remove(projectId)
      }
    }

    // All checks keyed on site IDs go here
    fun expect(
        vararg sites: SiteId,
        createFacility: Boolean = false,
        listFacilities: Boolean = false,
        readSite: Boolean = false,
        createLayer: Boolean = false,
        readLayer: Boolean = false,
        updateLayer: Boolean = false,
        deleteLayer: Boolean = false,
    ) {
      sites.forEach { siteId ->
        assertEquals(
            createFacility, user.canCreateFacility(siteId), "Can create site $siteId facility")
        assertEquals(
            listFacilities, user.canListFacilities(siteId), "Can list site $siteId facilities")
        assertEquals(readSite, user.canReadSite(siteId), "Can read site $siteId")
        assertEquals(createLayer, user.canCreateLayer(siteId), "Can create layer at site $siteId")
        assertEquals(readLayer, user.canReadLayer(siteId), "Can read layer at site $siteId")
        assertEquals(updateLayer, user.canUpdateLayer(siteId), "Can update layer at site $siteId")
        assertEquals(deleteLayer, user.canDeleteLayer(siteId), "Can delete layer at site $siteId")

        uncheckedSites.remove(siteId)
      }
    }

    // All checks keyed on facility IDs go here
    fun expect(
        vararg facilities: FacilityId,
        createAccession: Boolean = false,
    ) {
      facilities.forEach { facilityId ->
        assertEquals(
            createAccession,
            user.canCreateAccession(facilityId),
            "Can create accession at facility $facilityId")

        uncheckedFacilities.remove(facilityId)
      }
    }

    // All checks keyed on accession IDs go here
    fun expect(
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

        uncheckedAccessions.remove(accessionId)
      }
    }

    // All checks keyed on layer IDs go here
    fun expect(
        vararg layers: LayerId,
        createLayerData: Boolean = false,
        updateLayerData: Boolean = false,
    ) {
      layers.forEach { layerId ->
        assertEquals(
            createLayerData,
            user.canCreateLayerData(layerId),
            "Can create layer data associated with layer $layerId")
        assertEquals(
            updateLayerData,
            user.canUpdateLayerData(layerId),
            "Can update layer data associated with layer $layerId")
      }
    }

    // All checks keyed on feature IDs go here
    fun expect(
        vararg features: FeatureId,
        readLayerData: Boolean = false,
        deleteLayerData: Boolean = false,
    ) {
      features.forEach { featureId ->
        assertEquals(
            readLayerData,
            user.canReadLayerData(featureId),
            "Can read layer data associated with feature $featureId")
        assertEquals(
            deleteLayerData,
            user.canDeleteLayerData(featureId),
            "Can delete layer data associated with feature $featureId")
      }
    }
    fun andNothingElse() {
      expect(*uncheckedOrgs.toTypedArray())
      expect(*uncheckedProjects.toTypedArray())
      expect(*uncheckedSites.toTypedArray())
      expect(*uncheckedFacilities.toTypedArray())
      expect(*uncheckedAccessions.toTypedArray())
    }
  }
}
