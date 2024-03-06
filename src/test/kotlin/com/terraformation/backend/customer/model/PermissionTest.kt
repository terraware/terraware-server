package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.PermissionTest.PermissionsTracker
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
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
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.REPORTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.nursery.tables.references.WITHDRAWALS
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.seedbank.tables.references.VIABILITY_TESTS
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.tables.references.DRAFT_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.dummyKeycloakInfo
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired

/**
 * Tests the permission business logic. This includes both the database interactions and the
 * in-memory computations.
 *
 * Most of the tests are done against a canned set of organization data that allows testing various
 * permutations of objects:
 * ```
 * Organization 1 - Two of everything
 *   Facility 1000
 *   Facility 1001
 *   Project 1000
 *   Project 1001
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
  private val draftPlantingSiteIds = facilityIds.map { DraftPlantingSiteId(it.value) }
  private val plantingSiteIds = facilityIds.map { PlantingSiteId(it.value) }
  private val plantingSubzoneIds = facilityIds.map { PlantingSubzoneId(it.value) }
  private val plantingZoneIds = facilityIds.map { PlantingZoneId(it.value) }
  private val observationIds = plantingSiteIds.map { ObservationId(it.value) }
  private val projectIds = facilityIds.map { ProjectId(it.value) }

  private val accessionIds = facilityIds.map { AccessionId(it.value) }
  private val automationIds = facilityIds.map { AutomationId(it.value) }
  private val batchIds = facilityIds.map { BatchId(it.value) }
  private val deviceIds = facilityIds.map { DeviceId(it.value) }
  private val subLocationIds = facilityIds.map { SubLocationId(it.value) }
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

  // For now, participant permissions are global, not per-participant, so we only need one ID.
  private val participantId = ParticipantId(1)
  private val cohortId = CohortId(1)
  private val globalRoles = setOf(GlobalRole.SuperAdmin)

  private inline fun <reified T> List<T>.filterToArray(func: (T) -> Boolean): Array<T> =
      filter(func).toTypedArray()

  private inline fun <reified T> List<T>.filterStartsWith(prefix: String): Array<T> =
      filter { "$it".startsWith(prefix) }.toTypedArray()

  private inline fun <reified T> List<T>.forOrg1() = filterStartsWith("1")

  private inline fun <reified T> List<T>.forFacility1000() = filterStartsWith("1000")

  @BeforeEach
  fun setUp() {
    parentStore = ParentStore(dslContext)
    permissionStore = PermissionStore(dslContext)
    userStore =
        UserStore(
            clock,
            config,
            dslContext,
            mockk(),
            InMemoryKeycloakAdminClient(),
            dummyKeycloakInfo(),
            mockk(),
            parentStore,
            permissionStore,
            mockk(),
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

    subLocationIds.forEach { insertSubLocation(it, facilityId = it.value, createdBy = userId) }

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

    plantingSubzoneIds.forEach { plantingSubzoneId ->
      insertPlantingSubzone(
          createdBy = userId,
          id = plantingSubzoneId,
          plantingSiteId = PlantingSiteId(plantingSubzoneId.value),
          plantingZoneId = PlantingZoneId(plantingSubzoneId.value),
      )
    }

    draftPlantingSiteIds.forEach { draftPlantingSiteId ->
      val organizationId = draftPlantingSiteId.value / 1000
      insertDraftPlantingSite(
          createdBy = userId,
          id = draftPlantingSiteId,
          organizationId = organizationId,
      )
    }

    reportIds.forEach { reportId ->
      val organizationId = OrganizationId(reportId.value)
      insertReport(id = reportId, organizationId = organizationId)
    }

    observationIds.forEach { observationId ->
      val plantingSiteId = PlantingSiteId(observationId.value)
      insertObservation(id = observationId, plantingSiteId = plantingSiteId)
    }

    projectIds.forEach { projectId ->
      val organizationId = OrganizationId(projectId.value / 1000)
      insertProject(
          createdBy = userId,
          id = projectId,
          organizationId = organizationId,
      )
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
        createDraftPlantingSite = true,
        listReports = true,
        createProject = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
        createDevice = true,
        createSubLocation = true,
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
        *subLocationIds.forOrg1(),
        readSubLocation = true,
        updateSubLocation = true,
        deleteSubLocation = true,
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
        createObservation = true,
        readPlantingSite = true,
        scheduleObservation = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
        updatePlantingSubzone = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
        updatePlantingZone = true,
    )

    permissions.expect(
        *draftPlantingSiteIds.forOrg1(),
        deleteDraftPlantingSite = true,
        readDraftPlantingSite = true,
        updateDraftPlantingSite = true,
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
        *observationIds.forOrg1(),
        readObservation = true,
        replaceObservationPlot = true,
        rescheduleObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        deleteProject = true,
        readProject = true,
        updateProject = true,
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
        createDraftPlantingSite = true,
        listReports = true,
        createProject = true,
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

  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "Admin",
              "TerraformationContact",
          ])
  fun `admin equivalent role grants all permissions except deleting organization`(
      roleName: String
  ) {
    givenRole(org1Id, Role.valueOf(roleName))

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
        createDraftPlantingSite = true,
        listReports = true,
        createProject = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
        createDevice = true,
        createSubLocation = true,
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
        *subLocationIds.forOrg1(),
        readSubLocation = true,
        updateSubLocation = true,
        deleteSubLocation = true,
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
        createObservation = true,
        readPlantingSite = true,
        scheduleObservation = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
        updatePlantingSubzone = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
        updatePlantingZone = true,
    )

    permissions.expect(
        *draftPlantingSiteIds.forOrg1(),
        deleteDraftPlantingSite = true,
        readDraftPlantingSite = true,
        updateDraftPlantingSite = true,
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
        *observationIds.forOrg1(),
        readObservation = true,
        replaceObservationPlot = true,
        rescheduleObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        deleteProject = true,
        readProject = true,
        updateProject = true,
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
        *subLocationIds.forOrg1(),
        readSubLocation = true,
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
        createObservation = true,
        readPlantingSite = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
    )

    permissions.expect(
        *draftPlantingSiteIds.forOrg1(),
        readDraftPlantingSite = true,
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
        *observationIds.forOrg1(),
        readObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        readProject = true,
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
        *subLocationIds.forOrg1(),
        readSubLocation = true,
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
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
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
        *observationIds.forOrg1(),
        readObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        readProject = true,
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
        createDraftPlantingSite = true,
        createReport = true,
        listReports = true,
        createProject = true,
    )

    permissions.expect(
        *facilityIds.toTypedArray(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
        createDevice = true,
        createSubLocation = true,
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
        *subLocationIds.toTypedArray(),
        readSubLocation = true,
        updateSubLocation = true,
        deleteSubLocation = true,
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
        addAnyOrganizationUser = true,
        addCohortParticipant = true,
        addParticipantProject = true,
        createCohort = true,
        createDeviceManager = true,
        createParticipant = true,
        deleteCohort = true,
        deleteCohortParticipant = true,
        deleteParticipant = true,
        deleteParticipantProject = true,
        manageNotifications = true,
        readCohort = true,
        readGlobalRoles = true,
        readInternalTags = true,
        readParticipant = true,
        setTestClock = true,
        updateCohort = true,
        updateAppVersions = true,
        updateDeviceTemplates = true,
        updateGlobalRoles = true,
        updateParticipant = true,
        updateSpecificGlobalRoles = true,
    )

    permissions.expect(
        *plantingSiteIds.toTypedArray(),
        createDelivery = true,
        createObservation = true,
        deletePlantingSite = true,
        movePlantingSiteToAnyOrg = true,
        readPlantingSite = true,
        scheduleObservation = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *plantingSubzoneIds.toTypedArray(),
        readPlantingSubzone = true,
        updatePlantingSubzone = true,
    )

    permissions.expect(
        *plantingZoneIds.toTypedArray(),
        readPlantingZone = true,
        updatePlantingZone = true,
    )

    permissions.expect(
        *draftPlantingSiteIds.toTypedArray(),
        deleteDraftPlantingSite = true,
        readDraftPlantingSite = true,
        updateDraftPlantingSite = true,
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

    permissions.expect(
        *observationIds.toTypedArray(),
        manageObservation = true,
        readObservation = true,
        replaceObservationPlot = true,
        rescheduleObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        *projectIds.toTypedArray(),
        deleteProject = true,
        readProject = true,
        updateProject = true,
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
    insertUserGlobalRole(userId, GlobalRole.SuperAdmin)

    givenRole(org1Id, Role.Admin)

    val permissions = PermissionsTracker()

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        createObservation = true,
        deletePlantingSite = true,
        movePlantingSiteToAnyOrg = true,
        readPlantingSite = true,
        scheduleObservation = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *observationIds.forOrg1(),
        manageObservation = true,
        readObservation = true,
        replaceObservationPlot = true,
        rescheduleObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        deleteProject = true,
        readProject = true,
        updateProject = true,
        updateProjectDocumentSettings = true,
    )

    // Not an admin of this org but can still update document settings.
    permissions.expect(
        ProjectId(3000),
        updateProjectDocumentSettings = true,
    )

    permissions.expect(
        addAnyOrganizationUser = true,
        addCohortParticipant = true,
        addParticipantProject = true,
        createCohort = true,
        createDeviceManager = true,
        createParticipant = true,
        deleteCohort = true,
        deleteCohortParticipant = true,
        deleteParticipant = true,
        deleteParticipantProject = true,
        deleteSelf = true,
        importGlobalSpeciesData = true,
        manageDeliverables = true,
        manageInternalTags = true,
        manageModules = true,
        readInternalTags = true,
        readGlobalRoles = true,
        readCohort = true,
        readParticipant = true,
        regenerateAllDeviceManagerTokens = true,
        setTestClock = true,
        updateAppVersions = true,
        updateCohort = true,
        updateDeviceTemplates = true,
        updateGlobalRoles = true,
        updateParticipant = true,
        updateSpecificGlobalRoles = true,
    )

    // Super admin can apply all global roles to a user
    assertTrue(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.AcceleratorAdmin)))
    assertTrue(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.ReadOnly)))
    assertTrue(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.SuperAdmin)))
    assertTrue(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.TFExpert)))
  }

  @Test
  fun `accelerator admin user has correct privileges`() {
    insertUserGlobalRole(userId, GlobalRole.AcceleratorAdmin)

    givenRole(org1Id, Role.Admin)

    val permissions = PermissionsTracker()

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        createObservation = true,
        deletePlantingSite = false,
        movePlantingSiteToAnyOrg = false,
        readPlantingSite = true,
        scheduleObservation = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *observationIds.forOrg1(),
        manageObservation = false,
        readObservation = true,
        replaceObservationPlot = true,
        rescheduleObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        deleteProject = true,
        readProject = true,
        updateProject = true,
        updateProjectDocumentSettings = true,
    )

    // Not an admin of this org but can still update document settings.
    permissions.expect(
        ProjectId(3000),
        updateProjectDocumentSettings = true,
    )

    permissions.expect(
        addAnyOrganizationUser = false,
        addCohortParticipant = true,
        addParticipantProject = true,
        createCohort = true,
        createDeviceManager = false,
        createParticipant = true,
        deleteCohort = true,
        deleteCohortParticipant = true,
        deleteParticipant = true,
        deleteParticipantProject = true,
        deleteSelf = true,
        importGlobalSpeciesData = false,
        manageDeliverables = true,
        manageInternalTags = false,
        manageModules = true,
        readInternalTags = true,
        readCohort = true,
        readGlobalRoles = true,
        readParticipant = true,
        regenerateAllDeviceManagerTokens = false,
        setTestClock = false,
        updateAppVersions = false,
        updateCohort = true,
        updateDeviceTemplates = false,
        updateGlobalRoles = false,
        updateParticipant = true,
    )

    // Accelerator admin can apply all global roles to a user except super admin
    assertTrue(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.AcceleratorAdmin)))
    assertTrue(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.ReadOnly)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.SuperAdmin)))
    assertTrue(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.TFExpert)))
  }

  @Test
  fun `tf expert user has correct privileges`() {
    insertUserGlobalRole(userId, GlobalRole.TFExpert)

    givenRole(org1Id, Role.Admin)

    val permissions = PermissionsTracker()

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        createObservation = true,
        deletePlantingSite = false,
        movePlantingSiteToAnyOrg = false,
        readPlantingSite = true,
        scheduleObservation = true,
        updatePlantingSite = true,
    )

    permissions.expect(
        *observationIds.forOrg1(),
        manageObservation = false,
        readObservation = true,
        replaceObservationPlot = true,
        rescheduleObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        addAnyOrganizationUser = false,
        addCohortParticipant = false,
        addParticipantProject = false,
        createCohort = false,
        createDeviceManager = false,
        createParticipant = false,
        deleteCohort = false,
        deleteCohortParticipant = false,
        deleteParticipant = false,
        deleteParticipantProject = false,
        deleteSelf = true,
        importGlobalSpeciesData = false,
        manageInternalTags = false,
        readInternalTags = false,
        readCohort = true,
        readGlobalRoles = false,
        readParticipant = true,
        regenerateAllDeviceManagerTokens = false,
        setTestClock = false,
        updateAppVersions = false,
        updateCohort = false,
        updateDeviceTemplates = false,
        updateGlobalRoles = false,
        updateParticipant = false,
    )

    // TF Expert can't apply any global roles to a user
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.AcceleratorAdmin)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.ReadOnly)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.SuperAdmin)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.TFExpert)))
  }

  @Test
  fun `read only user has correct privileges`() {
    insertUserGlobalRole(userId, GlobalRole.ReadOnly)

    val permissions = PermissionsTracker()

    givenRole(org1Id, Role.Contributor)

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = false,
        createObservation = false,
        deletePlantingSite = false,
        movePlantingSiteToAnyOrg = false,
        readPlantingSite = true,
        scheduleObservation = false,
        updatePlantingSite = false,
    )

    permissions.expect(
        *observationIds.forOrg1(),
        manageObservation = false,
        readObservation = true,
        replaceObservationPlot = false,
        rescheduleObservation = false,
        updateObservation = true,
    )

    permissions.expect(
        addAnyOrganizationUser = false,
        addCohortParticipant = false,
        addParticipantProject = false,
        createCohort = false,
        createDeviceManager = false,
        createParticipant = false,
        deleteCohort = false,
        deleteCohortParticipant = false,
        deleteParticipant = false,
        deleteParticipantProject = false,
        deleteSelf = true,
        importGlobalSpeciesData = false,
        manageInternalTags = false,
        readInternalTags = false,
        readCohort = true,
        readGlobalRoles = false,
        readParticipant = true,
        regenerateAllDeviceManagerTokens = false,
        setTestClock = false,
        updateAppVersions = false,
        updateCohort = false,
        updateDeviceTemplates = false,
        updateGlobalRoles = false,
        updateParticipant = false,
    )

    // Read Only can't apply any global roles to a user
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.AcceleratorAdmin)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.ReadOnly)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.SuperAdmin)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.TFExpert)))
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

  @Test
  fun `admin user can read but not write draft planting sites of other users in same org`() {
    val otherUserDraftSiteId = DraftPlantingSiteId(1002)
    insertDraftPlantingSite(
        id = otherUserDraftSiteId, createdBy = sameOrgUserId, organizationId = org1Id)

    givenRole(org1Id, Role.Admin)

    assertTrue(user.canReadDraftPlantingSite(otherUserDraftSiteId), "Can read draft site")
    assertFalse(user.canDeleteDraftPlantingSite(otherUserDraftSiteId), "Can delete draft site")
    assertFalse(user.canUpdateDraftPlantingSite(otherUserDraftSiteId), "Can update draft site")
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
    dslContext.deleteFrom(SUB_LOCATIONS).execute()
    dslContext.deleteFrom(TIMESERIES).execute()
    dslContext.deleteFrom(DEVICE_MANAGERS).execute()
    dslContext.deleteFrom(AUTOMATIONS).execute()
    dslContext.deleteFrom(DEVICES).execute()
    dslContext.deleteFrom(ACCESSIONS).execute()
    dslContext.deleteFrom(FACILITIES).execute()
    dslContext.deleteFrom(OBSERVATIONS).execute()
    dslContext.deleteFrom(PLANTING_ZONES).execute()
    dslContext.deleteFrom(PLANTING_SITES).execute()
    dslContext.deleteFrom(DRAFT_PLANTING_SITES).execute()
    dslContext.deleteFrom(SPECIES).execute()
    dslContext.deleteFrom(PROJECTS).execute()
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
    private val uncheckedDraftPlantingSites = draftPlantingSiteIds.toMutableSet()
    private val uncheckedObservations = observationIds.toMutableSet()
    private val uncheckedPlantings = plantingIds.toMutableSet()
    private val uncheckedPlantingSites = plantingSiteIds.toMutableSet()
    private val uncheckedPlantingSubzones = plantingSubzoneIds.toMutableSet()
    private val uncheckedPlantingZones = plantingZoneIds.toMutableSet()
    private val uncheckedProjects = projectIds.toMutableSet()
    private val uncheckedReports = reportIds.toMutableSet()
    private val uncheckedSpecies = speciesIds.toMutableSet()
    private val uncheckedSubLocations = subLocationIds.toMutableSet()
    private val uncheckedViabilityTests = viabilityTestIds.toMutableSet()
    private val uncheckedWithdrawals = withdrawalIds.toMutableSet()

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
        createDraftPlantingSite: Boolean = false,
        createReport: Boolean = false,
        listReports: Boolean = false,
        createProject: Boolean = false,
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
            createDraftPlantingSite,
            user.canCreateDraftPlantingSite(organizationId),
            "Can create draft planting site in organization $organizationId")
        assertEquals(
            createReport,
            user.canCreateReport(organizationId),
            "Can create report in organization $organizationId")
        assertEquals(
            listReports,
            user.canListReports(organizationId),
            "Can list reports in organization $organizationId")
        assertEquals(
            createProject,
            user.canCreateProject(organizationId),
            "Can create project in organization $organizationId")

        uncheckedOrgs.remove(organizationId)
      }
    }

    fun expect(
        vararg facilities: FacilityId,
        createAccession: Boolean = false,
        createAutomation: Boolean = false,
        createBatch: Boolean = false,
        createDevice: Boolean = false,
        createSubLocation: Boolean = false,
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
            createSubLocation,
            user.canCreateSubLocation(facilityId),
            "Can create sub-location at facility $facilityId")
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
        vararg subLocationIds: SubLocationId,
        readSubLocation: Boolean = false,
        updateSubLocation: Boolean = false,
        deleteSubLocation: Boolean = false,
    ) {
      subLocationIds.forEach { subLocationId ->
        assertEquals(
            readSubLocation,
            user.canReadSubLocation(subLocationId),
            "Can read sub-location $subLocationId")
        assertEquals(
            updateSubLocation,
            user.canUpdateSubLocation(subLocationId),
            "Can update sub-location $subLocationId")
        assertEquals(
            deleteSubLocation,
            user.canDeleteSubLocation(subLocationId),
            "Can delete sub-location $subLocationId")

        uncheckedSubLocations.remove(subLocationId)
      }
    }

    /** Checks for globally-scoped permissions. */
    fun expect(
        addAnyOrganizationUser: Boolean = false,
        addCohortParticipant: Boolean = false,
        addParticipantProject: Boolean = false,
        createCohort: Boolean = false,
        createDeviceManager: Boolean = false,
        createParticipant: Boolean = false,
        deleteCohort: Boolean = false,
        deleteCohortParticipant: Boolean = false,
        deleteParticipant: Boolean = false,
        deleteParticipantProject: Boolean = false,
        deleteSelf: Boolean = false,
        importGlobalSpeciesData: Boolean = false,
        manageDeliverables: Boolean = false,
        manageInternalTags: Boolean = false,
        manageModules: Boolean = false,
        manageNotifications: Boolean = false,
        readCohort: Boolean = false,
        readGlobalRoles: Boolean = false,
        readInternalTags: Boolean = false,
        readParticipant: Boolean = false,
        regenerateAllDeviceManagerTokens: Boolean = false,
        setTestClock: Boolean = false,
        updateAppVersions: Boolean = false,
        updateCohort: Boolean = false,
        updateDeviceTemplates: Boolean = false,
        updateGlobalRoles: Boolean = false,
        updateParticipant: Boolean = false,
        updateSpecificGlobalRoles: Boolean = false,
    ) {
      assertEquals(
          addAnyOrganizationUser, user.canAddAnyOrganizationUser(), "Can add any organization user")
      assertEquals(
          addCohortParticipant,
          user.canAddCohortParticipant(cohortId, participantId),
          "Can add cohort participant")
      assertEquals(
          addParticipantProject,
          user.canAddParticipantProject(participantId, projectIds[0]),
          "Can add participant project")
      assertEquals(createCohort, user.canCreateCohort(), "Can create cohort")
      assertEquals(createDeviceManager, user.canCreateDeviceManager(), "Can create device manager")
      assertEquals(createParticipant, user.canCreateParticipant(), "Can create participant")
      assertEquals(deleteCohort, user.canDeleteCohort(cohortId), "Can delete cohort")
      assertEquals(
          deleteCohortParticipant,
          user.canDeleteCohortParticipant(cohortId, participantId),
          "Can delete cohort participant")
      assertEquals(
          deleteParticipant, user.canDeleteParticipant(participantId), "Can delete participant")
      assertEquals(
          deleteParticipantProject,
          user.canDeleteParticipantProject(participantId, projectIds[0]),
          "Can delete participant project")
      assertEquals(deleteSelf, user.canDeleteSelf(), "Can delete self")
      assertEquals(
          importGlobalSpeciesData,
          user.canImportGlobalSpeciesData(),
          "Can import global species data")
      assertEquals(manageDeliverables, user.canManageDeliverables(), "Can manage deliverables")
      assertEquals(manageInternalTags, user.canManageInternalTags(), "Can manage internal tags")
      assertEquals(manageModules, user.canManageModules(), "Can manage modules")
      assertEquals(manageNotifications, user.canManageNotifications(), "Can manage notifications")
      assertEquals(readCohort, user.canReadCohort(cohortId), "Can read cohort")
      assertEquals(readGlobalRoles, user.canReadGlobalRoles(), "Can read global roles")
      assertEquals(readInternalTags, user.canReadInternalTags(), "Can read internal tags")
      assertEquals(readParticipant, user.canReadParticipant(participantId), "Can read participant")
      assertEquals(
          regenerateAllDeviceManagerTokens,
          user.canRegenerateAllDeviceManagerTokens(),
          "Can regenerate all device manager tokens")
      assertEquals(setTestClock, user.canSetTestClock(), "Can set test clock")
      assertEquals(updateAppVersions, user.canUpdateAppVersions(), "Can update app versions")
      assertEquals(updateCohort, user.canUpdateCohort(cohortId), "Can update cohort")
      assertEquals(
          updateDeviceTemplates, user.canUpdateDeviceTemplates(), "Can update device templates")
      assertEquals(updateGlobalRoles, user.canUpdateGlobalRoles(), "Can update global roles")
      assertEquals(
          updateParticipant, user.canUpdateParticipant(participantId), "Can update participant")
      assertEquals(
          updateSpecificGlobalRoles,
          user.canUpdateSpecificGlobalRoles(globalRoles),
          "Can update specific global roles")

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

        uncheckedViabilityTests.remove(viabilityTestId)
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

        uncheckedWithdrawals.remove(withdrawalId)
      }
    }

    fun expect(
        vararg plantingSiteIds: PlantingSiteId,
        createDelivery: Boolean = false,
        createObservation: Boolean = false,
        deletePlantingSite: Boolean = false,
        movePlantingSiteToAnyOrg: Boolean = false,
        readPlantingSite: Boolean = false,
        scheduleObservation: Boolean = false,
        updatePlantingSite: Boolean = false,
    ) {
      plantingSiteIds.forEach { plantingSiteId ->
        assertEquals(
            createDelivery,
            user.canCreateDelivery(plantingSiteId),
            "Can create delivery at planting site $plantingSiteId")
        assertEquals(
            createObservation,
            user.canCreateObservation(plantingSiteId),
            "Can create observation of planting site $plantingSiteId")
        assertEquals(
            deletePlantingSite,
            user.canDeletePlantingSite(plantingSiteId),
            "Can delete planting site $plantingSiteId")
        assertEquals(
            movePlantingSiteToAnyOrg,
            user.canMovePlantingSiteToAnyOrg(plantingSiteId),
            "Can move planting site $plantingSiteId")
        assertEquals(
            readPlantingSite,
            user.canReadPlantingSite(plantingSiteId),
            "Can read planting site $plantingSiteId")
        assertEquals(
            scheduleObservation,
            user.canScheduleObservation(plantingSiteId),
            "Can schedule observation $plantingSiteId")
        assertEquals(
            updatePlantingSite,
            user.canUpdatePlantingSite(plantingSiteId),
            "Can update planting site $plantingSiteId")

        uncheckedPlantingSites.remove(plantingSiteId)
      }
    }

    fun expect(
        vararg plantingSubzoneIds: PlantingSubzoneId,
        readPlantingSubzone: Boolean = false,
        updatePlantingSubzone: Boolean = false,
    ) {
      plantingSubzoneIds.forEach { plantingSubzoneId ->
        assertEquals(
            readPlantingSubzone,
            user.canReadPlantingSubzone(plantingSubzoneId),
            "Can read planting subzone $plantingSubzoneId")
        assertEquals(
            updatePlantingSubzone,
            user.canUpdatePlantingSubzone(plantingSubzoneId),
            "Can update planting subzone $plantingSubzoneId")

        uncheckedPlantingSubzones.remove(plantingSubzoneId)
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
        vararg draftPlantingSiteIds: DraftPlantingSiteId,
        deleteDraftPlantingSite: Boolean = false,
        readDraftPlantingSite: Boolean = false,
        updateDraftPlantingSite: Boolean = false,
    ) {
      draftPlantingSiteIds.forEach { draftPlantingSiteId ->
        assertEquals(
            deleteDraftPlantingSite,
            user.canDeleteDraftPlantingSite(draftPlantingSiteId),
            "Can delete draft planting site $draftPlantingSiteId")
        assertEquals(
            readDraftPlantingSite,
            user.canReadDraftPlantingSite(draftPlantingSiteId),
            "Can read draft planting site $draftPlantingSiteId")
        assertEquals(
            updateDraftPlantingSite,
            user.canUpdateDraftPlantingSite(draftPlantingSiteId),
            "Can update draft planting site $draftPlantingSiteId")

        uncheckedDraftPlantingSites.remove(draftPlantingSiteId)
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

    fun expect(
        vararg observationIds: ObservationId,
        manageObservation: Boolean = false,
        readObservation: Boolean = false,
        replaceObservationPlot: Boolean = false,
        rescheduleObservation: Boolean = false,
        updateObservation: Boolean = false,
    ) {
      observationIds.forEach { observationId ->
        assertEquals(
            manageObservation,
            user.canManageObservation(observationId),
            "Can manage observation $observationId")
        assertEquals(
            readObservation,
            user.canReadObservation(observationId),
            "Can read observation $observationId")
        assertEquals(
            replaceObservationPlot,
            user.canReplaceObservationPlot(observationId),
            "Can replace plot in observation $observationId")
        assertEquals(
            rescheduleObservation,
            user.canRescheduleObservation(observationId),
            "Can reschedule observation $observationId")
        assertEquals(
            updateObservation,
            user.canUpdateObservation(observationId),
            "Can update observation $observationId")

        uncheckedObservations.remove(observationId)
      }
    }

    fun expect(
        vararg projectIds: ProjectId,
        deleteProject: Boolean = false,
        readProject: Boolean = false,
        updateProject: Boolean = false,
        updateProjectDocumentSettings: Boolean = false,
    ) {
      projectIds.forEach { projectId ->
        assertEquals(
            deleteProject, user.canDeleteProject(projectId), "Can delete project $projectId")
        assertEquals(readProject, user.canReadProject(projectId), "Can read project $projectId")
        assertEquals(
            updateProject, user.canUpdateProject(projectId), "Can update project $projectId")
        assertEquals(
            updateProjectDocumentSettings,
            user.canUpdateProjectDocumentSettings(projectId),
            "Can update project $projectId document settings")

        uncheckedProjects.remove(projectId)
      }
    }

    fun andNothingElse() {
      expect(*uncheckedAccessions.toTypedArray())
      expect(*uncheckedAutomations.toTypedArray())
      expect(*uncheckedBatches.toTypedArray())
      expect(*uncheckedDeliveries.toTypedArray())
      expect(*uncheckedDeviceManagers.toTypedArray())
      expect(*uncheckedDevices.toTypedArray())
      expect(*uncheckedDraftPlantingSites.toTypedArray())
      expect(*uncheckedFacilities.toTypedArray())
      expect(*uncheckedObservations.toTypedArray())
      expect(*uncheckedOrgs.toTypedArray())
      expect(*uncheckedPlantings.toTypedArray())
      expect(*uncheckedPlantingSites.toTypedArray())
      expect(*uncheckedPlantingSubzones.toTypedArray())
      expect(*uncheckedPlantingZones.toTypedArray())
      expect(*uncheckedProjects.toTypedArray())
      expect(*uncheckedReports.toTypedArray())
      expect(*uncheckedSpecies.toTypedArray())
      expect(*uncheckedSubLocations.toTypedArray())
      expect(*uncheckedViabilityTests.toTypedArray())
      expect(*uncheckedWithdrawals.toTypedArray())

      if (!hasCheckedGlobalPermissions) {
        expect()
      }
    }
  }
}
