package com.terraformation.backend.customer.model

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.PermissionTest.PermissionsTracker
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.BalenaDeviceId
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.AUTOMATIONS
import com.terraformation.backend.db.tables.references.DEVICES
import com.terraformation.backend.db.tables.references.DEVICE_MANAGERS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.db.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.tables.references.PROJECTS
import com.terraformation.backend.db.tables.references.PROJECT_USERS
import com.terraformation.backend.db.tables.references.SITES
import com.terraformation.backend.db.tables.references.SPECIES
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.tables.references.TIMESERIES
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
 *       Facility 1111
 *
 * Organization 2 - Incomplete tree structure
 *   Project 20 - No sites
 *   Project 21 - No facilities
 *     Site 210
 *   Project 22
 *     Site 220
 *       Facility 2200
 *
 * Organization 3 - No projects
 *
 * Upload 1 - created by the test's default user ID
 * ```
 *
 * The basic structure of each test is to:
 *
 * 1. Grant the user a role in an organization and possibly add them to some projects. In most
 * cases, you will add them to organization 1 and project 10 because there are some canned ID lists
 * you can use in assertions to make the tests clearer and more concise.
 * 2. Create a [PermissionsTracker] instance which should be used throughout the whole test.
 * 3. Use the tracker to assert which specific permissions the user should have on each of the above
 * objects.
 * 4. Call [PermissionsTracker.andNothingElse] which will check that the user doesn't have any
 * permissions other than the ones the test specifically said they should.
 */
internal class PermissionTest : DatabaseTest() {
  private lateinit var parentStore: ParentStore
  private lateinit var permissionStore: PermissionStore
  private lateinit var userStore: UserStore

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)!!
  private val realmResource: RealmResource = mockk()

  private val userId = UserId(1234)
  private val user: IndividualUser by lazy { userStore.fetchOneById(userId) }

  /*
   * Test data set; see class docs for a prettier version. This takes advantage of the default
   * "parent ID is our ID divided by 10" logic of the insert functions in DatabaseTest.
   */
  private val organizationIds = listOf(1, 2, 3).map { OrganizationId(it.toLong()) }
  private val org1Id = OrganizationId(1)

  private val speciesIds = organizationIds.map { SpeciesId(it.value) }

  private val projectIds = listOf(10, 11, 20, 21, 22).map { ProjectId(it.toLong()) }
  private val project10Id = ProjectId(10)

  private val siteIds = listOf(100, 101, 110, 111, 210, 220).map { SiteId(it.toLong()) }

  private val facilityIds =
      listOf(1000, 1001, 1010, 1011, 1100, 1101, 1110, 1111, 2200).map { FacilityId(it.toLong()) }

  private val accessionIds = facilityIds.map { AccessionId(it.value) }
  private val automationIds = facilityIds.map { AutomationId(it.value) }
  private val deviceIds = facilityIds.map { DeviceId(it.value) }
  private val storageLocationIds = facilityIds.map { StorageLocationId(it.value) }

  private val deviceManagerIds = listOf(1000L, 1100L, 2200L, 3000L).map { DeviceManagerId(it) }
  private val nonConnectedDeviceManagerIds = deviceManagerIds.filterToArray { it.value >= 3000 }

  private val otherUserId = UserId(8765)

  private val uploadId = UploadId(1)

  private inline fun <reified T> List<T>.filterToArray(func: (T) -> Boolean): Array<T> =
      filter(func).toTypedArray()
  private inline fun <reified T> List<T>.filterStartsWith(prefix: String): Array<T> =
      filter { "$it".startsWith(prefix) }.toTypedArray()
  private inline fun <reified T> List<T>.forOrg1() = filterStartsWith("1")

  @BeforeEach
  fun setUp() {
    every { realmResource.users() } returns mockk()

    parentStore = ParentStore(dslContext)
    permissionStore = PermissionStore(dslContext)
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            parentStore,
            permissionStore,
            realmResource,
            usersDao,
        )

    insertUser(userId)
    insertUser(otherUserId)
    organizationIds.forEach { insertOrganization(it, createdBy = userId) }
    projectIds.forEach { insertProject(it, it.value / 10, createdBy = userId) }
    siteIds.forEach { insertSite(it, it.value / 10, createdBy = userId) }

    facilityIds.forEach { facilityId ->
      insertFacility(facilityId, facilityId.value / 10, facilityId.value / 1000, createdBy = userId)
      insertDevice(facilityId.value, facilityId, createdBy = userId)
      insertAutomation(facilityId.value, facilityId, createdBy = userId)
      accessionsDao.insert(
          AccessionsRow(
              id = AccessionId(facilityId.value),
              facilityId = facilityId,
              stateId = AccessionState.Pending,
              createdBy = userId,
              createdTime = Instant.EPOCH,
              modifiedBy = userId,
              modifiedTime = Instant.EPOCH))
    }

    speciesIds.forEach { insertSpecies(it, organizationId = it.value, createdBy = userId) }
    storageLocationIds.forEach {
      insertStorageLocation(it, facilityId = it.value, createdBy = userId)
    }

    deviceManagerIds.forEach { deviceManagerId ->
      val facilityId = FacilityId(deviceManagerId.value)
      deviceManagersDao.insert(
          DeviceManagersRow(
              balenaId = BalenaDeviceId(deviceManagerId.value),
              balenaUuid = UUID.randomUUID().toString(),
              balenaModifiedTime = Instant.EPOCH,
              deviceName = "$deviceManagerId",
              id = deviceManagerId,
              isOnline = true,
              createdTime = Instant.EPOCH,
              refreshedTime = Instant.EPOCH,
              sensorKitId = "$deviceManagerId",
              facilityId = if (facilityId in facilityIds) facilityId else null,
              userId = if (facilityId in facilityIds) userId else null))
    }
  }

  @Test
  fun `owner role grants all permissions in organization projects, sites, and facilities`() {
    givenRole(org1Id, Role.OWNER)
    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        createProject = true,
        listProjects = true,
        readOrganization = true,
        updateOrganization = true,
        deleteOrganization = true,
        listOrganizationUsers = true,
        addOrganizationUser = true,
        removeOrganizationUser = true,
        removeOrganizationSelf = true,
        createSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        readProject = true,
        updateProject = true,
        addProjectUser = true,
        createSite = true,
        listSites = true,
        removeProjectUser = true,
        removeProjectSelf = true,
    )

    permissions.expect(
        *siteIds.forOrg1(),
        createFacility = true,
        listFacilities = true,
        readSite = true,
        updateSite = true,
        deleteSite = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        createStorageLocation = true,
        updateFacility = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        readAccession = true,
        updateAccession = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
        triggerAutomation = true,
    )

    permissions.expect(
        *deviceManagerIds.forOrg1(),
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
        updateDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forOrg1(),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        readSpecies = true,
        updateSpecies = true,
        deleteSpecies = true,
    )

    permissions.expect(
        *storageLocationIds.forOrg1(),
        readStorageLocation = true,
        updateStorageLocation = true,
        deleteStorageLocation = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `owner role in empty organization grants organization-level permissions`() {
    givenRole(OrganizationId(3), Role.OWNER)

    val permissions = PermissionsTracker()

    permissions.expect(
        OrganizationId(3),
        createProject = true,
        listProjects = true,
        readOrganization = true,
        updateOrganization = true,
        deleteOrganization = true,
        listOrganizationUsers = true,
        addOrganizationUser = true,
        removeOrganizationUser = true,
        removeOrganizationSelf = true,
        createSpecies = true,
    )

    permissions.expect(
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
        updateDeviceManager = true,
    )

    permissions.expect(
        SpeciesId(3),
        readSpecies = true,
        updateSpecies = true,
        deleteSpecies = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `admin role grants all permissions except deleting organization`() {
    givenRole(org1Id, Role.ADMIN)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        createProject = true,
        listProjects = true,
        readOrganization = true,
        updateOrganization = true,
        listOrganizationUsers = true,
        addOrganizationUser = true,
        removeOrganizationUser = true,
        removeOrganizationSelf = true,
        createSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        readProject = true,
        updateProject = true,
        addProjectUser = true,
        createSite = true,
        listSites = true,
        removeProjectUser = true,
        removeProjectSelf = true,
    )

    permissions.expect(
        *siteIds.forOrg1(),
        createFacility = true,
        listFacilities = true,
        readSite = true,
        updateSite = true,
        deleteSite = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        createStorageLocation = true,
        updateFacility = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        readAccession = true,
        updateAccession = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
        triggerAutomation = true,
    )

    permissions.expect(
        *deviceManagerIds.forOrg1(),
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
        updateDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forOrg1(),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        readSpecies = true,
        updateSpecies = true,
        deleteSpecies = true,
    )

    permissions.expect(
        *storageLocationIds.forOrg1(),
        readStorageLocation = true,
        updateStorageLocation = true,
        deleteStorageLocation = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `managers can add users to projects and access all data in their organizations`() {
    givenRole(org1Id, Role.MANAGER, project10Id)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        listProjects = true,
        readOrganization = true,
        listOrganizationUsers = true,
        removeOrganizationSelf = true,
        createSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        readProject = true,
        addProjectUser = true,
        listSites = true,
        removeProjectUser = true,
        removeProjectSelf = true,
    )

    permissions.expect(
        *siteIds.forOrg1(),
        listFacilities = true,
        readSite = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        readAccession = true,
        updateAccession = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
        triggerAutomation = true,
    )

    permissions.expect(
        *deviceManagerIds.forOrg1(),
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forOrg1(),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        readSpecies = true,
        updateSpecies = true,
        deleteSpecies = true,
    )

    permissions.expect(
        *storageLocationIds.forOrg1(),
        readStorageLocation = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `contributors have access to data associated with their organizations`() {
    givenRole(org1Id, Role.CONTRIBUTOR, project10Id)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        listProjects = true,
        readOrganization = true,
        removeOrganizationSelf = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        readProject = true,
        listSites = true,
        removeProjectSelf = true,
    )

    permissions.expect(
        *siteIds.forOrg1(),
        listFacilities = true,
        readSite = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        readAccession = true,
        updateAccession = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
        triggerAutomation = true,
    )

    permissions.expect(
        *deviceManagerIds.forOrg1(),
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forOrg1(),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        readSpecies = true,
    )

    permissions.expect(
        *storageLocationIds.forOrg1(),
        readStorageLocation = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `API clients are members of all projects, sites, and facilities`() {
    usersDao.update(usersDao.fetchOneById(userId)!!.copy(userTypeId = UserType.APIClient))
    givenRole(org1Id, Role.CONTRIBUTOR)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        listProjects = true,
        readOrganization = true,
        removeOrganizationSelf = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        readProject = true,
        listSites = true,
        removeProjectSelf = true,
    )

    permissions.expect(
        *siteIds.forOrg1(),
        listFacilities = true,
        readSite = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createDevice = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        readAccession = true,
        updateAccession = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
        triggerAutomation = true,
    )

    permissions.expect(
        *deviceManagerIds.forOrg1(),
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forOrg1(),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        readSpecies = true,
    )

    permissions.expect(
        *storageLocationIds.forOrg1(),
        readStorageLocation = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `user with no organization memberships has no organization-level permissions`() {
    val permissions = PermissionsTracker()

    permissions.expect(
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `super admin user has elevated privileges`() {
    usersDao.update(usersDao.fetchOneById(userId)!!.copy(userTypeId = UserType.SuperAdmin))

    val permissions = PermissionsTracker()

    permissions.expect(
        createDeviceManager = true,
        importGlobalSpeciesData = true,
        setTestClock = true,
        updateDeviceTemplates = true,
    )
  }

  @Test
  fun `user can access their own uploads`() {
    insertUpload(uploadId, createdBy = userId)

    assertTrue(user.canReadUpload(uploadId), "Can read upload")
    assertTrue(user.canUpdateUpload(uploadId), "Can update upload")
    assertTrue(user.canDeleteUpload(uploadId), "Can delete upload")
  }

  @Test
  fun `user cannot access uploads of other users`() {
    insertUpload(uploadId, createdBy = otherUserId)

    assertFalse(user.canReadUpload(uploadId), "Can read upload")
    assertFalse(user.canUpdateUpload(uploadId), "Can update upload")
    assertFalse(user.canDeleteUpload(uploadId), "Can delete upload")
  }

  private fun givenRole(organizationId: OrganizationId, role: Role, vararg projects: ProjectId) {
    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(USER_ID, userId)
          .set(ORGANIZATION_ID, organizationId)
          .set(ROLE_ID, role.id)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .execute()
    }

    projects.forEach { projectId ->
      with(PROJECT_USERS) {
        dslContext
            .insertInto(PROJECT_USERS)
            .set(USER_ID, userId)
            .set(PROJECT_ID, projectId)
            .set(CREATED_BY, userId)
            .set(CREATED_TIME, Instant.EPOCH)
            .set(MODIFIED_BY, userId)
            .set(MODIFIED_TIME, Instant.EPOCH)
            .execute()
      }
    }
  }

  @Test
  fun `permissions require target objects to exist`() {
    givenRole(org1Id, Role.OWNER)

    dslContext.deleteFrom(STORAGE_LOCATIONS).execute()
    dslContext.deleteFrom(TIMESERIES).execute()
    dslContext.deleteFrom(DEVICE_MANAGERS).execute()
    dslContext.deleteFrom(AUTOMATIONS).execute()
    dslContext.deleteFrom(DEVICES).execute()
    dslContext.deleteFrom(ACCESSIONS).execute()
    dslContext.deleteFrom(FACILITIES).execute()
    dslContext.deleteFrom(SITES).execute()
    dslContext.deleteFrom(PROJECT_USERS).execute()
    dslContext.deleteFrom(PROJECTS).execute()
    dslContext.deleteFrom(SPECIES).execute()
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
    private val uncheckedAutomations = automationIds.toMutableSet()
    private val uncheckedDeviceManagers = deviceManagerIds.toMutableSet()
    private val uncheckedDevices = deviceIds.toMutableSet()
    private val uncheckedSpecies = speciesIds.toMutableSet()
    private val uncheckedStorageLocationIds = storageLocationIds.toMutableSet()

    private var hasCheckedGlobalPermissions = false

    fun expect(
        vararg organizations: OrganizationId,
        createProject: Boolean = false,
        listProjects: Boolean = false,
        readOrganization: Boolean = false,
        updateOrganization: Boolean = false,
        deleteOrganization: Boolean = false,
        listOrganizationUsers: Boolean = false,
        addOrganizationUser: Boolean = false,
        removeOrganizationUser: Boolean = false,
        removeOrganizationSelf: Boolean = false,
        createSpecies: Boolean = false,
    ) {
      organizations.forEach { organizationId ->
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
            updateOrganization,
            user.canUpdateOrganization(organizationId),
            "Can update organization $organizationId")
        assertEquals(
            deleteOrganization,
            user.canDeleteOrganization(organizationId),
            "Can delete organization $organizationId")
        assertEquals(
            listOrganizationUsers,
            user.canListOrganizationUsers(organizationId),
            "Can list users in organization $organizationId")
        assertEquals(
            addOrganizationUser,
            user.canAddOrganizationUser(organizationId),
            "Can add organization $organizationId user")
        assertEquals(
            removeOrganizationUser,
            user.canRemoveOrganizationUser(organizationId, otherUserId),
            "Can remove user from organization $organizationId")
        assertEquals(
            removeOrganizationSelf,
            user.canRemoveOrganizationUser(organizationId, userId),
            "Can remove self from organization $organizationId")
        assertEquals(
            createSpecies,
            user.canCreateSpecies(organizationId),
            "Can create species in organization $organizationId")

        uncheckedOrgs.remove(organizationId)
      }
    }

    fun expect(
        vararg projects: ProjectId,
        readProject: Boolean = false,
        updateProject: Boolean = false,
        addProjectUser: Boolean = false,
        createSite: Boolean = false,
        listSites: Boolean = false,
        removeProjectUser: Boolean = false,
        removeProjectSelf: Boolean = false,
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
            user.canRemoveProjectUser(projectId, otherUserId),
            "Can remove project $projectId user")
        assertEquals(
            removeProjectSelf,
            user.canRemoveProjectUser(projectId, userId),
            "Can remove self from project $projectId")

        uncheckedProjects.remove(projectId)
      }
    }

    fun expect(
        vararg sites: SiteId,
        createFacility: Boolean = false,
        listFacilities: Boolean = false,
        readSite: Boolean = false,
        updateSite: Boolean = false,
        deleteSite: Boolean = false,
    ) {
      sites.forEach { siteId ->
        assertEquals(
            createFacility, user.canCreateFacility(siteId), "Can create site $siteId facility")
        assertEquals(
            listFacilities, user.canListFacilities(siteId), "Can list site $siteId facilities")
        assertEquals(readSite, user.canReadSite(siteId), "Can read site $siteId")
        assertEquals(updateSite, user.canUpdateSite(siteId), "Can update site $siteId")
        assertEquals(deleteSite, user.canDeleteSite(siteId), "Can delete site $siteId")

        uncheckedSites.remove(siteId)
      }
    }

    fun expect(
        vararg facilities: FacilityId,
        createAccession: Boolean = false,
        createAutomation: Boolean = false,
        createDevice: Boolean = false,
        createStorageLocation: Boolean = false,
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
            createStorageLocation,
            user.canCreateStorageLocation(facilityId),
            "Can create storage location at facility $facilityId")
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

    fun expect(
        vararg automations: AutomationId,
        readAutomation: Boolean = false,
        updateAutomation: Boolean = false,
        deleteAutomation: Boolean = false,
        triggerAutomation: Boolean = false,
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
        assertEquals(
            triggerAutomation,
            user.canTriggerAutomation(automationId),
            "Can trigger automation $automationId")

        uncheckedAutomations.remove(automationId)
      }
    }

    fun expect(
        vararg deviceManagerIds: DeviceManagerId,
        readDeviceManager: Boolean = false,
        updateDeviceManager: Boolean = false,
    ) {
      deviceManagerIds.forEach { deviceManagerId ->
        assertEquals(
            readDeviceManager,
            user.canReadDeviceManager(deviceManagerId),
            "Can read device manager $deviceManagerId")
        assertEquals(
            updateDeviceManager,
            user.canUpdateDeviceManager(deviceManagerId),
            "Can update device manager $deviceManagerId")

        uncheckedDeviceManagers.remove(deviceManagerId)
      }
    }

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

    fun expect(
        vararg speciesIds: SpeciesId,
        readSpecies: Boolean = false,
        updateSpecies: Boolean = false,
        deleteSpecies: Boolean = false,
    ) {
      speciesIds.forEach { speciesId ->
        assertEquals(readSpecies, user.canReadSpecies(speciesId), "Can read species $speciesId")
        assertEquals(
            updateSpecies, user.canUpdateSpecies(speciesId), "Can update species $speciesId")
        assertEquals(
            deleteSpecies, user.canDeleteSpecies(speciesId), "Can delete species $speciesId")

        uncheckedSpecies.remove(speciesId)
      }
    }

    fun expect(
        vararg storageLocationIds: StorageLocationId,
        readStorageLocation: Boolean = false,
        updateStorageLocation: Boolean = false,
        deleteStorageLocation: Boolean = false,
    ) {
      storageLocationIds.forEach { storageLocationId ->
        assertEquals(
            readStorageLocation,
            user.canReadStorageLocation(storageLocationId),
            "Can read storage location $storageLocationId")
        assertEquals(
            updateStorageLocation,
            user.canUpdateStorageLocation(storageLocationId),
            "Can update storage location $storageLocationId")
        assertEquals(
            deleteStorageLocation,
            user.canDeleteStorageLocation(storageLocationId),
            "Can delete storage location $storageLocationId")

        uncheckedStorageLocationIds.remove(storageLocationId)
      }
    }

    /** Checks for globally-scoped permissions. */
    fun expect(
        createDeviceManager: Boolean = false,
        importGlobalSpeciesData: Boolean = false,
        setTestClock: Boolean = false,
        updateDeviceTemplates: Boolean = false,
    ) {
      assertEquals(createDeviceManager, user.canCreateDeviceManager(), "Can create device manager")
      assertEquals(
          importGlobalSpeciesData,
          user.canImportGlobalSpeciesData(),
          "Can import global species data")
      assertEquals(setTestClock, user.canSetTestClock(), "Can set test clock")
      assertEquals(
          updateDeviceTemplates, user.canUpdateDeviceTemplates(), "Can update device templates")

      hasCheckedGlobalPermissions = true
    }

    fun andNothingElse() {
      expect(*uncheckedAccessions.toTypedArray())
      expect(*uncheckedAutomations.toTypedArray())
      expect(*uncheckedDeviceManagers.toTypedArray())
      expect(*uncheckedDevices.toTypedArray())
      expect(*uncheckedFacilities.toTypedArray())
      expect(*uncheckedOrgs.toTypedArray())
      expect(*uncheckedProjects.toTypedArray())
      expect(*uncheckedSites.toTypedArray())
      expect(*uncheckedSpecies.toTypedArray())
      expect(*uncheckedStorageLocationIds.toTypedArray())

      if (!hasCheckedGlobalPermissions) {
        expect()
      }
    }
  }
}
