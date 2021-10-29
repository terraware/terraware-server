package com.terraformation.backend.customer.model

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.AutomationsDao
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.AutomationsRow
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.db.tables.pojos.UsersRow
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.AUTOMATIONS
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.FEATURES
import com.terraformation.backend.db.tables.references.FEATURE_PHOTOS
import com.terraformation.backend.db.tables.references.LAYERS
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PHOTOS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.TIMESERIES
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
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
  private lateinit var automationsDao: AutomationsDao
  private lateinit var devicesDao: DevicesDao
  private lateinit var parentStore: ParentStore
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
  private val organizationIds = listOf(1, 2, 3).map { OrganizationId(it.toLong()) }
  private val projectIds = listOf(10, 11, 20, 21).map { ProjectId(it.toLong()) }
  private val siteIds = listOf(100, 101, 110, 111, 210).map { SiteId(it.toLong()) }
  private val facilityIds =
      listOf(1000, 1001, 1010, 1011, 1100, 1101, 1110, 1111).map { FacilityId(it.toLong()) }
  private val automationIds = facilityIds.map { AutomationId(it.value) }
  private val deviceIds = facilityIds.map { DeviceId(it.value) }
  private val accessionIds = facilityIds.map { AccessionId(it.value) }
  private val layerIds = listOf(1000, 1001, 1100, 2100).map { LayerId(it.toLong()) }
  private val featureIds = listOf(10010, 11000, 11001, 21000).map { FeatureId(it.toLong()) }
  private val photoIds = featureIds.map { PhotoId(it.value) }

  @BeforeEach
  fun setUp() {
    every { realmResource.users() } returns mockk()

    val jooqConfig = dslContext.configuration()
    accessionsDao = AccessionsDao(jooqConfig)
    automationsDao = AutomationsDao(jooqConfig)
    devicesDao = DevicesDao(jooqConfig)
    parentStore = ParentStore(dslContext)
    permissionStore = PermissionStore(dslContext)
    usersDao = UsersDao(jooqConfig)
    userStore =
        UserStore(
            clock,
            config,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            parentStore,
            permissionStore,
            realmResource,
            usersDao)

    organizationIds.forEach { insertOrganization(it.value) }
    projectIds.forEach { insertProject(it.value) }
    siteIds.forEach { insertSite(it.value) }

    facilityIds.forEach { facilityId ->
      insertFacility(facilityId.value)
      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(facilityId.value),
              facilityId = facilityId,
              stateId = AccessionState.Pending,
              createdTime = Instant.EPOCH))
      automationsDao.insert(
          AutomationsRow(
              id = AutomationId(facilityId.value),
              facilityId = facilityId,
              name = "Automation $facilityId",
              createdTime = Instant.EPOCH,
              modifiedTime = Instant.EPOCH))
      devicesDao.insert(
          DevicesRow(
              id = DeviceId(facilityId.value),
              facilityId = facilityId,
              name = "Device $facilityId",
              deviceType = "type",
              make = "make",
              model = "model"))
    }

    layerIds.forEach { insertLayer(it.value) }
    featureIds.forEach { insertFeature(it.value) }
    photoIds.forEach {
      insertPhoto(it.value)
      insertFeaturePhoto(it.value)
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
  fun `owner role grants all permissions in organization projects, sites, facilities, and layers`() {
    givenRole(OrganizationId(1), Role.OWNER)
    val permissions = PermissionsTracker()

    permissions.expect(
        OrganizationId(1),
        addOrganizationUser = true,
        createProject = true,
        listProjects = true,
        readOrganization = true,
        deleteOrganization = true,
        removeOrganizationUser = true,
    )

    permissions.expect(
        ProjectId(10),
        ProjectId(11),
        readProject = true,
        updateProject = true,
        addProjectUser = true,
        createSite = true,
        listSites = true,
        removeProjectUser = true,
    )

    permissions.expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        createFacility = true,
        listFacilities = true,
        readSite = true,
        createLayer = true,
    )

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        LayerId(1100),
        readLayer = true,
        updateLayer = true,
        deleteLayer = true,
        createFeature = true,
    )

    permissions.expect(
        FeatureId(10010),
        FeatureId(11000),
        FeatureId(11001),
        readFeature = true,
        updateFeature = true,
        deleteFeature = true,
    )

    permissions.expect(
        PhotoId(10010),
        PhotoId(11000),
        PhotoId(11001),
        readFeaturePhoto = true,
        deleteFeaturePhoto = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        FacilityId(1100),
        FacilityId(1101),
        FacilityId(1110),
        FacilityId(1111),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        updateFacility = true,
        listAutomations = true,
        sendAlert = true,
    )

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
        updateAccession = true,
    )

    permissions.expect(
        AutomationId(1000),
        AutomationId(1001),
        AutomationId(1010),
        AutomationId(1011),
        AutomationId(1100),
        AutomationId(1101),
        AutomationId(1110),
        AutomationId(1111),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
    )

    permissions.expect(
        DeviceId(1000),
        DeviceId(1001),
        DeviceId(1010),
        DeviceId(1011),
        DeviceId(1100),
        DeviceId(1101),
        DeviceId(1110),
        DeviceId(1111),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

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
        listProjects = true,
        readOrganization = true,
        deleteOrganization = true,
        removeOrganizationUser = true,
    )

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
        listProjects = true,
        readOrganization = true,
        removeOrganizationUser = true,
    )

    permissions.expect(
        ProjectId(10),
        ProjectId(11),
        readProject = true,
        updateProject = true,
        addProjectUser = true,
        createSite = true,
        listSites = true,
        removeProjectUser = true,
    )

    permissions.expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        createFacility = true,
        listFacilities = true,
        readSite = true,
        createLayer = true,
    )

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        LayerId(1100),
        readLayer = true,
        updateLayer = true,
        deleteLayer = true,
        createFeature = true,
    )

    permissions.expect(
        FeatureId(10010),
        FeatureId(11000),
        FeatureId(11001),
        readFeature = true,
        updateFeature = true,
        deleteFeature = true,
    )

    permissions.expect(
        PhotoId(10010),
        PhotoId(11000),
        PhotoId(11001),
        readFeaturePhoto = true,
        deleteFeaturePhoto = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        FacilityId(1100),
        FacilityId(1101),
        FacilityId(1110),
        FacilityId(1111),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        updateFacility = true,
        listAutomations = true,
        sendAlert = true,
    )

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
        updateAccession = true,
    )

    permissions.expect(
        AutomationId(1000),
        AutomationId(1001),
        AutomationId(1010),
        AutomationId(1011),
        AutomationId(1100),
        AutomationId(1101),
        AutomationId(1110),
        AutomationId(1111),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
    )

    permissions.expect(
        DeviceId(1000),
        DeviceId(1001),
        DeviceId(1010),
        DeviceId(1011),
        DeviceId(1100),
        DeviceId(1101),
        DeviceId(1110),
        DeviceId(1111),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `managers can add users to their project, access all data associated with their project`() {
    givenRole(OrganizationId(1), Role.MANAGER, ProjectId(10))

    val permissions = PermissionsTracker()

    permissions.expect(OrganizationId(1), listProjects = true, readOrganization = true)

    permissions.expect(
        ProjectId(10),
        readProject = true,
        addProjectUser = true,
        listSites = true,
        removeProjectUser = true,
    )

    permissions.expect(
        SiteId(100),
        SiteId(101),
        listFacilities = true,
        readSite = true,
        createLayer = true,
    )

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        readLayer = true,
        updateLayer = true,
        deleteLayer = true,
        createFeature = true,
    )

    permissions.expect(
        FeatureId(10010),
        readFeature = true,
        updateFeature = true,
        deleteFeature = true,
    )

    permissions.expect(PhotoId(10010), readFeaturePhoto = true, deleteFeaturePhoto = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        updateFacility = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        AccessionId(1000),
        AccessionId(1001),
        AccessionId(1010),
        AccessionId(1011),
        readAccession = true,
        updateAccession = true,
    )

    permissions.expect(
        AutomationId(1000),
        AutomationId(1001),
        AutomationId(1010),
        AutomationId(1011),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
    )

    permissions.expect(
        DeviceId(1000),
        DeviceId(1001),
        DeviceId(1010),
        DeviceId(1011),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `contributors have access to data associated with their project(s)`() {
    givenRole(OrganizationId(1), Role.CONTRIBUTOR, ProjectId(10))

    val permissions = PermissionsTracker()

    permissions.expect(OrganizationId(1), listProjects = true, readOrganization = true)

    permissions.expect(ProjectId(10), readProject = true, listSites = true)

    permissions.expect(
        SiteId(100),
        SiteId(101),
        listFacilities = true,
        readSite = true,
        createLayer = true,
    )

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        readLayer = true,
        updateLayer = true,
        deleteLayer = true,
        createFeature = true,
    )

    permissions.expect(
        FeatureId(10010),
        readFeature = true,
        updateFeature = true,
        deleteFeature = true,
    )

    permissions.expect(PhotoId(10010), readFeaturePhoto = true, deleteFeaturePhoto = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        updateFacility = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        AccessionId(1000),
        AccessionId(1001),
        AccessionId(1010),
        AccessionId(1011),
        readAccession = true,
        updateAccession = true,
    )

    permissions.expect(
        AutomationId(1000),
        AutomationId(1001),
        AutomationId(1010),
        AutomationId(1011),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
    )

    permissions.expect(
        DeviceId(1000),
        DeviceId(1001),
        DeviceId(1010),
        DeviceId(1011),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `API clients are members of all projects, sites, facilities, and layers`() {
    usersDao.update(usersDao.fetchOneById(userId)!!.copy(userTypeId = UserType.APIClient))
    givenRole(OrganizationId(1), Role.CONTRIBUTOR)

    val permissions = PermissionsTracker()

    permissions.expect(OrganizationId(1), listProjects = true, readOrganization = true)

    permissions.expect(
        ProjectId(10),
        ProjectId(11),
        readProject = true,
        listSites = true,
    )

    permissions.expect(
        SiteId(100),
        SiteId(101),
        SiteId(110),
        SiteId(111),
        listFacilities = true,
        readSite = true,
        createLayer = true,
    )

    permissions.expect(
        LayerId(1000),
        LayerId(1001),
        LayerId(1100),
        readLayer = true,
        updateLayer = true,
        deleteLayer = true,
        createFeature = true,
    )

    permissions.expect(
        FeatureId(10010),
        FeatureId(11000),
        FeatureId(11001),
        readFeature = true,
        updateFeature = true,
        deleteFeature = true,
    )

    permissions.expect(
        PhotoId(10010),
        PhotoId(11000),
        PhotoId(11001),
        readFeaturePhoto = true,
        deleteFeaturePhoto = true)

    permissions.expect(
        FacilityId(1000),
        FacilityId(1001),
        FacilityId(1010),
        FacilityId(1011),
        FacilityId(1100),
        FacilityId(1101),
        FacilityId(1110),
        FacilityId(1111),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        updateFacility = true,
        listAutomations = true,
        sendAlert = true,
    )

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
        updateAccession = true,
    )

    permissions.expect(
        AutomationId(1000),
        AutomationId(1001),
        AutomationId(1010),
        AutomationId(1011),
        AutomationId(1100),
        AutomationId(1101),
        AutomationId(1110),
        AutomationId(1111),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
    )

    permissions.expect(
        DeviceId(1000),
        DeviceId(1001),
        DeviceId(1010),
        DeviceId(1011),
        DeviceId(1100),
        DeviceId(1101),
        DeviceId(1110),
        DeviceId(1111),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `user with no organization memberships has no organization-level permissions`() {
    // No givenRole() here; user has no roles anywhere.
    PermissionsTracker().andNothingElse()
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

  @Test
  fun `permissions require target objects to exist`() {
    givenRole(OrganizationId(1), Role.OWNER)

    dslContext.deleteFrom(TIMESERIES).execute()
    dslContext.deleteFrom(DEVICES).execute()
    dslContext.deleteFrom(AUTOMATIONS).execute()
    dslContext.deleteFrom(ACCESSIONS).execute()
    dslContext.deleteFrom(FACILITIES).execute()
    dslContext.deleteFrom(FEATURE_PHOTOS).execute()
    dslContext.deleteFrom(PHOTOS).execute()
    dslContext.deleteFrom(FEATURES).execute()
    dslContext.deleteFrom(LAYERS).execute()
    dslContext.deleteFrom(SITES).execute()
    dslContext.deleteFrom(PROJECT_USERS).execute()
    dslContext.deleteFrom(PROJECTS).execute()
    dslContext.deleteFrom(ORGANIZATION_USERS).execute()
    dslContext.deleteFrom(ORGANIZATIONS).execute()

    PermissionsTracker().andNothingElse()
  }

  inner class PermissionsTracker {
    private val uncheckedOrgs = organizationIds.toMutableSet()
    private val uncheckedProjects = projectIds.toMutableSet()
    private val uncheckedSites = siteIds.toMutableSet()
    private val uncheckedFacilities = facilityIds.toMutableSet()
    private val uncheckedAccessions = accessionIds.toMutableSet()
    private val uncheckedLayers = layerIds.toMutableSet()
    private val uncheckedFeatures = featureIds.toMutableSet()
    private val uncheckedPhotos = photoIds.toMutableSet()
    private val uncheckedAutomations = automationIds.toMutableSet()
    private val uncheckedDevices = deviceIds.toMutableSet()

    // All checks keyed on organization IDs go here
    fun expect(
        vararg organizations: OrganizationId,
        addOrganizationUser: Boolean = false,
        createProject: Boolean = false,
        listProjects: Boolean = false,
        readOrganization: Boolean = false,
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
            listProjects,
            user.canListProjects(organizationId),
            "Can list projects of organization $organizationId")
        assertEquals(
            readOrganization,
            user.canReadOrganization(organizationId),
            "Can read organization $organizationId")
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
        readProject: Boolean = false,
        updateProject: Boolean = false,
        addProjectUser: Boolean = false,
        createSite: Boolean = false,
        listSites: Boolean = false,
        removeProjectUser: Boolean = false,
    ) {
      projects.forEach { projectId ->
        assertEquals(readProject, user.canReadProject(projectId), "Can read project $projectId")
        assertEquals(
            updateProject, user.canUpdateProject(projectId), "Can update project $projectId")
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
    ) {
      sites.forEach { siteId ->
        assertEquals(
            createFacility, user.canCreateFacility(siteId), "Can create site $siteId facility")
        assertEquals(
            listFacilities, user.canListFacilities(siteId), "Can list site $siteId facilities")
        assertEquals(readSite, user.canReadSite(siteId), "Can read site $siteId")
        assertEquals(createLayer, user.canCreateLayer(siteId), "Can create layer at site $siteId")

        uncheckedSites.remove(siteId)
      }
    }

    // All checks keyed on facility IDs go here
    fun expect(
        vararg facilities: FacilityId,
        createAccession: Boolean = false,
        createAutomation: Boolean = false,
        createDevice: Boolean = false,
        updateFacility: Boolean = false,
        listAutomations: Boolean = false,
        sendAlert: Boolean = false,
    ) {
      facilities.forEach { facilityId ->
        assertEquals(
            createAccession,
            user.canCreateAccession(facilityId),
            "Can create accession at facility $facilityId")
        assertEquals(
            createAutomation,
            user.canCreateAutomation(facilityId),
            "Can create automation at facility $facilityId")
        assertEquals(
            createDevice,
            user.canCreateDevice(facilityId),
            "Can create device at facility $facilityId")
        assertEquals(
            updateFacility, user.canUpdateFacility(facilityId), "Can update facility $facilityId")
        assertEquals(
            listAutomations,
            user.canListAutomations(facilityId),
            "Can list automations at facility $facilityId")
        assertEquals(
            sendAlert, user.canSendAlert(facilityId), "Can send alert for facility $facilityId")

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
        readLayer: Boolean = false,
        updateLayer: Boolean = false,
        deleteLayer: Boolean = false,
        createFeature: Boolean = false,
    ) {
      layers.forEach { layerId ->
        assertEquals(readLayer, user.canReadLayer(layerId), "Can read layer $layerId")
        assertEquals(updateLayer, user.canUpdateLayer(layerId), "Can update layer $layerId")
        assertEquals(deleteLayer, user.canDeleteLayer(layerId), "Can delete layer $layerId")
        assertEquals(
            createFeature,
            user.canCreateFeature(layerId),
            "Can create feature associated with layer $layerId")

        uncheckedLayers.remove(layerId)
      }
    }

    // All checks keyed on feature IDs go here
    fun expect(
        vararg features: FeatureId,
        readFeature: Boolean = false,
        updateFeature: Boolean = false,
        deleteFeature: Boolean = false,
    ) {
      features.forEach { featureId ->
        assertEquals(readFeature, user.canReadFeature(featureId), "Can read feature $featureId")
        assertEquals(
            updateFeature, user.canUpdateFeature(featureId), "Can update feature $featureId")
        assertEquals(
            deleteFeature, user.canDeleteFeature(featureId), "Can delete feature $featureId")

        uncheckedFeatures.remove(featureId)
      }
    }

    // All checks keyed on photo IDs go here
    fun expect(
        vararg photos: PhotoId,
        readFeaturePhoto: Boolean = false,
        deleteFeaturePhoto: Boolean = false
    ) {
      photos.forEach { photoId ->
        assertEquals(
            readFeaturePhoto, user.canReadFeaturePhoto(photoId), "Can read feature photo $photoId")
        assertEquals(
            deleteFeaturePhoto,
            user.canDeleteFeaturePhoto(photoId),
            "Can delete feature photo $photoId")

        uncheckedPhotos.remove(photoId)
      }
    }

    // All checks keyed on automation IDs go here
    fun expect(
        vararg automations: AutomationId,
        readAutomation: Boolean = false,
        updateAutomation: Boolean = false,
        deleteAutomation: Boolean = false,
    ) {
      automations.forEach { automationId ->
        assertEquals(
            readAutomation,
            user.canReadAutomation(automationId),
            "Can read automation $automationId")
        assertEquals(
            updateAutomation,
            user.canUpdateAutomation(automationId),
            "Can update automation $automationId")
        assertEquals(
            deleteAutomation,
            user.canDeleteAutomation(automationId),
            "Can delete automation $automationId")

        uncheckedAutomations.remove(automationId)
      }
    }

    // All checks keyed on device IDs go here
    fun expect(
        vararg devices: DeviceId,
        createTimeseries: Boolean = false,
        readTimeseries: Boolean = false,
        updateTimeseries: Boolean = false,
        readDevice: Boolean = false,
        updateDevice: Boolean = false,
    ) {
      devices.forEach { deviceId ->
        assertEquals(
            createTimeseries,
            user.canCreateTimeseries(deviceId),
            "Can create timeseries for device $deviceId")
        assertEquals(
            readTimeseries,
            user.canReadTimeseries(deviceId),
            "Can read timeseries for device $deviceId")
        assertEquals(
            updateTimeseries,
            user.canUpdateTimeseries(deviceId),
            "Can update timeseries for device $deviceId")
        assertEquals(readDevice, user.canReadDevice(deviceId), "Can read device $deviceId")
        assertEquals(updateDevice, user.canUpdateDevice(deviceId), "Can update device $deviceId")

        uncheckedDevices.remove(deviceId)
      }
    }

    fun andNothingElse() {
      expect(*uncheckedOrgs.toTypedArray())
      expect(*uncheckedProjects.toTypedArray())
      expect(*uncheckedSites.toTypedArray())
      expect(*uncheckedFacilities.toTypedArray())
      expect(*uncheckedAccessions.toTypedArray())
      expect(*uncheckedLayers.toTypedArray())
      expect(*uncheckedFeatures.toTypedArray())
      expect(*uncheckedPhotos.toTypedArray())
      expect(*uncheckedAutomations.toTypedArray())
      expect(*uncheckedDevices.toTypedArray())
    }
  }
}
