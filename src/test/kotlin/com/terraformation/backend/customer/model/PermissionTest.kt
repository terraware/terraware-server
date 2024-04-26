package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.PermissionTest.PermissionsTracker
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
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
import com.terraformation.backend.db.tracking.MonitoringPlotId
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
 * Organization 4 - Has the Accelerator internal tag
 *   Project 4000
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
  private val organizationIds =
      nonEmptyOrganizationIds + listOf(OrganizationId(2), OrganizationId(4))
  private val org1Id = OrganizationId(1)

  // Org 2 is empty (no reports or species)
  private val reportIds = nonEmptyOrganizationIds.map { ReportId(it.value) }
  private val speciesIds = nonEmptyOrganizationIds.map { SpeciesId(it.value) }

  private val facilityIds = listOf(1000, 1001, 3000).map { FacilityId(it.toLong()) }
  private val draftPlantingSiteIds = facilityIds.map { DraftPlantingSiteId(it.value) }
  private val monitoringPlotIds = facilityIds.map { MonitoringPlotId(it.value) }
  private val plantingSiteIds = facilityIds.map { PlantingSiteId(it.value) }
  private val plantingSubzoneIds = facilityIds.map { PlantingSubzoneId(it.value) }
  private val plantingZoneIds = facilityIds.map { PlantingZoneId(it.value) }
  private val observationIds = plantingSiteIds.map { ObservationId(it.value) }

  private val projectIds = listOf(1000, 1001, 3000, 4000).map { ProjectId(it.toLong()) }
  private val moduleEventIds = listOf(1000, 1001, 3000, 4000).map { EventId(it.toLong()) }
  private val submissionDocumentIds = projectIds.map { SubmissionDocumentId(it.value) }

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
          OrganizationId(3) to UserId(9876),
          OrganizationId(4) to UserId(6543))

  private val uploadId = UploadId(1)

  private val participantIds = listOf(1, 3, 4).map { ParticipantId(it.toLong()) }
  private val cohortIds = listOf(1, 3, 4).map { CohortId(it.toLong()) }
  private val globalRoles = setOf(GlobalRole.SuperAdmin)

  private val moduleIds = listOf(1000, 1001, 3000, 4000).map { ModuleId(it.toLong()) }
  private val deliverableIds = listOf(DeliverableId(1000))
  private val submissionIds = projectIds.map { SubmissionId(it.value) }

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

    insertOrganizationInternalTag(OrganizationId(4), InternalTagIds.Accelerator, createdBy = userId)

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

    monitoringPlotIds.forEach { monitoringPlotId ->
      insertMonitoringPlot(
          createdBy = userId,
          id = monitoringPlotId,
          plantingSubzoneId = PlantingSubzoneId(monitoringPlotId.value),
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

    cohortIds.forEach { cohortId -> insertCohort(createdBy = userId, id = cohortId) }

    participantIds.forEach { participantId ->
      val cohortId = CohortId(participantId.value)
      insertParticipant(createdBy = userId, id = participantId, cohortId = cohortId)
    }

    projectIds.forEach { projectId ->
      val organizationId = OrganizationId(projectId.value / 1000)
      val participantId = ParticipantId(projectId.value / 1000)
      insertProject(
          createdBy = userId,
          id = projectId,
          organizationId = organizationId,
          participantId = participantId,
      )
    }

    moduleIds.forEach { moduleId ->
      val cohortId = CohortId(moduleId.value / 1000)
      insertModule(
          createdBy = userId,
          id = moduleId,
      )

      insertCohortModule(cohortId, moduleId)
    }

    deliverableIds.forEach { deliverableId ->
      val moduleId = ModuleId(deliverableId.value)
      insertDeliverable(
          createdBy = userId,
          id = deliverableId,
          moduleId = moduleId,
      )

      submissionIds.forEach { submissionId ->
        insertSubmission(
            createdBy = userId,
            deliverableId = deliverableId,
            id = submissionId,
            projectId = ProjectId(submissionId.value),
        )
      }
    }

    moduleEventIds.forEach { eventId ->
      val moduleId = ModuleId(eventId.value)
      insertEvent(
          createdBy = userId,
          id = eventId,
          moduleId = moduleId,
      )
      insertEventProject(eventId, ProjectId(eventId.value))
    }
  }

  @Test
  fun `owner role grants all permissions in organization projects, sites, and facilities`() {
    givenRole(org1Id, Role.Owner)
    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        addOrganizationUser = true,
        createDraftPlantingSite = true,
        createFacility = true,
        createPlantingSite = true,
        createProject = true,
        createSpecies = true,
        deleteOrganization = true,
        listFacilities = true,
        listOrganizationUsers = true,
        listReports = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        updateOrganization = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
        createDevice = true,
        createSubLocation = true,
        listAutomations = true,
        sendAlert = true,
        updateFacility = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        deleteAccession = true,
        readAccession = true,
        setWithdrawalUser = true,
        updateAccession = true,
        uploadPhoto = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        deleteAutomation = true,
        readAutomation = true,
        triggerAutomation = true,
        updateAutomation = true,
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
        readDevice = true,
        readTimeseries = true,
        updateDevice = true,
        updateTimeseries = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        deleteSpecies = true,
        readSpecies = true,
        updateSpecies = true,
    )

    permissions.expect(
        *subLocationIds.forOrg1(),
        deleteSubLocation = true,
        readSubLocation = true,
        updateSubLocation = true,
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
        *monitoringPlotIds.forOrg1(),
        readMonitoringPlot = true,
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
        *moduleEventIds.forOrg1(),
        readModuleEvent = true,
    )

    permissions.expect(
        *moduleIds.forOrg1(),
        readModule = true,
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
        createSubmission = true,
        deleteProject = true,
        readProject = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        updateProject = true,
    )

    permissions.expect(
        *submissionIds.forOrg1(),
        readSubmission = true,
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
        addOrganizationUser = true,
        createDraftPlantingSite = true,
        createFacility = true,
        createPlantingSite = true,
        createProject = true,
        createSpecies = true,
        deleteOrganization = true,
        listFacilities = true,
        listOrganizationUsers = true,
        listReports = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        updateOrganization = true,
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
        addOrganizationUser = true,
        createDraftPlantingSite = true,
        createFacility = true,
        createPlantingSite = true,
        createProject = true,
        createSpecies = true,
        listFacilities = true,
        listOrganizationUsers = true,
        listReports = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        updateOrganization = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
        createDevice = true,
        createSubLocation = true,
        listAutomations = true,
        sendAlert = true,
        updateFacility = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        deleteAccession = true,
        readAccession = true,
        setWithdrawalUser = true,
        updateAccession = true,
        uploadPhoto = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        deleteAutomation = true,
        readAutomation = true,
        triggerAutomation = true,
        updateAutomation = true,
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
        readDevice = true,
        readTimeseries = true,
        updateDevice = true,
        updateTimeseries = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        deleteSpecies = true,
        readSpecies = true,
        updateSpecies = true,
    )

    permissions.expect(
        *subLocationIds.forOrg1(),
        deleteSubLocation = true,
        readSubLocation = true,
        updateSubLocation = true,
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

    permissions.expect(*moduleEventIds.forOrg1(), readModuleEvent = true)

    permissions.expect(*moduleIds.forOrg1(), readModule = true)

    permissions.expect(
        *monitoringPlotIds.forOrg1(),
        readMonitoringPlot = true,
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
        createSubmission = true,
        deleteProject = true,
        readProject = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        updateProject = true,
    )

    permissions.expect(
        *submissionIds.forOrg1(),
        readSubmission = true,
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
        createSpecies = true,
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createBatch = true,
        listAutomations = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        deleteAccession = true,
        readAccession = true,
        setWithdrawalUser = true,
        updateAccession = true,
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
        readDevice = true,
        readTimeseries = true,
    )

    permissions.expect(
        *speciesIds.forOrg1(),
        deleteSpecies = true,
        readSpecies = true,
        updateSpecies = true,
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
        *monitoringPlotIds.forOrg1(),
        readMonitoringPlot = true,
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
        *moduleEventIds.forOrg1(),
        readModuleEvent = true,
    )

    permissions.expect(
        *moduleIds.forOrg1(),
        readModule = true,
    )

    permissions.expect(
        *observationIds.forOrg1(),
        readObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        createSubmission = true,
        readProject = true,
        readProjectDeliverables = true,
        readProjectModules = true,
    )

    permissions.expect(
        *submissionIds.forOrg1(),
        readSubmission = true,
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
        listFacilities = true,
        readOrganization = true,
        readOrganizationSelf = true,
        removeOrganizationSelf = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createBatch = true,
        listAutomations = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        deleteAccession = false,
        readAccession = true,
        updateAccession = false,
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
        readDevice = true,
        readTimeseries = true,
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
        *moduleEventIds.forOrg1(),
        readModuleEvent = true,
    )

    permissions.expect(
        *moduleIds.forOrg1(),
        readModule = true,
    )

    permissions.expect(
        *monitoringPlotIds.forOrg1(),
        readMonitoringPlot = true,
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
        readProjectModules = true,
    )

    permissions.expect(
        *submissionIds.forOrg1(),
        readSubmission = true,
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
        listFacilities = true,
        readOrganization = true,
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
        deleteAutomation = true,
        readAutomation = true,
        triggerAutomation = true,
        updateAutomation = true,
    )

    permissions.expect(
        deviceManagerId,
        readDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forFacility1000(),
        createTimeseries = true,
        readDevice = true,
        readTimeseries = true,
        updateDevice = true,
        updateTimeseries = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `system user can perform most operations`() {
    usersDao.update(usersDao.fetchOneById(userId)!!.copy(userTypeId = UserType.System))

    val permissions = PermissionsTracker()

    permissions.expect(
        *organizationIds.toTypedArray(),
        addOrganizationUser = true,
        createDraftPlantingSite = true,
        createFacility = true,
        createPlantingSite = true,
        createProject = true,
        createReport = true,
        createSpecies = true,
        deleteOrganization = true,
        listFacilities = true,
        listOrganizationUsers = true,
        listReports = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        updateOrganization = true,
    )

    permissions.expect(
        *facilityIds.toTypedArray(),
        createAccession = true,
        createAutomation = true,
        createBatch = true,
        createDevice = true,
        createSubLocation = true,
        listAutomations = true,
        sendAlert = true,
        updateFacility = true,
    )

    permissions.expect(
        *accessionIds.toTypedArray(),
        deleteAccession = true,
        readAccession = true,
        setWithdrawalUser = true,
        updateAccession = true,
        uploadPhoto = true,
    )

    permissions.expect(
        *automationIds.toTypedArray(),
        deleteAutomation = true,
        readAutomation = true,
        triggerAutomation = true,
        updateAutomation = true,
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
        readDevice = true,
        readTimeseries = true,
        updateDevice = true,
        updateTimeseries = true,
    )

    permissions.expect(
        *speciesIds.toTypedArray(),
        deleteSpecies = true,
        readSpecies = true,
        updateSpecies = true,
    )

    permissions.expect(
        *subLocationIds.toTypedArray(),
        deleteSubLocation = true,
        readSubLocation = true,
        updateSubLocation = true,
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
        createCohortModule = true,
        createDeviceManager = true,
        createParticipant = true,
        deleteCohort = true,
        deleteCohortParticipant = true,
        deleteParticipant = true,
        deleteParticipantProject = true,
        deleteSupportIssue = true,
        manageModuleEventStatuses = true,
        manageNotifications = true,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohorts = true,
        readGlobalRoles = true,
        readModuleEventParticipants = true,
        readInternalTags = true,
        readParticipant = true,
        setTestClock = true,
        updateAppVersions = true,
        updateCohort = true,
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
        *monitoringPlotIds.toTypedArray(),
        readMonitoringPlot = true,
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
        *moduleEventIds.toTypedArray(),
        readModuleEvent = true,
    )

    permissions.expect(
        *moduleIds.toTypedArray(),
        readModule = true,
        readModuleDetails = true,
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
        createSubmission = true,
        deleteProject = true,
        readDefaultVoters = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        updateDefaultVoters = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectScores = true,
        updateProjectVotes = true,
    )

    permissions.expect(
        *submissionIds.toTypedArray(),
        readSubmission = true,
    )

    permissions.expect(
        *submissionDocumentIds.toTypedArray(),
        readSubmissionDocument = true,
    )

    permissions.expect(
        *otherUserIds.values.toTypedArray(),
        readUser = true,
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
        *organizationIds.forOrg1(),
        addOrganizationUser = true,
        createDraftPlantingSite = true,
        createFacility = true,
        createPlantingSite = true,
        createProject = true,
        createReport = true,
        createSpecies = true,
        listFacilities = true,
        listOrganizationUsers = true,
        listReports = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        updateOrganization = true,
    )

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
        createSubmission = true,
        deleteProject = true,
        readDefaultVoters = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        updateDefaultVoters = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectDocumentSettings = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    // Can read and perform certain operations on all orgs even if not a member.
    permissions.expect(
        *organizationIds.filterNot { it == org1Id }.toTypedArray(),
        addOrganizationUser = true,
        createReport = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
    )

    // Can access accelerator-related functions on all ogs.
    permissions.expect(
        ProjectId(3000),
        ProjectId(4000),
        createSubmission = true,
        readDefaultVoters = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        updateDefaultVoters = true,
        updateProjectAcceleratorDetails = true,
        updateProjectDocumentSettings = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    permissions.expect(
        *moduleEventIds.toTypedArray(),
        readModuleEvent = true,
    )

    permissions.expect(
        *moduleIds.toTypedArray(),
        readModule = true,
        readModuleDetails = true,
    )

    // Can read all submissions even those outside of this org
    permissions.expect(
        *submissionIds.toTypedArray(),
        readSubmission = true,
    )

    permissions.expect(
        *submissionDocumentIds.toTypedArray(),
        readSubmissionDocument = true,
    )

    permissions.expect(
        *otherUserIds.values.toTypedArray(),
        readUser = true,
    )

    permissions.expect(
        addAnyOrganizationUser = true,
        addCohortParticipant = true,
        addParticipantProject = true,
        createCohort = true,
        createCohortModule = true,
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
        manageModuleEvents = true,
        manageModules = true,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohorts = true,
        readGlobalRoles = true,
        readInternalTags = true,
        readModuleEventParticipants = true,
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
        org1Id,
        addOrganizationUser = true,
        createDraftPlantingSite = true,
        createFacility = true,
        createPlantingSite = true,
        createProject = true,
        createSpecies = true,
        listFacilities = true,
        listOrganizationUsers = true,
        listReports = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        updateOrganization = true,
    )

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
        createSubmission = true,
        deleteProject = true,
        readDefaultVoters = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectDocumentSettings = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    // Not an admin of this org but can still access accelerator-related functions.
    permissions.expect(
        OrganizationId(3),
        readOrganizationDeliverables = true,
    )

    // Can read and perform certain operations on orgs with Accelerator internal tag.
    permissions.expect(
        OrganizationId(4),
        readOrganization = true,
        readOrganizationDeliverables = true,
    )

    permissions.expect(
        ProjectId(3000),
        createSubmission = true,
        readDefaultVoters = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectScores = true,
        readProjectVotes = true,
        updateProjectAcceleratorDetails = true,
        updateProjectDocumentSettings = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    permissions.expect(
        *moduleEventIds.toTypedArray(),
        readModuleEvent = true,
    )

    permissions.expect(
        *moduleIds.toTypedArray(),
        readModule = true,
        readModuleDetails = true,
    )

    // Can read all submissions even those outside of this org
    permissions.expect(
        *submissionIds.toTypedArray(),
        readSubmission = true,
    )

    permissions.expect(
        *submissionDocumentIds.toTypedArray(),
        readSubmissionDocument = true,
    )

    permissions.expect(
        *otherUserIds.values.toTypedArray(),
        readUser = true,
    )

    permissions.expect(
        addAnyOrganizationUser = false,
        addCohortParticipant = true,
        addParticipantProject = true,
        createCohort = true,
        createCohortModule = true,
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
        manageModuleEvents = true,
        manageModules = true,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohorts = true,
        readGlobalRoles = true,
        readInternalTags = true,
        readModuleEventParticipants = true,
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
        org1Id,
        addOrganizationUser = true,
        createDraftPlantingSite = true,
        createFacility = true,
        createPlantingSite = true,
        createProject = true,
        createSpecies = true,
        listFacilities = true,
        listOrganizationUsers = true,
        listReports = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        updateOrganization = true,
    )

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
        createSubmission = true,
        deleteProject = true,
        readDefaultVoters = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    // Not an admin of this org but can still access accelerator-related functions.
    permissions.expect(
        OrganizationId(4),
        readOrganization = true,
        readOrganizationDeliverables = true,
    )

    permissions.expect(
        ProjectId(4000),
        readDefaultVoters = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        updateProjectAcceleratorDetails = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    permissions.expect(
        *moduleEventIds.toTypedArray(),
        readModuleEvent = true,
    )

    permissions.expect(
        *moduleIds.toTypedArray(),
        readModule = true,
        readModuleDetails = true,
    )

    // Can read all submissions even those outside of this org
    permissions.expect(
        *submissionIds.toTypedArray(),
        readSubmission = true,
    )

    permissions.expect(
        *submissionDocumentIds.toTypedArray(),
        readSubmissionDocument = true,
    )

    permissions.expect(
        addAnyOrganizationUser = false,
        addCohortParticipant = false,
        addParticipantProject = false,
        createCohort = false,
        createCohortModule = false,
        createDeviceManager = false,
        createParticipant = false,
        deleteCohort = false,
        deleteCohortParticipant = false,
        deleteParticipant = false,
        deleteParticipantProject = false,
        deleteSelf = true,
        importGlobalSpeciesData = false,
        manageInternalTags = false,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohorts = true,
        readGlobalRoles = false,
        readModuleEventParticipants = true,
        readInternalTags = true,
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
        org1Id,
        listFacilities = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        removeOrganizationSelf = true,
    )

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
        OrganizationId(4),
        readOrganization = true,
        readOrganizationDeliverables = true,
    )

    permissions.expect(
        *moduleEventIds.toTypedArray(),
        readModuleEvent = true,
    )

    permissions.expect(
        *moduleIds.toTypedArray(),
        readModule = true,
        readModuleDetails = true,
    )

    // Can read all submissions even those outside of this org
    permissions.expect(
        *submissionIds.toTypedArray(),
        readSubmission = true,
    )

    permissions.expect(
        *submissionDocumentIds.toTypedArray(),
        readSubmissionDocument = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        readDefaultVoters = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
    )

    // Not an admin of this org but can still access accelerator-related functions.
    permissions.expect(
        ProjectId(4000),
        readDefaultVoters = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
    )

    permissions.expect(
        addAnyOrganizationUser = false,
        addCohortParticipant = false,
        addParticipantProject = false,
        createCohort = false,
        createCohortModule = false,
        createDeviceManager = false,
        createParticipant = false,
        deleteCohort = false,
        deleteCohortParticipant = false,
        deleteParticipant = false,
        deleteParticipantProject = false,
        deleteSelf = true,
        importGlobalSpeciesData = false,
        manageInternalTags = false,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohorts = true,
        readGlobalRoles = false,
        readModuleEventParticipants = true,
        readInternalTags = true,
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
    dslContext.deleteFrom(SUBMISSIONS).execute()

    permissions.expect(deleteSelf = true)

    permissions.andNothingElse()
  }

  inner class PermissionsTracker {
    private val uncheckedAccessions = accessionIds.toMutableSet()
    private val uncheckedAutomations = automationIds.toMutableSet()
    private val uncheckedBatches = batchIds.toMutableSet()
    private val uncheckedDeliveries = deliveryIds.toMutableSet()
    private val uncheckedDeviceManagers = deviceManagerIds.toMutableSet()
    private val uncheckedDevices = deviceIds.toMutableSet()
    private val uncheckedDraftPlantingSites = draftPlantingSiteIds.toMutableSet()
    private val uncheckedFacilities = facilityIds.toMutableSet()
    private val uncheckedModuleEvents = moduleEventIds.toMutableSet()
    private val uncheckedModules = moduleIds.toMutableSet()
    private val uncheckedMonitoringPlots = monitoringPlotIds.toMutableSet()
    private val uncheckedObservations = observationIds.toMutableSet()
    private val uncheckedOrgs = organizationIds.toMutableSet()
    private val uncheckedPlantings = plantingIds.toMutableSet()
    private val uncheckedPlantingSites = plantingSiteIds.toMutableSet()
    private val uncheckedPlantingSubzones = plantingSubzoneIds.toMutableSet()
    private val uncheckedPlantingZones = plantingZoneIds.toMutableSet()
    private val uncheckedProjects = projectIds.toMutableSet()
    private val uncheckedReports = reportIds.toMutableSet()
    private val uncheckedSpecies = speciesIds.toMutableSet()
    private val uncheckedSubLocations = subLocationIds.toMutableSet()
    private val uncheckedSubmissionDocuments = submissionDocumentIds.toMutableSet()
    private val uncheckedSubmissions = submissionIds.toMutableSet()
    private val uncheckedUsers = otherUserIds.values.toMutableSet()
    private val uncheckedViabilityTests = viabilityTestIds.toMutableSet()
    private val uncheckedWithdrawals = withdrawalIds.toMutableSet()

    private var hasCheckedGlobalPermissions = false

    fun expect(
        vararg organizations: OrganizationId,
        addOrganizationUser: Boolean = false,
        createDraftPlantingSite: Boolean = false,
        createFacility: Boolean = false,
        createPlantingSite: Boolean = false,
        createProject: Boolean = false,
        createReport: Boolean = false,
        createSpecies: Boolean = false,
        deleteOrganization: Boolean = false,
        listFacilities: Boolean = false,
        listOrganizationUsers: Boolean = false,
        listReports: Boolean = false,
        readOrganization: Boolean = false,
        readOrganizationDeliverables: Boolean = false,
        readOrganizationSelf: Boolean = false,
        readOrganizationUser: Boolean = false,
        removeOrganizationSelf: Boolean = false,
        removeOrganizationUser: Boolean = false,
        updateOrganization: Boolean = false,
    ) {
      organizations.forEach { organizationId ->
        assertEquals(
            addOrganizationUser,
            user.canAddOrganizationUser(organizationId),
            "Can add organization $organizationId user")
        assertEquals(
            createDraftPlantingSite,
            user.canCreateDraftPlantingSite(organizationId),
            "Can create draft planting site in organization $organizationId")
        assertEquals(
            createFacility,
            user.canCreateFacility(organizationId),
            "Can create facility in organization $organizationId")
        assertEquals(
            createPlantingSite,
            user.canCreatePlantingSite(organizationId),
            "Can create planting site in organization $organizationId")
        assertEquals(
            createProject,
            user.canCreateProject(organizationId),
            "Can create project in organization $organizationId")
        assertEquals(
            createReport,
            user.canCreateReport(organizationId),
            "Can create report in organization $organizationId")
        assertEquals(
            createSpecies,
            user.canCreateSpecies(organizationId),
            "Can create species in organization $organizationId")
        assertEquals(
            deleteOrganization,
            user.canDeleteOrganization(organizationId),
            "Can delete organization $organizationId")
        assertEquals(
            listFacilities,
            user.canListFacilities(organizationId),
            "Can list facilities in organization $organizationId")
        assertEquals(
            listOrganizationUsers,
            user.canListOrganizationUsers(organizationId),
            "Can list users in organization $organizationId")
        assertEquals(
            listReports,
            user.canListReports(organizationId),
            "Can list reports in organization $organizationId")
        assertEquals(
            readOrganization,
            user.canReadOrganization(organizationId),
            "Can read organization $organizationId")
        assertEquals(
            readOrganizationDeliverables,
            user.canReadOrganizationDeliverables(organizationId),
            "Can read deliverables for organization $organizationId")
        assertEquals(
            readOrganizationSelf,
            user.canReadOrganizationUser(organizationId, userId),
            "Can read self in organization $organizationId")
        assertEquals(
            readOrganizationUser,
            user.canReadOrganizationUser(organizationId, otherUserIds[organizationId]!!),
            "Can read user in organization $organizationId")
        assertEquals(
            removeOrganizationSelf,
            user.canRemoveOrganizationUser(organizationId, userId),
            "Can remove self from organization $organizationId")
        assertEquals(
            removeOrganizationUser,
            user.canRemoveOrganizationUser(organizationId, otherUserIds[organizationId]!!),
            "Can remove user from organization $organizationId")
        assertEquals(
            updateOrganization,
            user.canUpdateOrganization(organizationId),
            "Can update organization $organizationId")

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
        listAutomations: Boolean = false,
        sendAlert: Boolean = false,
        updateFacility: Boolean = false,
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
            listAutomations,
            user.canListAutomations(facilityId),
            "Can list automations at facility $facilityId")
        assertEquals(
            sendAlert, user.canSendAlert(facilityId), "Can send alert for facility $facilityId")
        assertEquals(
            updateFacility, user.canUpdateFacility(facilityId), "Can update facility $facilityId")

        uncheckedFacilities.remove(facilityId)
      }
    }

    fun expect(
        vararg accessions: AccessionId,
        deleteAccession: Boolean = false,
        readAccession: Boolean = false,
        setWithdrawalUser: Boolean = false,
        updateAccession: Boolean = false,
        uploadPhoto: Boolean = false,
    ) {
      accessions.forEach { accessionId ->
        assertEquals(
            deleteAccession,
            user.canDeleteAccession(accessionId),
            "Can delete accession $accessionId")
        assertEquals(
            readAccession, user.canReadAccession(accessionId), "Can read accession $accessionId")
        assertEquals(
            setWithdrawalUser,
            user.canSetWithdrawalUser(accessionId),
            "Can set withdrawal user for accession $accessionId")
        assertEquals(
            updateAccession,
            user.canUpdateAccession(accessionId),
            "Can update accession $accessionId")
        assertEquals(
            uploadPhoto,
            user.canUploadPhoto(accessionId),
            "Can upload photo for accession $accessionId")

        uncheckedAccessions.remove(accessionId)
      }
    }

    fun expect(
        vararg automations: AutomationId,
        deleteAutomation: Boolean = false,
        readAutomation: Boolean = false,
        triggerAutomation: Boolean = false,
        updateAutomation: Boolean = false,
    ) {
      automations.forEach { automationId ->
        assertEquals(
            deleteAutomation,
            user.canDeleteAutomation(automationId),
            "Can delete automation $automationId")
        assertEquals(
            readAutomation,
            user.canReadAutomation(automationId),
            "Can read automation $automationId")
        assertEquals(
            triggerAutomation,
            user.canTriggerAutomation(automationId),
            "Can trigger automation $automationId")
        assertEquals(
            updateAutomation,
            user.canUpdateAutomation(automationId),
            "Can update automation $automationId")

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
        readDevice: Boolean = false,
        readTimeseries: Boolean = false,
        updateDevice: Boolean = false,
        updateTimeseries: Boolean = false,
    ) {
      devices.forEach { deviceId ->
        assertEquals(
            createTimeseries,
            user.canCreateTimeseries(deviceId),
            "Can create timeseries for device $deviceId")
        assertEquals(readDevice, user.canReadDevice(deviceId), "Can read device $deviceId")
        assertEquals(
            readTimeseries,
            user.canReadTimeseries(deviceId),
            "Can read timeseries for device $deviceId")
        assertEquals(updateDevice, user.canUpdateDevice(deviceId), "Can update device $deviceId")
        assertEquals(
            updateTimeseries,
            user.canUpdateTimeseries(deviceId),
            "Can update timeseries for device $deviceId")

        uncheckedDevices.remove(deviceId)
      }
    }

    fun expect(
        vararg speciesIds: SpeciesId,
        deleteSpecies: Boolean = false,
        readSpecies: Boolean = false,
        updateSpecies: Boolean = false,
    ) {
      speciesIds.forEach { speciesId ->
        assertEquals(
            deleteSpecies, user.canDeleteSpecies(speciesId), "Can delete species $speciesId")
        assertEquals(readSpecies, user.canReadSpecies(speciesId), "Can read species $speciesId")
        assertEquals(
            updateSpecies, user.canUpdateSpecies(speciesId), "Can update species $speciesId")

        uncheckedSpecies.remove(speciesId)
      }
    }

    fun expect(
        vararg subLocationIds: SubLocationId,
        deleteSubLocation: Boolean = false,
        readSubLocation: Boolean = false,
        updateSubLocation: Boolean = false,
    ) {
      subLocationIds.forEach { subLocationId ->
        assertEquals(
            deleteSubLocation,
            user.canDeleteSubLocation(subLocationId),
            "Can delete sub-location $subLocationId")
        assertEquals(
            readSubLocation,
            user.canReadSubLocation(subLocationId),
            "Can read sub-location $subLocationId")
        assertEquals(
            updateSubLocation,
            user.canUpdateSubLocation(subLocationId),
            "Can update sub-location $subLocationId")

        uncheckedSubLocations.remove(subLocationId)
      }
    }

    /** Checks for globally-scoped permissions. */
    fun expect(
        addAnyOrganizationUser: Boolean = false,
        addCohortParticipant: Boolean = false,
        addParticipantProject: Boolean = false,
        createCohort: Boolean = false,
        createCohortModule: Boolean = false,
        createDeviceManager: Boolean = false,
        createParticipant: Boolean = false,
        deleteCohort: Boolean = false,
        deleteCohortParticipant: Boolean = false,
        deleteParticipant: Boolean = false,
        deleteParticipantProject: Boolean = false,
        deleteSelf: Boolean = false,
        deleteSupportIssue: Boolean = false,
        importGlobalSpeciesData: Boolean = false,
        manageDeliverables: Boolean = false,
        manageInternalTags: Boolean = false,
        manageModuleEvents: Boolean = false,
        manageModuleEventStatuses: Boolean = false,
        manageModules: Boolean = false,
        manageNotifications: Boolean = false,
        readAllAcceleratorDetails: Boolean = false,
        readAllDeliverables: Boolean = false,
        readCohort: Boolean = false,
        readCohorts: Boolean = false,
        readGlobalRoles: Boolean = false,
        readInternalTags: Boolean = false,
        readModuleEventParticipants: Boolean = false,
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
          user.canAddCohortParticipant(cohortIds[0], participantIds[0]),
          "Can add cohort participant")
      assertEquals(
          addParticipantProject,
          user.canAddParticipantProject(participantIds[0], projectIds[0]),
          "Can add participant project")
      assertEquals(createCohort, user.canCreateCohort(), "Can create cohort")
      assertEquals(createCohortModule, user.canCreateCohortModule(), "Can create cohort module")
      assertEquals(createDeviceManager, user.canCreateDeviceManager(), "Can create device manager")
      assertEquals(createParticipant, user.canCreateParticipant(), "Can create participant")
      assertEquals(deleteCohort, user.canDeleteCohort(cohortIds[0]), "Can delete cohort")
      assertEquals(
          deleteCohortParticipant,
          user.canDeleteCohortParticipant(cohortIds[0], participantIds[0]),
          "Can delete cohort participant")
      assertEquals(
          deleteParticipant, user.canDeleteParticipant(participantIds[0]), "Can delete participant")
      assertEquals(
          deleteParticipantProject,
          user.canDeleteParticipantProject(participantIds[0], projectIds[0]),
          "Can delete participant project")
      assertEquals(deleteSelf, user.canDeleteSelf(), "Can delete self")
      assertEquals(deleteSupportIssue, user.canDeleteSupportIssue(), "Can delete support issue")
      assertEquals(
          importGlobalSpeciesData,
          user.canImportGlobalSpeciesData(),
          "Can import global species data")
      assertEquals(manageDeliverables, user.canManageDeliverables(), "Can manage deliverables")
      assertEquals(manageInternalTags, user.canManageInternalTags(), "Can manage internal tags")
      assertEquals(manageModuleEvents, user.canManageModuleEvents(), "Can manage module events")
      assertEquals(
          manageModuleEventStatuses,
          user.canManageModuleEventStatuses(),
          "Can manage module event statuses")
      assertEquals(manageModules, user.canManageModules(), "Can manage modules")
      assertEquals(manageNotifications, user.canManageNotifications(), "Can manage notifications")
      assertEquals(
          readAllAcceleratorDetails,
          user.canReadAllAcceleratorDetails(),
          "Can read all accelerator details")
      assertEquals(readAllDeliverables, user.canReadAllDeliverables(), "Can read all deliverables")
      assertEquals(readCohort, user.canReadCohort(cohortIds[0]), "Can read cohort")
      assertEquals(readCohorts, user.canReadCohorts(), "Can read all cohorts")
      assertEquals(readGlobalRoles, user.canReadGlobalRoles(), "Can read global roles")
      assertEquals(readInternalTags, user.canReadInternalTags(), "Can read internal tags")
      assertEquals(
          readModuleEventParticipants,
          user.canReadModuleEventParticipants(),
          "Can read module event participants")
      assertEquals(
          readParticipant, user.canReadParticipant(participantIds[0]), "Can read participant")
      assertEquals(
          regenerateAllDeviceManagerTokens,
          user.canRegenerateAllDeviceManagerTokens(),
          "Can regenerate all device manager tokens")
      assertEquals(setTestClock, user.canSetTestClock(), "Can set test clock")
      assertEquals(updateAppVersions, user.canUpdateAppVersions(), "Can update app versions")
      assertEquals(updateCohort, user.canUpdateCohort(cohortIds[0]), "Can update cohort")
      assertEquals(
          updateDeviceTemplates, user.canUpdateDeviceTemplates(), "Can update device templates")
      assertEquals(updateGlobalRoles, user.canUpdateGlobalRoles(), "Can update global roles")
      assertEquals(
          updateParticipant, user.canUpdateParticipant(participantIds[0]), "Can update participant")
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
        vararg monitoringPlotIds: MonitoringPlotId,
        readMonitoringPlot: Boolean = false,
    ) {
      monitoringPlotIds.forEach { monitoringPlotId ->
        assertEquals(
            readMonitoringPlot,
            user.canReadMonitoringPlot(monitoringPlotId),
            "Can read monitoring plot $monitoringPlotId")

        uncheckedMonitoringPlots.remove(monitoringPlotId)
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
        createSubmission: Boolean = false,
        deleteProject: Boolean = false,
        readDefaultVoters: Boolean = false,
        readProject: Boolean = false,
        readProjectAcceleratorDetails: Boolean = false,
        readProjectDeliverables: Boolean = false,
        readProjectModules: Boolean = false,
        readProjectScores: Boolean = false,
        readProjectVotes: Boolean = false,
        updateDefaultVoters: Boolean = false,
        updateProject: Boolean = false,
        updateProjectAcceleratorDetails: Boolean = false,
        updateProjectDocumentSettings: Boolean = false,
        updateProjectScores: Boolean = false,
        updateProjectVotes: Boolean = false,
        updateSubmissionStatus: Boolean = false,
    ) {
      projectIds.forEach { projectId ->
        assertEquals(
            createSubmission,
            user.canCreateSubmission(projectId),
            "Can create submission for project $projectId")
        assertEquals(
            deleteProject, user.canDeleteProject(projectId), "Can delete project $projectId")
        assertEquals(readDefaultVoters, user.canReadDefaultVoters(), "Can read default voters")
        assertEquals(
            readProjectModules, user.canReadProjectModules(projectId), "Can read project modules")
        assertEquals(readProject, user.canReadProject(projectId), "Can read project $projectId")
        assertEquals(
            readProjectAcceleratorDetails,
            user.canReadProjectAcceleratorDetails(projectId),
            "Can read accelerator details for project $projectId")
        assertEquals(
            readProjectDeliverables,
            user.canReadProjectDeliverables(projectId),
            "Can read deliverables for project $projectId")
        assertEquals(
            readProjectScores,
            user.canReadProjectScores(projectId),
            "Can read scores for project $projectId")
        assertEquals(
            readProjectVotes,
            user.canReadProjectVotes(projectId),
            "Can read votes for project $projectId")
        assertEquals(
            updateDefaultVoters, user.canUpdateDefaultVoters(), "Can update default voters")
        assertEquals(
            updateProject, user.canUpdateProject(projectId), "Can update project $projectId")
        assertEquals(
            updateProjectAcceleratorDetails,
            user.canUpdateProjectAcceleratorDetails(projectId),
            "Can update accelerator details for project $projectId")
        assertEquals(
            updateProjectDocumentSettings,
            user.canUpdateProjectDocumentSettings(projectId),
            "Can update project document settings for project $projectId")
        assertEquals(
            updateProjectScores,
            user.canUpdateProjectScores(projectId),
            "Can update scores for project $projectId")

        assertEquals(
            updateProjectVotes,
            user.canUpdateProjectVotes(projectId),
            "Can update votes for project $projectId")

        assertEquals(
            updateSubmissionStatus,
            user.canUpdateSubmissionStatus(deliverableIds.first(), projectId),
            "Can update submission status for project $projectId")

        uncheckedProjects.remove(projectId)
      }
    }

    fun expect(
        vararg eventIds: EventId,
        readModuleEvent: Boolean = false,
    ) {
      eventIds.forEach { eventId ->
        assertEquals(
            readModuleEvent, user.canReadModuleEvent(eventId), "Can read module event $eventId")

        uncheckedModuleEvents.remove(eventId)
      }
    }

    fun expect(
        vararg moduleIds: ModuleId,
        readModule: Boolean = false,
        readModuleDetails: Boolean = false,
    ) {
      moduleIds.forEach { moduleId ->
        assertEquals(readModule, user.canReadModule(moduleId), "Can read module $moduleId")
        assertEquals(
            readModuleDetails,
            user.canReadModuleDetails(moduleId),
            "Can read module details $moduleId")

        uncheckedModules.remove(moduleId)
      }
    }

    fun expect(
        vararg submissionIds: SubmissionId,
        readSubmission: Boolean = false,
    ) {
      submissionIds.forEach { submissionId ->
        assertEquals(
            readSubmission,
            user.canReadSubmission(submissionId),
            "Can read submission $submissionId")

        uncheckedSubmissions.remove(submissionId)
      }
    }

    fun expect(
        vararg documentIds: SubmissionDocumentId,
        readSubmissionDocument: Boolean = false,
    ) {
      documentIds.forEach { documentId ->
        assertEquals(
            readSubmissionDocument,
            user.canReadSubmissionDocument(documentId),
            "Can read submission document $documentId")

        uncheckedSubmissionDocuments.remove(documentId)
      }
    }

    fun expect(
        vararg userIds: UserId,
        readUser: Boolean = false,
    ) {
      userIds.forEach { userId ->
        assertEquals(readUser, user.canReadUser(userId), "Can read user $userId")

        uncheckedUsers.remove(userId)
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
      expect(*uncheckedModuleEvents.toTypedArray())
      expect(*uncheckedModules.toTypedArray())
      expect(*uncheckedMonitoringPlots.toTypedArray())
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
      expect(*uncheckedSubmissionDocuments.toTypedArray())
      expect(*uncheckedSubmissions.toTypedArray())
      expect(*uncheckedUsers.toTypedArray())
      expect(*uncheckedViabilityTests.toTypedArray())
      expect(*uncheckedWithdrawals.toTypedArray())

      if (!hasCheckedGlobalPermissions) {
        expect()
      }
    }
  }
}
