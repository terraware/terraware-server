package com.terraformation.backend.customer.model

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.PermissionTest.PermissionsTracker
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.db.default_schema.tables.references.DEVICES
import com.terraformation.backend.db.default_schema.tables.references.DEVICE_MANAGERS
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
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
 * ```
 * Organization 1 - Two of everything (currently "everything" is just "facilities")
 *   Facility 1000
 *   Facility 1001
 *
 * Organization 2 - No facilities or planting sites
 *
 * Organization 3 - One of everything, but current user isn't a member
 *
 * Upload 1 - created by the test's default user ID
 * ```
 *
 * The basic structure of each test is to:
 * 1. Grant the user a role in an organization. In most cases, you will add them to organization 1
 *    because there are some canned ID lists you can use in assertions to make the tests clearer and
 *    more concise.
 * 2. Create a [PermissionsTracker] instance which should be used throughout the whole test.
 * 3. Use the tracker to assert which specific permissions the user should have on each of the above
 *    objects.
 * 4. Call [PermissionsTracker.andNothingElse] which will check that the user doesn't have any
 *    permissions other than the ones the test specifically said they should.
 */
internal class PermissionTest : DatabaseTest() {
  private lateinit var parentStore: ParentStore
  private lateinit var permissionStore: PermissionStore
  private lateinit var userStore: UserStore

  @Autowired private lateinit var config: TerrawareServerConfig

  private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)!!
  private val realmResource: RealmResource = mockk()

  private val userId = UserId(1234)
  private val user: TerrawareUser by lazy { fetchUser() }

  /*
   * Test data set; see class docs for a prettier version. This takes advantage of the default
   * "parent ID is our ID divided by 10" logic of the insert functions in DatabaseTest.
   */
  private val nonEmptyOrganizationIds = listOf(OrganizationId(1), OrganizationId(3))
  private val organizationIds = nonEmptyOrganizationIds + OrganizationId(2)
  private val org1Id = OrganizationId(1)

  // Org 2 is empty (no reports or species)
  private val reportIds = nonEmptyOrganizationIds.map { ReportId(it.value) }
  private val speciesIds = nonEmptyOrganizationIds.map { SpeciesId(it.value) }

  private val facilityIds = listOf(1000, 1001, 3000).map { FacilityId(it.toLong()) }
  private val plantingSiteIds = facilityIds.map { PlantingSiteId(it.value) }
  private val plantingZoneIds = facilityIds.map { PlantingZoneId(it.value) }

  private val accessionIds = facilityIds.map { AccessionId(it.value) }
  private val automationIds = facilityIds.map { AutomationId(it.value) }
  private val batchIds = facilityIds.map { BatchId(it.value) }
  private val deviceIds = facilityIds.map { DeviceId(it.value) }
  private val storageLocationIds = facilityIds.map { StorageLocationId(it.value) }
  private val viabilityTestIds = facilityIds.map { ViabilityTestId(it.value) }
  private val withdrawalIds = facilityIds.map { WithdrawalId(it.value) }

  private val deliveryIds = plantingSiteIds.map { DeliveryId(it.value) }
  private val plantingIds = plantingSiteIds.map { PlantingId(it.value) }

  private val deviceManagerIds = listOf(1000L, 1001L, 2000L).map { DeviceManagerId(it) }
  private val nonConnectedDeviceManagerIds = deviceManagerIds.filterToArray { it.value >= 2000 }

  private val sameOrgUserId = UserId(8765)
  private val otherUserIds =
      mapOf(
          OrganizationId(1) to sameOrgUserId,
          OrganizationId(2) to UserId(8766),
          OrganizationId(3) to UserId(9876))

  private val uploadId = UploadId(1)

  private inline fun <reified T> List<T>.filterToArray(func: (T) -> Boolean): Array<T> =
      filter(func).toTypedArray()
  private inline fun <reified T> List<T>.filterStartsWith(prefix: String): Array<T> =
      filter { "$it".startsWith(prefix) }.toTypedArray()
  private inline fun <reified T> List<T>.forOrg1() = filterStartsWith("1")
  private inline fun <reified T> List<T>.forFacility1000() = filterStartsWith("1000")

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
            parentStore,
            permissionStore,
            mockk(),
            realmResource,
            usersDao,
        )

    insertUser(userId)

    organizationIds.forEach { organizationId ->
      insertOrganization(organizationId, createdBy = userId)
      insertSpecies(organizationId.value, organizationId = organizationId, createdBy = userId)
    }

    otherUserIds.forEach { (organizationId, otherUserId) ->
      insertUser(otherUserId)
      insertOrganizationUser(otherUserId, organizationId, createdBy = userId)
    }

    facilityIds.forEach { facilityId ->
      val organizationId = facilityId.value / 1000

      insertFacility(facilityId, organizationId, createdBy = userId)
      insertDevice(facilityId.value, facilityId, createdBy = userId)
      insertAutomation(facilityId.value, facilityId, createdBy = userId)
      insertAccession(id = facilityId.value, facilityId = facilityId, createdBy = userId)
      viabilityTestsDao.insert(
          ViabilityTestsRow(
              accessionId = AccessionId(facilityId.value),
              id = ViabilityTestId(facilityId.value),
              seedsSown = 1,
              testType = ViabilityTestType.Lab))

      insertBatch(
          createdBy = userId,
          id = facilityId.value,
          facilityId = facilityId,
          organizationId = organizationId,
          speciesId = organizationId,
      )
      insertWithdrawal(
          createdBy = userId,
          facilityId = facilityId,
          id = facilityId.value,
          purpose = WithdrawalPurpose.OutPlant,
      )
    }

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
              userId = if (facilityId in facilityIds) sameOrgUserId else null))
    }

    plantingSiteIds.forEach { plantingSiteId ->
      val organizationId = OrganizationId(plantingSiteId.value / 1000)
      insertPlantingSite(
          createdBy = userId,
          id = plantingSiteId,
          organizationId = organizationId,
      )
      insertDelivery(
          createdBy = userId,
          id = plantingSiteId.value,
          plantingSiteId = plantingSiteId.value,
          withdrawalId = plantingSiteId.value,
      )
      insertPlanting(
          createdBy = userId,
          deliveryId = plantingSiteId.value,
          id = plantingSiteId.value,
          speciesId = organizationId.value,
      )
    }

    plantingZoneIds.forEach { plantingZoneId ->
      insertPlantingZone(
          createdBy = userId,
          id = plantingZoneId,
          plantingSiteId = PlantingSiteId(plantingZoneId.value),
      )
    }

    reportIds.forEach { reportId ->
      val organizationId = OrganizationId(reportId.value)
      insertReport(id = reportId, organizationId = organizationId)
    }
  }

  @Test
  fun `owner role grants all permissions in organization projects, sites, and facilities`() {
    givenRole(org1Id, Role.Owner)
    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        readOrganization = true,
        updateOrganization = true,
        deleteOrganization = true,
        listOrganizationUsers = true,
        readOrganizationUser = true,
        readOrganizationSelf = true,
        addOrganizationUser = true,
        removeOrganizationUser = true,
        removeOrganizationSelf = true,
        createSpecies = true,
        createFacility = true,
        listFacilities = true,
        createPlantingSite = true,
        listReports = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
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
        deleteAccession = true,
        setWithdrawalUser = true,
        uploadPhoto = true,
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

    permissions.expect(
        *viabilityTestIds.forOrg1(),
        readViabilityTest = true,
    )

    permissions.expect(
        *batchIds.forOrg1(),
        deleteBatch = true,
        readBatch = true,
        updateBatch = true,
    )

    permissions.expect(
        *withdrawalIds.forOrg1(),
        createWithdrawalPhoto = true,
        readWithdrawal = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        readPlantingSite = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
        updatePlantingZone = true,
    )

    permissions.expect(
        *deliveryIds.forOrg1(),
        readDelivery = true,
        updateDelivery = true,
    )

    permissions.expect(
        *plantingIds.forOrg1(),
        readPlanting = true,
    )

    permissions.expect(
        *reportIds.forOrg1(),
        deleteReport = true,
        readReport = true,
        updateReport = true,
    )

    permissions.expect(
        deleteSelf = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `owner role in empty organization grants organization-level permissions`() {
    givenRole(OrganizationId(2), Role.Owner)

    val permissions = PermissionsTracker()

    permissions.expect(
        OrganizationId(2),
        readOrganization = true,
        updateOrganization = true,
        deleteOrganization = true,
        listOrganizationUsers = true,
        readOrganizationUser = true,
        readOrganizationSelf = true,
        addOrganizationUser = true,
        removeOrganizationUser = true,
        removeOrganizationSelf = true,
        createSpecies = true,
        createFacility = true,
        listFacilities = true,
        createPlantingSite = true,
        listReports = true,
    )

    permissions.expect(
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
        updateDeviceManager = true,
    )

    permissions.expect(
        deleteSelf = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `admin role grants all permissions except deleting organization`() {
    givenRole(org1Id, Role.Admin)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        readOrganization = true,
        updateOrganization = true,
        listOrganizationUsers = true,
        readOrganizationUser = true,
        readOrganizationSelf = true,
        addOrganizationUser = true,
        removeOrganizationUser = true,
        removeOrganizationSelf = true,
        createSpecies = true,
        createFacility = true,
        listFacilities = true,
        createPlantingSite = true,
        listReports = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
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
        deleteAccession = true,
        setWithdrawalUser = true,
        uploadPhoto = true,
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

    permissions.expect(
        *viabilityTestIds.forOrg1(),
        readViabilityTest = true,
    )

    permissions.expect(
        *batchIds.forOrg1(),
        deleteBatch = true,
        readBatch = true,
        updateBatch = true,
    )

    permissions.expect(
        *withdrawalIds.forOrg1(),
        createWithdrawalPhoto = true,
        readWithdrawal = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        readPlantingSite = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
        updatePlantingZone = true,
    )

    permissions.expect(
        *deliveryIds.forOrg1(),
        readDelivery = true,
        updateDelivery = true,
    )

    permissions.expect(
        *plantingIds.forOrg1(),
        readPlanting = true,
    )

    permissions.expect(
        *reportIds.forOrg1(),
        deleteReport = true,
        readReport = true,
        updateReport = true,
    )

    permissions.expect(
        deleteSelf = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `managers can add users to projects and access all data in their organizations`() {
    givenRole(org1Id, Role.Manager)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        readOrganization = true,
        listOrganizationUsers = true,
        readOrganizationUser = true,
        readOrganizationSelf = true,
        removeOrganizationSelf = true,
        createSpecies = true,
        listFacilities = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createBatch = true,
        listAutomations = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        readAccession = true,
        updateAccession = true,
        deleteAccession = true,
        setWithdrawalUser = true,
        uploadPhoto = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        readAutomation = true,
    )

    permissions.expect(
        *deviceManagerIds.forOrg1(),
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forOrg1(),
        readTimeseries = true,
        readDevice = true,
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

    permissions.expect(
        *viabilityTestIds.forOrg1(),
        readViabilityTest = true,
    )

    permissions.expect(
        *batchIds.forOrg1(),
        deleteBatch = true,
        readBatch = true,
        updateBatch = true,
    )

    permissions.expect(
        *withdrawalIds.forOrg1(),
        createWithdrawalPhoto = true,
        readWithdrawal = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        readPlantingSite = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
    )

    permissions.expect(
        *deliveryIds.forOrg1(),
        readDelivery = true,
        updateDelivery = true,
    )

    permissions.expect(
        *plantingIds.forOrg1(),
        readPlanting = true,
    )

    permissions.expect(
        deleteSelf = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `contributors have full read and limited write access to data associated with their organizations`() {
    givenRole(org1Id, Role.Contributor)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        readOrganization = true,
        readOrganizationSelf = true,
        removeOrganizationSelf = true,
        listFacilities = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createBatch = true,
        listAutomations = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        readAccession = true,
        updateAccession = false,
        deleteAccession = false,
        uploadPhoto = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        readAutomation = true,
    )

    permissions.expect(
        *deviceManagerIds.forOrg1(),
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forOrg1(),
        readTimeseries = true,
        readDevice = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        readSpecies = true,
    )

    permissions.expect(
        *storageLocationIds.forOrg1(),
        readStorageLocation = true,
    )

    permissions.expect(
        *viabilityTestIds.forOrg1(),
        readViabilityTest = true,
    )

    permissions.expect(
        *batchIds.forOrg1(),
        deleteBatch = true,
        readBatch = true,
        updateBatch = true,
    )

    permissions.expect(
        *withdrawalIds.forOrg1(),
        createWithdrawalPhoto = true,
        readWithdrawal = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        readPlantingSite = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
    )

    permissions.expect(
        *deliveryIds.forOrg1(),
        readDelivery = true,
        updateDelivery = true,
    )

    permissions.expect(
        *plantingIds.forOrg1(),
        readPlanting = true,
    )

    permissions.expect(
        deleteSelf = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `device managers only have access to device operations at their connected facility`() {
    // Associate the current user with one of the device managers.
    usersDao.update(usersDao.fetchOneById(userId)!!.copy(userTypeId = UserType.DeviceManager))
    val deviceManagerId = deviceManagerIds.first()
    val facilityId = facilityIds.first()
    deviceManagersDao.update(
        deviceManagersDao
            .fetchOneById(deviceManagerId)!!
            .copy(facilityId = facilityId, userId = userId))

    givenRole(org1Id, Role.Contributor)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        readOrganization = true,
        listFacilities = true,
    )

    permissions.expect(
        facilityId,
        createAutomation = true,
        createDevice = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        *automationIds.forFacility1000(),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
        triggerAutomation = true,
    )

    permissions.expect(
        deviceManagerId,
        readDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forFacility1000(),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `system user can perform most operations`() {
    usersDao.update(usersDao.fetchOneById(userId)!!.copy(userTypeId = UserType.System))

    val permissions = PermissionsTracker()

    permissions.expect(
        *organizationIds.toTypedArray(),
        readOrganization = true,
        updateOrganization = true,
        deleteOrganization = true,
        listOrganizationUsers = true,
        readOrganizationUser = true,
        readOrganizationSelf = true,
        addOrganizationUser = true,
        removeOrganizationUser = true,
        removeOrganizationSelf = true,
        createSpecies = true,
        createFacility = true,
        listFacilities = true,
        createPlantingSite = true,
        createReport = true,
        listReports = true,
    )

    permissions.expect(
        *facilityIds.toTypedArray(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
        createDevice = true,
        createStorageLocation = true,
        updateFacility = true,
        listAutomations = true,
        sendAlert = true,
    )

    permissions.expect(
        *accessionIds.toTypedArray(),
        readAccession = true,
        updateAccession = true,
        deleteAccession = true,
        setWithdrawalUser = true,
        uploadPhoto = true,
    )

    permissions.expect(
        *automationIds.toTypedArray(),
        readAutomation = true,
        updateAutomation = true,
        deleteAutomation = true,
        triggerAutomation = true,
    )

    permissions.expect(
        *deviceManagerIds.toTypedArray(),
        *nonConnectedDeviceManagerIds,
        readDeviceManager = true,
        updateDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.toTypedArray(),
        createTimeseries = true,
        readTimeseries = true,
        updateTimeseries = true,
        readDevice = true,
        updateDevice = true,
    )

    permissions.expect(
        *speciesIds.toTypedArray(),
        readSpecies = true,
        updateSpecies = true,
        deleteSpecies = true,
    )

    permissions.expect(
        *storageLocationIds.toTypedArray(),
        readStorageLocation = true,
        updateStorageLocation = true,
        deleteStorageLocation = true,
    )

    permissions.expect(
        *viabilityTestIds.toTypedArray(),
        readViabilityTest = true,
    )

    permissions.expect(
        *batchIds.toTypedArray(),
        deleteBatch = true,
        readBatch = true,
        updateBatch = true,
    )

    permissions.expect(
        *withdrawalIds.toTypedArray(),
        createWithdrawalPhoto = true,
        readWithdrawal = true,
    )

    permissions.expect(
        createDeviceManager = true,
        setTestClock = true,
        updateAppVersions = true,
        updateDeviceTemplates = true,
    )

    permissions.expect(
        *plantingSiteIds.toTypedArray(),
        createDelivery = true,
        movePlantingSiteToAnyOrg = true,
        readPlantingSite = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *plantingZoneIds.toTypedArray(),
        readPlantingZone = true,
        updatePlantingZone = true,
    )

    permissions.expect(
        *deliveryIds.toTypedArray(),
        readDelivery = true,
        updateDelivery = true,
    )

    permissions.expect(
        *plantingIds.toTypedArray(),
        readPlanting = true,
    )

    permissions.expect(
        *reportIds.toTypedArray(),
        readReport = true,
        updateReport = true,
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

    permissions.expect(
        deleteSelf = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `super admin user has elevated privileges`() {
    usersDao.update(usersDao.fetchOneById(userId)!!.copy(userTypeId = UserType.SuperAdmin))

    givenRole(org1Id, Role.Admin)

    val permissions = PermissionsTracker()

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        movePlantingSiteToAnyOrg = true,
        readPlantingSite = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        createDeviceManager = true,
        deleteSelf = true,
        importGlobalSpeciesData = true,
        manageInternalTags = true,
        regenerateAllDeviceManagerTokens = true,
        setTestClock = true,
        updateAppVersions = true,
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
    insertUpload(uploadId, createdBy = sameOrgUserId)

    assertFalse(user.canReadUpload(uploadId), "Can read upload")
    assertFalse(user.canUpdateUpload(uploadId), "Can update upload")
    assertFalse(user.canDeleteUpload(uploadId), "Can delete upload")
  }

  private fun givenRole(organizationId: OrganizationId, role: Role) {
    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(USER_ID, userId)
          .set(ORGANIZATION_ID, organizationId)
          .set(ROLE_ID, role)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .execute()
    }
  }

  private fun fetchUser(): TerrawareUser {
    val user = userStore.fetchOneById(userId)
    return if (user.userType == UserType.System) {
      SystemUser(usersDao)
    } else {
      user
    }
  }

  @Test
  fun `permissions require target objects to exist`() {
    val permissions = PermissionsTracker()

    givenRole(org1Id, Role.Owner)

    dslContext.deleteFrom(REPORTS).execute()
    dslContext.deleteFrom(WITHDRAWALS).execute()
    dslContext.deleteFrom(BATCHES).execute()
    dslContext.deleteFrom(VIABILITY_TESTS).execute()
    dslContext.deleteFrom(STORAGE_LOCATIONS).execute()
    dslContext.deleteFrom(TIMESERIES).execute()
    dslContext.deleteFrom(DEVICE_MANAGERS).execute()
    dslContext.deleteFrom(AUTOMATIONS).execute()
    dslContext.deleteFrom(DEVICES).execute()
    dslContext.deleteFrom(ACCESSIONS).execute()
    dslContext.deleteFrom(FACILITIES).execute()
    dslContext.deleteFrom(PLANTING_ZONES).execute()
    dslContext.deleteFrom(PLANTING_SITES).execute()
    dslContext.deleteFrom(SPECIES).execute()
    dslContext.deleteFrom(ORGANIZATION_USERS).execute()
    dslContext.deleteFrom(ORGANIZATIONS).execute()

    permissions.expect(
        deleteSelf = true,
    )

    permissions.andNothingElse()
  }

  inner class PermissionsTracker {
    private val uncheckedOrgs = organizationIds.toMutableSet()
    private val uncheckedFacilities = facilityIds.toMutableSet()
    private val uncheckedAccessions = accessionIds.toMutableSet()
    private val uncheckedAutomations = automationIds.toMutableSet()
    private val uncheckedBatches = batchIds.toMutableSet()
    private val uncheckedDeliveries = deliveryIds.toMutableSet()
    private val uncheckedDeviceManagers = deviceManagerIds.toMutableSet()
    private val uncheckedDevices = deviceIds.toMutableSet()
    private val uncheckedPlantings = plantingIds.toMutableSet()
    private val uncheckedPlantingSites = plantingSiteIds.toMutableSet()
    private val uncheckedPlantingZones = plantingZoneIds.toMutableSet()
    private val uncheckedReports = reportIds.toMutableSet()
    private val uncheckedSpecies = speciesIds.toMutableSet()
    private val uncheckedStorageLocationIds = storageLocationIds.toMutableSet()
    private val uncheckedViabilityTestIds = viabilityTestIds.toMutableSet()
    private val uncheckedWithdrawalIds = withdrawalIds.toMutableSet()

    private var hasCheckedGlobalPermissions = false

    fun expect(
        vararg organizations: OrganizationId,
        readOrganization: Boolean = false,
        updateOrganization: Boolean = false,
        deleteOrganization: Boolean = false,
        listOrganizationUsers: Boolean = false,
        readOrganizationUser: Boolean = false,
        readOrganizationSelf: Boolean = false,
        addOrganizationUser: Boolean = false,
        removeOrganizationUser: Boolean = false,
        removeOrganizationSelf: Boolean = false,
        createSpecies: Boolean = false,
        createFacility: Boolean = false,
        listFacilities: Boolean = false,
        createPlantingSite: Boolean = false,
        createReport: Boolean = false,
        listReports: Boolean = false,
    ) {
      organizations.forEach { organizationId ->
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
            readOrganizationUser,
            user.canReadOrganizationUser(organizationId, otherUserIds[organizationId]!!),
            "Can read user in organization $organizationId")
        assertEquals(
            readOrganizationSelf,
            user.canReadOrganizationUser(organizationId, userId),
            "Can read self in organization $organizationId")
        assertEquals(
            addOrganizationUser,
            user.canAddOrganizationUser(organizationId),
            "Can add organization $organizationId user")
        assertEquals(
            removeOrganizationUser,
            user.canRemoveOrganizationUser(organizationId, otherUserIds[organizationId]!!),
            "Can remove user from organization $organizationId")
        assertEquals(
            removeOrganizationSelf,
            user.canRemoveOrganizationUser(organizationId, userId),
            "Can remove self from organization $organizationId")
        assertEquals(
            createSpecies,
            user.canCreateSpecies(organizationId),
            "Can create species in organization $organizationId")
        assertEquals(
            createFacility,
            user.canCreateFacility(organizationId),
            "Can create facility in organization $organizationId")
        assertEquals(
            listFacilities,
            user.canListFacilities(organizationId),
            "Can list facilities in organization $organizationId")
        assertEquals(
            createPlantingSite,
            user.canCreatePlantingSite(organizationId),
            "Can create planting site in organization $organizationId")
        assertEquals(
            createReport,
            user.canCreateReport(organizationId),
            "Can create report in organization $organizationId")
        assertEquals(
            listReports,
            user.canListReports(organizationId),
            "Can list reports in organization $organizationId")

        uncheckedOrgs.remove(organizationId)
      }
    }

    fun expect(
        vararg facilities: FacilityId,
        createAccession: Boolean = false,
        createAutomation: Boolean = false,
        createBatch: Boolean = false,
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
            createBatch,
            user.canCreateBatch(facilityId),
            "Can create seedling batch at facility $facilityId")
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
        deleteAccession: Boolean = false,
        setWithdrawalUser: Boolean = false,
        uploadPhoto: Boolean = false,
    ) {
      accessions.forEach { accessionId ->
        assertEquals(
            readAccession, user.canReadAccession(accessionId), "Can read accession $accessionId")
        assertEquals(
            updateAccession,
            user.canUpdateAccession(accessionId),
            "Can update accession $accessionId")
        assertEquals(
            deleteAccession,
            user.canDeleteAccession(accessionId),
            "Can delete accession $accessionId")
        assertEquals(
            setWithdrawalUser,
            user.canSetWithdrawalUser(accessionId),
            "Can set withdrawal user for accession $accessionId")
        assertEquals(
            uploadPhoto,
            user.canUploadPhoto(accessionId),
            "Can upload photo for accession $accessionId")

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
        deleteSelf: Boolean = false,
        importGlobalSpeciesData: Boolean = false,
        manageInternalTags: Boolean = false,
        regenerateAllDeviceManagerTokens: Boolean = false,
        setTestClock: Boolean = false,
        updateAppVersions: Boolean = false,
        updateDeviceTemplates: Boolean = false,
    ) {
      assertEquals(createDeviceManager, user.canCreateDeviceManager(), "Can create device manager")
      assertEquals(deleteSelf, user.canDeleteSelf(), "Can delete self")
      assertEquals(
          importGlobalSpeciesData,
          user.canImportGlobalSpeciesData(),
          "Can import global species data")
      assertEquals(manageInternalTags, user.canManageInternalTags(), "Can manage internal tags")
      assertEquals(
          regenerateAllDeviceManagerTokens,
          user.canRegenerateAllDeviceManagerTokens(),
          "Can regenerate all device manager tokens")
      assertEquals(setTestClock, user.canSetTestClock(), "Can set test clock")
      assertEquals(updateAppVersions, user.canUpdateAppVersions(), "Can update app versions")
      assertEquals(
          updateDeviceTemplates, user.canUpdateDeviceTemplates(), "Can update device templates")

      hasCheckedGlobalPermissions = true
    }

    fun expect(
        vararg viabilityTestIds: ViabilityTestId,
        readViabilityTest: Boolean = false,
    ) {
      viabilityTestIds.forEach { viabilityTestId ->
        assertEquals(
            readViabilityTest,
            user.canReadViabilityTest(viabilityTestId),
            "Can read viability test $viabilityTestId")

        uncheckedViabilityTestIds.remove(viabilityTestId)
      }
    }

    fun expect(
        vararg batchIds: BatchId,
        deleteBatch: Boolean = false,
        readBatch: Boolean = false,
        updateBatch: Boolean = false,
    ) {
      batchIds.forEach { batchId ->
        assertEquals(deleteBatch, user.canDeleteBatch(batchId), "Can delete batch $batchId")
        assertEquals(readBatch, user.canReadBatch(batchId), "Can read batch $batchId")
        assertEquals(updateBatch, user.canUpdateBatch(batchId), "Can update batch $batchId")

        uncheckedBatches.remove(batchId)
      }
    }

    fun expect(
        vararg withdrawalIds: WithdrawalId,
        createWithdrawalPhoto: Boolean = false,
        readWithdrawal: Boolean = false,
    ) {
      withdrawalIds.forEach { withdrawalId ->
        assertEquals(
            createWithdrawalPhoto,
            user.canCreateWithdrawalPhoto(withdrawalId),
            "Can create photo for withdrawal $withdrawalId")
        assertEquals(
            readWithdrawal,
            user.canReadWithdrawal(withdrawalId),
            "Can read withdrawal $withdrawalId")

        uncheckedWithdrawalIds.remove(withdrawalId)
      }
    }

    fun expect(
        vararg plantingSiteIds: PlantingSiteId,
        createDelivery: Boolean = false,
        movePlantingSiteToAnyOrg: Boolean = false,
        readPlantingSite: Boolean = false,
        updatePlantingSite: Boolean = false,
    ) {
      plantingSiteIds.forEach { plantingSiteId ->
        assertEquals(
            createDelivery,
            user.canCreateDelivery(plantingSiteId),
            "Can create delivery at planting site $plantingSiteId")
        assertEquals(
            movePlantingSiteToAnyOrg,
            user.canMovePlantingSiteToAnyOrg(plantingSiteId),
            "Can move planting site $plantingSiteId")
        assertEquals(
            readPlantingSite,
            user.canReadPlantingSite(plantingSiteId),
            "Can read planting site $plantingSiteId")
        assertEquals(
            updatePlantingSite,
            user.canUpdatePlantingSite(plantingSiteId),
            "Can update planting site $plantingSiteId")

        uncheckedPlantingSites.remove(plantingSiteId)
      }
    }

    fun expect(
        vararg plantingZoneIds: PlantingZoneId,
        readPlantingZone: Boolean = false,
        updatePlantingZone: Boolean = false,
    ) {
      plantingZoneIds.forEach { plantingZoneId ->
        assertEquals(
            readPlantingZone,
            user.canReadPlantingZone(plantingZoneId),
            "Can read planting zone $plantingZoneId")
        assertEquals(
            updatePlantingZone,
            user.canUpdatePlantingZone(plantingZoneId),
            "Can update planting zone $plantingZoneId")

        uncheckedPlantingZones.remove(plantingZoneId)
      }
    }

    fun expect(
        vararg deliveryIds: DeliveryId,
        readDelivery: Boolean = false,
        updateDelivery: Boolean = false,
    ) {
      deliveryIds.forEach { deliveryId ->
        assertEquals(
            readDelivery, user.canReadDelivery(deliveryId), "Can read delivery $deliveryId")
        assertEquals(
            updateDelivery, user.canUpdateDelivery(deliveryId), "Can update delivery $deliveryId")

        uncheckedDeliveries.remove(deliveryId)
      }
    }

    fun expect(
        vararg plantingIds: PlantingId,
        readPlanting: Boolean = false,
    ) {
      plantingIds.forEach { plantingId ->
        assertEquals(
            readPlanting, user.canReadPlanting(plantingId), "Can read planting $plantingId")

        uncheckedPlantings.remove(plantingId)
      }
    }

    fun expect(
        vararg reportIds: ReportId,
        deleteReport: Boolean = false,
        readReport: Boolean = false,
        updateReport: Boolean = false,
    ) {
      reportIds.forEach { reportId ->
        assertEquals(deleteReport, user.canDeleteReport(reportId), "Can delete report $reportId")
        assertEquals(readReport, user.canReadReport(reportId), "Can read report $reportId")
        assertEquals(updateReport, user.canUpdateReport(reportId), "Can update report $reportId")

        uncheckedReports.remove(reportId)
      }
    }

    fun andNothingElse() {
      expect(*uncheckedAccessions.toTypedArray())
      expect(*uncheckedAutomations.toTypedArray())
      expect(*uncheckedBatches.toTypedArray())
      expect(*uncheckedDeliveries.toTypedArray())
      expect(*uncheckedDeviceManagers.toTypedArray())
      expect(*uncheckedDevices.toTypedArray())
      expect(*uncheckedFacilities.toTypedArray())
      expect(*uncheckedOrgs.toTypedArray())
      expect(*uncheckedPlantings.toTypedArray())
      expect(*uncheckedPlantingSites.toTypedArray())
      expect(*uncheckedPlantingZones.toTypedArray())
      expect(*uncheckedReports.toTypedArray())
      expect(*uncheckedSpecies.toTypedArray())
      expect(*uncheckedStorageLocationIds.toTypedArray())
      expect(*uncheckedViabilityTestIds.toTypedArray())
      expect(*uncheckedWithdrawalIds.toTypedArray())

      if (!hasCheckedGlobalPermissions) {
        expect()
      }
    }
  }
}
