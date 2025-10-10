package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.PermissionTest.PermissionsTracker
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.accelerator.tables.references.ACTIVITIES
import com.terraformation.backend.db.accelerator.tables.references.SUBMISSIONS
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
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
import com.terraformation.backend.db.default_schema.tables.references.SEED_FUND_REPORTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SUB_LOCATIONS
import com.terraformation.backend.db.default_schema.tables.references.TIMESERIES
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.docprod.DocumentId
import com.terraformation.backend.db.funder.FundingEntityId
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
import com.terraformation.backend.db.tracking.ObservationState
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
 * permutations of objects.
 *
 * ```
 * Organization 1 - Two of everything
 *   Facility 1000
 *   Facility 1001
 *   Project 1000
 *   Project 1001
 *
 * Organization 2 - No facilities or planting sites or application
 *
 * Organization 3 - One of everything, but current user isn't a member
 *
 * Organization 4 - Has the Accelerator internal tag
 *   Project 4000
 *
 * Upload 1 - created by the test's default user ID
 * ```
 *
 * The code refers to entities using the fixed IDs listed above so that any error messages that
 * include the failing IDs are easy to interpret. The actual IDs in the database are dynamic
 * (allocated by the database at insert time) and there is a mapping between the fixed and actual
 * IDs.
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

  private lateinit var userId: UserId
  private val user: TerrawareUser by lazy { fetchUser() }

  /*
   * Test data set; see class docs for a prettier version.
   */
  private val org1Id = OrganizationId(1)
  private val organizationIds =
      listOf(org1Id, OrganizationId(2), OrganizationId(3), OrganizationId(4))

  // Org 2 is empty (no reports or species)
  private val seedFundReportIds = listOf(SeedFundReportId(1), SeedFundReportId(3))
  private val speciesIds = listOf(SpeciesId(1), SpeciesId(3), SpeciesId(4))

  private val facilityIds = listOf(1000, 1001, 3000, 4000).map { FacilityId(it.toLong()) }
  private val draftPlantingSiteIds = facilityIds.map { DraftPlantingSiteId(it.value) }
  private val monitoringPlotIds = facilityIds.map { MonitoringPlotId(it.value) }
  private val plantingSiteIds = facilityIds.map { PlantingSiteId(it.value) }
  private val plantingSubzoneIds = facilityIds.map { PlantingSubzoneId(it.value) }
  private val plantingZoneIds = facilityIds.map { PlantingZoneId(it.value) }
  private val observationIds = plantingSiteIds.map { ObservationId(it.value) }

  private val projectIds = listOf(1000, 1001, 3000, 4000).map { ProjectId(it.toLong()) }
  private val moduleEventIds = listOf(1000, 1001, 3000, 4000).map { EventId(it.toLong()) }
  private val documentIds = projectIds.map { DocumentId(it.value) }
  private val submissionDocumentIds = projectIds.map { SubmissionDocumentId(it.value) }
  private val reportIds = listOf(1000, 1001, 3000, 4000).map { ReportId(it.toLong()) }
  private val activityIds = projectIds.map { ActivityId(it.value) }

  private val accessionIds = facilityIds.map { AccessionId(it.value) }
  private val automationIds = facilityIds.map { AutomationId(it.value) }
  private val batchIds = facilityIds.map { BatchId(it.value) }
  private val deviceIds = facilityIds.map { DeviceId(it.value) }
  private val subLocationIds = facilityIds.map { SubLocationId(it.value) }
  private val viabilityTestIds = facilityIds.map { ViabilityTestId(it.value) }
  private val withdrawalIds = facilityIds.map { WithdrawalId(it.value) }

  private val deliveryIds = plantingSiteIds.map { DeliveryId(it.value) }
  private val plantingIds = plantingSiteIds.map { PlantingId(it.value) }

  private val userFundingEntityId = FundingEntityId(1000)
  private val otherFundingEntityId = FundingEntityId(2000)
  private val fundingEntityIds = listOf(userFundingEntityId, otherFundingEntityId)

  private val deviceManagerIds = listOf(1000L, 1001L, 2000L).map { DeviceManagerId(it) }
  private val nonConnectedDeviceManagerIds = deviceManagerIds.filterToArray { it.value >= 2000 }

  private lateinit var sameOrgUserId: UserId
  private lateinit var otherUserIds: Map<OrganizationId, UserId>

  private val participantIds = listOf(1, 3, 4).map { ParticipantId(it.toLong()) }
  private val cohortIds = listOf(1, 3, 4).map { CohortId(it.toLong()) }
  private val globalRoles = setOf(GlobalRole.SuperAdmin)

  private val applicationIds = listOf(1000, 1001, 3000).map { ApplicationId(it.toLong()) }
  private val moduleIds = listOf(1000, 1001, 3000, 4000).map { ModuleId(it.toLong()) }
  private val deliverableIds = listOf(DeliverableId(1000))
  private val submissionIds = projectIds.map { SubmissionId(it.value) }
  private val participantProjectSpeciesIds =
      projectIds.map { ParticipantProjectSpeciesId(it.value) }

  private inline fun <reified T> List<T>.filterToArray(func: (T) -> Boolean): Array<T> =
      filter(func).toTypedArray()

  private inline fun <reified T> List<T>.filterStartsWith(prefix: String): Array<T> =
      filter { "$it".startsWith(prefix) }.toTypedArray()

  private inline fun <reified T> List<T>.forOrg1() = filterStartsWith("1")

  private inline fun <reified T> List<T>.forOrgs(orgIds: List<Int>) = filterToArray { item ->
    orgIds.any { orgId -> item.toString().startsWith(orgId.toString()) }
  }

  private inline fun <reified T> List<T>.forFacility1000() = filterStartsWith("1000")

  private val mappedIds = mutableMapOf<Any, Any>()

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

    userId = insertUser()
    sameOrgUserId = insertUser()

    fundingEntityIds.forEach { entityId ->
      putDatabaseId(entityId, insertFundingEntity(createdBy = userId, modifiedBy = userId))
    }

    organizationIds.forEach { organizationId ->
      putDatabaseId(organizationId, insertOrganization(createdBy = userId))
      putDatabaseId(SpeciesId(organizationId.value), insertSpecies(createdBy = userId))
    }

    insertOrganizationInternalTag(
        getDatabaseId(OrganizationId(4)),
        InternalTagIds.Accelerator,
        createdBy = userId,
    )

    otherUserIds =
        mapOf(
            OrganizationId(1) to sameOrgUserId,
            OrganizationId(2) to insertUser(),
            OrganizationId(3) to insertUser(),
            OrganizationId(4) to insertUser(),
        )

    otherUserIds.forEach { (organizationId, otherUserId) ->
      insertOrganizationUser(otherUserId, getDatabaseId(organizationId), createdBy = userId)
    }

    cohortIds.forEach { cohortId -> putDatabaseId(cohortId, insertCohort(createdBy = userId)) }

    participantIds.forEach { participantId ->
      putDatabaseId(
          participantId,
          insertParticipant(
              createdBy = userId,
              cohortId = getDatabaseId(CohortId(participantId.value)),
          ),
      )
    }

    projectIds.forEach { projectId ->
      putDatabaseId(
          projectId,
          insertProject(
              createdBy = userId,
              organizationId = getDatabaseId(OrganizationId(projectId.value / 1000)),
              participantId = getDatabaseId(ParticipantId(projectId.value / 1000)),
          ),
      )
    }

    facilityIds.forEach { facilityId ->
      val organizationIdInDatabase = getDatabaseId(OrganizationId(facilityId.value / 1000))
      val speciesIdInDatabase = getDatabaseId(SpeciesId(facilityId.value / 1000))

      putDatabaseId(
          facilityId,
          insertFacility(organizationId = organizationIdInDatabase, createdBy = userId),
      )

      putDatabaseId(DeviceId(facilityId.value), insertDevice(createdBy = userId))
      putDatabaseId(AutomationId(facilityId.value), insertAutomation(createdBy = userId))

      putDatabaseId(
          AccessionId(facilityId.value),
          insertAccession(
              createdBy = userId,
              facilityId = getDatabaseId(facilityId),
              projectId = getDatabaseId(ProjectId(facilityId.value)),
          ),
      )
      val viabilityTestsRow =
          ViabilityTestsRow(
              accessionId = inserted.accessionId,
              seedsSown = 1,
              testType = ViabilityTestType.Lab,
          )
      viabilityTestsDao.insert(viabilityTestsRow)
      putDatabaseId(ViabilityTestId(facilityId.value), viabilityTestsRow.id!!)

      putDatabaseId(
          BatchId(facilityId.value),
          insertBatch(
              createdBy = userId,
              organizationId = organizationIdInDatabase,
              speciesId = speciesIdInDatabase,
              projectId = getDatabaseId(ProjectId(facilityId.value)),
          ),
      )
      putDatabaseId(
          WithdrawalId(facilityId.value),
          insertNurseryWithdrawal(createdBy = userId, purpose = WithdrawalPurpose.OutPlant),
      )
    }

    subLocationIds.forEach { subLocationId ->
      putDatabaseId(
          subLocationId,
          insertSubLocation(
              facilityId = getDatabaseId(FacilityId(subLocationId.value)),
              createdBy = userId,
          ),
      )
    }

    deviceManagerIds.forEach { deviceManagerId ->
      val facilityId = FacilityId(deviceManagerId.value)
      val deviceManagersRow =
          DeviceManagersRow(
              balenaId = BalenaDeviceId(deviceManagerId.value),
              balenaUuid = UUID.randomUUID().toString(),
              balenaModifiedTime = Instant.EPOCH,
              deviceName = "$deviceManagerId",
              isOnline = true,
              createdTime = Instant.EPOCH,
              refreshedTime = Instant.EPOCH,
              sensorKitId = "$deviceManagerId",
              facilityId = if (facilityId in facilityIds) getDatabaseId(facilityId) else null,
              userId = if (facilityId in facilityIds) sameOrgUserId else null,
          )
      deviceManagersDao.insert(deviceManagersRow)
      putDatabaseId(deviceManagerId, deviceManagersRow.id!!)
    }

    plantingSiteIds.forEach { plantingSiteId ->
      putDatabaseId(
          plantingSiteId,
          insertPlantingSite(
              createdBy = userId,
              organizationId = getDatabaseId(OrganizationId(plantingSiteId.value / 1000)),
              projectId = getDatabaseId(ProjectId(plantingSiteId.value)),
          ),
      )
      putDatabaseId(
          DeliveryId(plantingSiteId.value),
          insertDelivery(
              createdBy = userId,
              withdrawalId = getDatabaseId(WithdrawalId(plantingSiteId.value)),
              plantingSiteId = getDatabaseId(plantingSiteId),
          ),
      )
      putDatabaseId(
          PlantingId(plantingSiteId.value),
          insertPlanting(
              createdBy = userId,
              speciesId = getDatabaseId(SpeciesId(plantingSiteId.value / 1000)),
          ),
      )
    }

    plantingZoneIds.forEach { plantingZoneId ->
      putDatabaseId(
          plantingZoneId,
          insertPlantingZone(
              createdBy = userId,
              plantingSiteId = getDatabaseId(PlantingSiteId(plantingZoneId.value)),
          ),
      )
    }

    plantingSubzoneIds.forEach { plantingSubzoneId ->
      putDatabaseId(
          plantingSubzoneId,
          insertPlantingSubzone(
              createdBy = userId,
              plantingSiteId = getDatabaseId(PlantingSiteId(plantingSubzoneId.value)),
              plantingZoneId = getDatabaseId(PlantingZoneId(plantingSubzoneId.value)),
          ),
      )
    }

    monitoringPlotIds.forEach { monitoringPlotId ->
      putDatabaseId(
          monitoringPlotId,
          insertMonitoringPlot(
              createdBy = userId,
              organizationId = getDatabaseId(OrganizationId(monitoringPlotId.value / 1000)),
              plantingSubzoneId = getDatabaseId(PlantingSubzoneId(monitoringPlotId.value)),
          ),
      )
    }

    draftPlantingSiteIds.forEach { draftPlantingSiteId ->
      putDatabaseId(
          draftPlantingSiteId,
          insertDraftPlantingSite(
              createdBy = userId,
              organizationId = getDatabaseId(OrganizationId(draftPlantingSiteId.value / 1000)),
              projectId = getDatabaseId(ProjectId(draftPlantingSiteId.value)),
          ),
      )
    }

    seedFundReportIds.forEach { reportId ->
      putDatabaseId(
          reportId,
          insertSeedFundReport(organizationId = getDatabaseId(OrganizationId(reportId.value))),
      )
    }

    observationIds.forEach { observationId ->
      putDatabaseId(
          observationId,
          insertObservation(
              plantingSiteId = getDatabaseId(PlantingSiteId(observationId.value)),
              state = ObservationState.Upcoming,
          ),
      )
    }

    moduleIds.forEach { moduleId ->
      putDatabaseId(moduleId, insertModule(createdBy = userId))

      insertCohortModule(getDatabaseId(CohortId(moduleId.value / 1000)), inserted.moduleId)
    }

    deliverableIds.forEach { deliverableId ->
      putDatabaseId(
          deliverableId,
          insertDeliverable(
              createdBy = userId,
              moduleId = getDatabaseId(ModuleId(deliverableId.value)),
          ),
      )

      submissionIds.forEach { submissionId ->
        putDatabaseId(
            submissionId,
            insertSubmission(
                createdBy = userId,
                projectId = getDatabaseId(ProjectId(submissionId.value)),
            ),
        )
      }
    }

    reportIds.forEach { reportId ->
      val projectId = getDatabaseId(ProjectId(reportId.value))
      val configId = insertProjectReportConfig(projectId = projectId)
      putDatabaseId(reportId, insertReport(configId = configId, projectId = projectId))
    }

    moduleEventIds.forEach { eventId ->
      putDatabaseId(
          eventId,
          insertEvent(createdBy = userId, moduleId = getDatabaseId(ModuleId(eventId.value))),
      )
      insertEventProject(projectId = getDatabaseId(ProjectId(eventId.value)))
    }

    participantProjectSpeciesIds.forEach { participantProjectSpeciesId ->
      putDatabaseId(
          participantProjectSpeciesId,
          insertParticipantProjectSpecies(
              createdBy = userId,
              projectId = getDatabaseId(ProjectId(participantProjectSpeciesId.value)),
          ),
      )
    }

    applicationIds.forEach { applicationId ->
      putDatabaseId(
          applicationId,
          insertApplication(
              createdBy = userId,
              internalName = "XXX_$applicationId",
              projectId = getDatabaseId(ProjectId(applicationId.value)),
          ),
      )
    }

    activityIds.forEach { activityId ->
      putDatabaseId(
          activityId,
          insertActivity(
              createdBy = userId,
              projectId = getDatabaseId(ProjectId(activityId.value)),
          ),
      )
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
        readOrganizationFeatures = true,
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
        readFacility = true,
        sendAlert = true,
        updateFacility = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        deleteAccession = true,
        readAccession = true,
        setWithdrawalUser = true,
        updateAccession = true,
        updateAccessionProject = true,
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
        deletePlantingSite = true,
        readPlantingSite = true,
        scheduleAdHocObservation = true,
        scheduleObservation = true,
        updatePlantingSite = true,
        updatePlantingSiteProject = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
        updatePlantingSubzoneCompleted = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
        updatePlantingZone = true,
        updateT0 = true,
    )

    permissions.expect(
        *monitoringPlotIds.forOrg1(),
        readMonitoringPlot = true,
        updateMonitoringPlot = true,
        updateT0 = true,
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
        *seedFundReportIds.forOrg1(),
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
        *participantProjectSpeciesIds.forOrg1(),
        deleteParticipantProjectSpecies = true,
        readParticipantProjectSpecies = true,
        updateParticipantProjectSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        createActivity = true,
        createApplication = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        deleteProject = true,
        listActivities = true,
        readProject = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        updateProject = true,
        updateProjectReports = true,
    )

    permissions.expect(
        *submissionIds.forOrg1(),
        readSubmission = true,
    )

    permissions.expect(
        *applicationIds.forOrg1(),
        readApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
        updateApplicationSubmissionStatus = true,
    )

    permissions.expect(
        *reportIds.forOrg1(),
        readReport = true,
        updateReport = true,
    )

    permissions.expect(
        *activityIds.forOrg1(),
        deleteActivity = true,
        readActivity = true,
        updateActivity = true,
    )

    permissions.expect(
        deleteSelf = true,
        readCohort = true,
        readParticipant = true,
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
        readOrganizationFeatures = true,
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
          ]
  )
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
        readOrganizationFeatures = true,
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
        readFacility = true,
        sendAlert = true,
        updateFacility = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        deleteAccession = true,
        readAccession = true,
        setWithdrawalUser = true,
        updateAccession = true,
        updateAccessionProject = true,
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
        deletePlantingSite = true,
        readPlantingSite = true,
        scheduleAdHocObservation = true,
        scheduleObservation = true,
        updatePlantingSite = true,
        updatePlantingSiteProject = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
        updatePlantingSubzoneCompleted = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
        updatePlantingZone = true,
        updateT0 = true,
    )

    permissions.expect(*moduleEventIds.forOrg1(), readModuleEvent = true)

    permissions.expect(*moduleIds.forOrg1(), readModule = true)

    permissions.expect(
        *monitoringPlotIds.forOrg1(),
        readMonitoringPlot = true,
        updateMonitoringPlot = true,
        updateT0 = true,
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
        *seedFundReportIds.forOrg1(),
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
        *participantProjectSpeciesIds.forOrg1(),
        deleteParticipantProjectSpecies = true,
        readParticipantProjectSpecies = true,
        updateParticipantProjectSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        createActivity = true,
        createApplication = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        deleteProject = true,
        listActivities = true,
        readProject = true,
        readProjectDeliverables = true,
        readProjectModules = true,
        updateProject = true,
        updateProjectReports = true,
    )

    permissions.expect(
        *submissionIds.forOrg1(),
        readSubmission = true,
    )

    permissions.expect(
        *applicationIds.forOrg1(),
        readApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
        updateApplicationSubmissionStatus = true,
    )

    permissions.expect(
        *reportIds.forOrg1(),
        readReport = true,
        updateReport = true,
    )

    permissions.expect(
        *activityIds.forOrg1(),
        deleteActivity = true,
        readActivity = true,
        updateActivity = true,
    )

    permissions.expect(
        deleteSelf = true,
        readCohort = true,
        readParticipant = true,
    )

    permissions.andNothingElse()
  }

  @Test
  fun `managers can access all data in their organizations`() {
    givenRole(org1Id, Role.Manager)

    val permissions = PermissionsTracker()

    permissions.expect(
        org1Id,
        createSpecies = true,
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationFeatures = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createBatch = true,
        listAutomations = true,
        readFacility = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        deleteAccession = true,
        readAccession = true,
        setWithdrawalUser = true,
        updateAccession = true,
        updateAccessionProject = true,
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
        scheduleAdHocObservation = true,
        updatePlantingSiteProject = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
        updatePlantingSubzoneCompleted = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
        updateT0 = true,
    )

    permissions.expect(
        *monitoringPlotIds.forOrg1(),
        readMonitoringPlot = true,
        updateT0 = true,
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
        *participantProjectSpeciesIds.forOrg1(),
        deleteParticipantProjectSpecies = true,
        readParticipantProjectSpecies = true,
        updateParticipantProjectSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        createSubmission = true,
        createParticipantProjectSpecies = true,
        listActivities = true,
        readProject = true,
        readProjectDeliverables = true,
        readProjectModules = true,
    )

    permissions.expect(
        *submissionIds.forOrg1(),
        readSubmission = true,
    )

    permissions.expect(
        *reportIds.forOrg1(),
        readReport = true,
        updateReport = true,
    )

    permissions.expect(
        *activityIds.forOrg1(),
        readActivity = true,
    )

    permissions.expect(
        deleteSelf = true,
        readCohort = true,
        readParticipant = true,
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
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createBatch = true,
        listAutomations = true,
        readFacility = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        deleteAccession = false,
        readAccession = true,
        updateAccession = false,
        updateAccessionProject = true,
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
        scheduleAdHocObservation = true,
        updatePlantingSiteProject = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
        updatePlantingSubzoneCompleted = true,
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
        *participantProjectSpeciesIds.forOrg1(),
        readParticipantProjectSpecies = true,
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
        readCohort = true,
        readParticipant = true,
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
            .fetchOneById(getDatabaseId(deviceManagerId))!!
            .copy(facilityId = getDatabaseId(facilityId), userId = userId)
    )

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
        readFacility = true,
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
        addTerraformationContact = true,
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
        readOrganizationFeatures = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        removeTerraformationContact = true,
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
        readFacility = true,
        sendAlert = true,
        updateFacility = true,
    )

    permissions.expect(
        *accessionIds.toTypedArray(),
        deleteAccession = true,
        readAccession = true,
        setWithdrawalUser = true,
        updateAccession = true,
        updateAccessionProject = true,
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
        manageDisclaimers = true,
        manageModuleEventStatuses = true,
        manageNotifications = true,
        manageProjectReportConfigs = true,
        notifyUpcomingReports = true,
        proxyGeoServerGetRequests = true,
        publishProjectProfileDetails = true,
        publishReports = true,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohortParticipants = true,
        readCohorts = true,
        readCurrentDisclaimer = true,
        readGlobalRoles = true,
        readModuleEventParticipants = true,
        readInternalTags = true,
        readParticipant = true,
        readProjectReportConfigs = true,
        readPublishedProjects = true,
        readReportInternalComments = true,
        reviewReports = true,
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
        scheduleAdHocObservation = true,
        scheduleObservation = true,
        updatePlantingSite = true,
        updatePlantingSiteProject = true,
    )

    permissions.expect(
        *plantingSubzoneIds.toTypedArray(),
        readPlantingSubzone = true,
        updatePlantingSubzoneCompleted = true,
    )

    permissions.expect(
        *plantingZoneIds.toTypedArray(),
        readPlantingZone = true,
        updatePlantingZone = true,
        updateT0 = true,
    )

    permissions.expect(
        *monitoringPlotIds.toTypedArray(),
        readMonitoringPlot = true,
        updateMonitoringPlot = true,
        updateT0 = true,
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
        *seedFundReportIds.toTypedArray(),
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
        *participantProjectSpeciesIds.toTypedArray(),
        deleteParticipantProjectSpecies = true,
        readParticipantProjectSpecies = true,
        updateParticipantProjectSpecies = true,
    )

    permissions.expect(
        *projectIds.toTypedArray(),
        createActivity = true,
        createApplication = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        deleteProject = true,
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
        updateDefaultVoters = true,
        updateInternalVariableWorkflowDetails = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectInternalUsers = true,
        updateProjectReports = true,
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
        *documentIds.toTypedArray(),
        createSavedVersion = true,
        readDocument = true,
        updateDocument = true,
    )

    permissions.expect(
        *applicationIds.toTypedArray(),
        readApplication = true,
        reviewApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
        updateApplicationSubmissionStatus = true,
    )

    permissions.expect(
        *reportIds.toTypedArray(),
        readPublishedReport = true,
        readReport = true,
        updateReport = true,
    )

    permissions.expect(
        *fundingEntityIds.toTypedArray(),
        updateFundingEntityUsers = true,
        listFundingEntityUsers = true,
        readFundingEntity = true,
    )

    permissions.expect(
        *activityIds.toTypedArray(),
        deleteActivity = true,
        manageActivity = true,
        readActivity = true,
        updateActivity = true,
    )

    permissions.expect(
        *otherUserIds.values.toTypedArray(),
        createEntityWithOwner = true,
        createNotifications = true,
        deleteFunder = true,
        readUser = true,
        readUserInternalInterests = true,
        updateUserInternalInterests = true,
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
        addTerraformationContact = true,
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
        readOrganizationFeatures = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        removeTerraformationContact = true,
        updateOrganization = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        createObservation = true,
        deletePlantingSite = true,
        movePlantingSiteToAnyOrg = true,
        readPlantingSite = true,
        scheduleAdHocObservation = true,
        scheduleObservation = true,
        updatePlantingSite = true,
        updatePlantingSiteProject = true,
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
        *participantProjectSpeciesIds.forOrg1(),
        deleteParticipantProjectSpecies = true,
        readParticipantProjectSpecies = true,
        updateParticipantProjectSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        createActivity = true,
        createApplication = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        deleteProject = true,
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
        updateDefaultVoters = true,
        updateInternalVariableWorkflowDetails = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectDocumentSettings = true,
        updateProjectInternalUsers = true,
        updateProjectReports = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    // Can read and perform certain operations on all orgs even if not a member.
    permissions.expect(
        *organizationIds.filterNot { it == org1Id }.toTypedArray(),
        addOrganizationUser = true,
        addTerraformationContact = true,
        createReport = true,
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        removeTerraformationContact = true,
    )

    // Can access accelerator-related functions on all orgs.
    permissions.expect(
        ProjectId(3000),
        ProjectId(4000),
        createActivity = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
        updateDefaultVoters = true,
        updateInternalVariableWorkflowDetails = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectDocumentSettings = true,
        updateProjectInternalUsers = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    permissions.expect(
        *reportIds.toTypedArray(),
        readPublishedReport = true,
        readReport = true,
        updateReport = true,
    )

    permissions.expect(
        SpeciesId(3),
        SpeciesId(4),
        readSpecies = true,
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
        *applicationIds.forOrg1(),
        readApplication = true,
        reviewApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
        updateApplicationSubmissionStatus = true,
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
        *documentIds.toTypedArray(),
        createSavedVersion = true,
        readDocument = true,
        updateDocument = true,
    )

    permissions.expect(
        *applicationIds.toTypedArray(),
        readApplication = true,
        reviewApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
    )

    permissions.expect(
        *otherUserIds.values.toTypedArray(),
        createEntityWithOwner = true,
        deleteFunder = true,
        readUser = true,
        readUserInternalInterests = true,
        updateUserInternalInterests = true,
    )

    permissions.expect(
        *fundingEntityIds.toTypedArray(),
        updateFundingEntityUsers = true,
        listFundingEntityUsers = true,
        readFundingEntity = true,
    )

    permissions.expect(
        *activityIds.toTypedArray(),
        deleteActivity = true,
        readActivity = true,
        manageActivity = true,
        updateActivity = true,
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
        deleteUsers = true,
        importGlobalSpeciesData = true,
        manageDeliverables = true,
        manageDisclaimers = true,
        manageInternalTags = true,
        manageModuleEvents = true,
        manageModules = true,
        manageProjectReportConfigs = true,
        proxyGeoServerGetRequests = true,
        publishProjectProfileDetails = true,
        publishReports = true,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohortParticipants = true,
        readCohorts = true,
        readCurrentDisclaimer = true,
        readGlobalRoles = true,
        readInternalTags = true,
        readModuleEventParticipants = true,
        readParticipant = true,
        readProjectReportConfigs = true,
        readPublishedProjects = true,
        readReportInternalComments = true,
        regenerateAllDeviceManagerTokens = true,
        reviewReports = true,
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
        addTerraformationContact = true,
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
        readOrganizationFeatures = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        removeTerraformationContact = true,
        updateOrganization = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        createObservation = true,
        deletePlantingSite = true,
        movePlantingSiteToAnyOrg = false,
        readPlantingSite = true,
        scheduleAdHocObservation = true,
        scheduleObservation = true,
        updatePlantingSite = true,
        updatePlantingSiteProject = true,
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
        *participantProjectSpeciesIds.forOrg1(),
        deleteParticipantProjectSpecies = true,
        readParticipantProjectSpecies = true,
        updateParticipantProjectSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        createActivity = true,
        createApplication = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        deleteProject = true,
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
        updateDefaultVoters = true,
        updateInternalVariableWorkflowDetails = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectDocumentSettings = true,
        updateProjectInternalUsers = true,
        updateProjectReports = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    // Not an admin of this org but can still access it because it has an application.
    permissions.expect(
        OrganizationId(3),
        addTerraformationContact = true,
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationUser = true,
        readOrganizationDeliverables = true,
        removeTerraformationContact = true,
    )

    // Can read and perform certain operations on orgs with Accelerator internal tag.
    permissions.expect(
        OrganizationId(4),
        addTerraformationContact = true,
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationUser = true,
        removeTerraformationContact = true,
    )

    // Can access this project because it has an application.
    permissions.expect(
        ProjectId(3000),
        createActivity = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
        updateDefaultVoters = true,
        updateInternalVariableWorkflowDetails = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectDocumentSettings = true,
        updateProjectInternalUsers = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    permissions.expect(
        SpeciesId(3),
        SpeciesId(4),
        readSpecies = true,
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
        *applicationIds.forOrg1(),
        readApplication = true,
        reviewApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
        updateApplicationSubmissionStatus = true,
    )

    permissions.expect(
        *reportIds.toTypedArray(),
        readPublishedReport = true,
        readReport = true,
        updateReport = true,
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
        *documentIds.toTypedArray(),
        createSavedVersion = true,
        readDocument = true,
        updateDocument = true,
    )

    permissions.expect(
        *applicationIds.toTypedArray(),
        readApplication = true,
        reviewApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
    )

    permissions.expect(
        *otherUserIds.values.toTypedArray(),
        deleteFunder = true,
        readUser = true,
        readUserInternalInterests = true,
        updateUserInternalInterests = true,
    )

    permissions.expect(
        *fundingEntityIds.toTypedArray(),
        updateFundingEntityUsers = true,
        listFundingEntityUsers = true,
        readFundingEntity = true,
    )

    permissions.expect(
        *activityIds.toTypedArray(),
        deleteActivity = true,
        manageActivity = true,
        readActivity = true,
        updateActivity = true,
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
        manageProjectReportConfigs = true,
        proxyGeoServerGetRequests = true,
        publishProjectProfileDetails = true,
        publishReports = true,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohortParticipants = true,
        readCohorts = true,
        readGlobalRoles = true,
        readInternalTags = true,
        readModuleEventParticipants = true,
        readParticipant = true,
        readProjectReportConfigs = true,
        readPublishedProjects = true,
        readReportInternalComments = true,
        regenerateAllDeviceManagerTokens = false,
        reviewReports = true,
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
        addTerraformationContact = true,
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
        readOrganizationFeatures = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
        removeOrganizationUser = true,
        removeTerraformationContact = true,
        updateOrganization = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        createDelivery = true,
        createObservation = true,
        deletePlantingSite = true,
        movePlantingSiteToAnyOrg = false,
        readPlantingSite = true,
        scheduleAdHocObservation = true,
        scheduleObservation = true,
        updatePlantingSite = true,
        updatePlantingSiteProject = true,
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
        *speciesIds.forOrg1(),
        deleteSpecies = true,
        readSpecies = true,
        updateSpecies = true,
    )

    permissions.expect(
        *participantProjectSpeciesIds.forOrg1(),
        deleteParticipantProjectSpecies = true,
        readParticipantProjectSpecies = true,
        updateParticipantProjectSpecies = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        createActivity = true,
        createApplication = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        deleteProject = true,
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
        updateInternalVariableWorkflowDetails = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectInternalUsers = true,
        updateProjectReports = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    permissions.expect(
        *fundingEntityIds.toTypedArray(),
        listFundingEntityUsers = true,
        readFundingEntity = true,
    )

    permissions.expect(
        *activityIds.forOrg1(),
        deleteActivity = true,
        manageActivity = true,
        readActivity = true,
        updateActivity = true,
    )

    // Not an admin of this org but can still access accelerator-related functions.
    permissions.expect(
        OrganizationId(4),
        addTerraformationContact = true,
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationUser = true,
        removeTerraformationContact = true,
    )

    permissions.expect(
        ProjectId(4000),
        createActivity = true,
        createParticipantProjectSpecies = true,
        createSubmission = true,
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
        updateInternalVariableWorkflowDetails = true,
        updateProject = true,
        updateProjectAcceleratorDetails = true,
        updateProjectInternalUsers = true,
        updateProjectScores = true,
        updateProjectVotes = true,
        updateSubmissionStatus = true,
    )

    permissions.expect(
        SpeciesId(3),
        SpeciesId(4),
        readSpecies = true,
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
        *applicationIds.forOrg1(),
        readApplication = true,
        reviewApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
        updateApplicationSubmissionStatus = true,
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
        *documentIds.toTypedArray(),
        createSavedVersion = true,
        readDocument = true,
        updateDocument = true,
    )

    permissions.expect(
        *applicationIds.toTypedArray(),
        readApplication = true,
        reviewApplication = true,
        updateApplicationBoundary = true,
        updateApplicationCountry = true,
    )

    permissions.expect(
        *reportIds.toTypedArray(),
        readPublishedReport = true,
        readReport = true,
        updateReport = true,
    )

    permissions.expect(
        *otherUserIds.values.toTypedArray(),
        readUser = true,
        readUserInternalInterests = true,
    )

    permissions.expect(
        ActivityId(3),
        ActivityId(4),
        deleteActivity = true,
        manageActivity = true,
        readActivity = true,
        updateActivity = true,
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
        proxyGeoServerGetRequests = true,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohortParticipants = true,
        readCohorts = true,
        readGlobalRoles = true,
        readModuleEventParticipants = true,
        readInternalTags = true,
        readParticipant = true,
        readProjectReportConfigs = true,
        readPublishedProjects = true,
        readReportInternalComments = true,
        regenerateAllDeviceManagerTokens = false,
        reviewReports = true,
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
    insertProjectAcceleratorDetails(projectId = getDatabaseId(ProjectId(4000)))

    val permissions = PermissionsTracker()

    givenRole(org1Id, Role.Contributor)

    permissions.expect(
        org1Id,
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationSelf = true,
        readOrganizationUser = true,
        removeOrganizationSelf = true,
    )

    permissions.expect(
        OrganizationId(2),
        readOrganizationDeliverables = true,
    )

    permissions.expect(
        OrganizationId(3),
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationUser = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrg1(),
        readPlantingSite = true,
        scheduleAdHocObservation = true,
        updatePlantingSiteProject = true,
    )

    permissions.expect(
        *observationIds.forOrg1(),
        readObservation = true,
        updateObservation = true,
    )

    permissions.expect(
        OrganizationId(4),
        listFacilities = true,
        listOrganizationUsers = true,
        readOrganization = true,
        readOrganizationDeliverables = true,
        readOrganizationUser = true,
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
        *documentIds.toTypedArray(),
        readDocument = true,
    )

    permissions.expect(
        *participantProjectSpeciesIds.toTypedArray(),
        readParticipantProjectSpecies = true,
    )

    permissions.expect(
        *applicationIds.toTypedArray(),
        readApplication = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
    )
    permissions.expect(
        ProjectId(3000),
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
    )

    // Not an admin of this org but can still access accelerator-related functions.
    permissions.expect(
        ProjectId(4000),
        listActivities = true,
        readDefaultVoters = true,
        readInternalVariableWorkflowDetails = true,
        readProject = true,
        readProjectAcceleratorDetails = true,
        readProjectDeliverables = true,
        readProjectFunderDetails = true,
        readProjectModules = true,
        readProjectScores = true,
        readProjectVotes = true,
        readPublishedReports = true,
    )

    permissions.expect(
        *reportIds.toTypedArray(),
        readPublishedReport = true,
        readReport = true,
    )

    permissions.expect(
        *speciesIds.toTypedArray(),
        readSpecies = true,
    )

    permissions.expect(
        *otherUserIds.values.toTypedArray(),
        readUser = true,
    )

    permissions.expect(
        *fundingEntityIds.toTypedArray(),
        listFundingEntityUsers = true,
        readFundingEntity = true,
    )

    permissions.expect(
        *activityIds.toTypedArray(),
        readActivity = true,
    )

    permissions.expect(
        *accessionIds.forOrg1(),
        readAccession = true,
        updateAccessionProject = true,
        uploadPhoto = true,
    )

    permissions.expect(
        *automationIds.forOrg1(),
        readAutomation = true,
    )

    permissions.expect(
        *batchIds.forOrg1(),
        deleteBatch = true,
        readBatch = true,
        updateBatch = true,
    )

    permissions.expect(
        *deliveryIds.forOrg1(),
        readDelivery = true,
        updateDelivery = true,
    )

    permissions.expect(
        *deviceManagerIds.toTypedArray(),
        readDeviceManager = true,
    )

    permissions.expect(
        *deviceIds.forOrg1(),
        readDevice = true,
        readTimeseries = true,
    )

    permissions.expect(
        *facilityIds.forOrg1(),
        createAccession = true,
        createBatch = true,
        listAutomations = true,
        readFacility = true,
    )

    permissions.expect(
        *monitoringPlotIds.forOrg1(),
        readMonitoringPlot = true,
    )

    permissions.expect(
        *plantingIds.forOrg1(),
        readPlanting = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrg1(),
        readPlantingSubzone = true,
        updatePlantingSubzoneCompleted = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrg1(),
        readPlantingZone = true,
    )

    permissions.expect(
        *subLocationIds.forOrg1(),
        SubLocationId(3000),
        readSubLocation = true,
    )

    permissions.expect(
        *viabilityTestIds.forOrg1(),
        readViabilityTest = true,
    )

    permissions.expect(
        *withdrawalIds.forOrg1(),
        createWithdrawalPhoto = true,
        readWithdrawal = true,
    )

    permissions.expect(
        WithdrawalId(3000),
        readWithdrawal = true,
    )

    // accelerator project/org details
    permissions.expect(
        *accessionIds.forOrgs(listOf(3, 4)),
        readAccession = true,
    )

    permissions.expect(
        *batchIds.forOrgs(listOf(3, 4)),
        readBatch = true,
    )

    permissions.expect(
        *deliveryIds.forOrgs(listOf(3, 4)),
        readDelivery = true,
    )

    permissions.expect(
        *draftPlantingSiteIds.forOrgs(listOf(1, 3, 4)),
        readDraftPlantingSite = true,
    )

    permissions.expect(
        FacilityId(3000), // org has application
        readFacility = true,
    )

    permissions.expect(
        *facilityIds.forOrgs(listOf(3, 4)),
        readFacility = true,
    )

    permissions.expect(
        *monitoringPlotIds.forOrgs(listOf(3, 4)),
        readMonitoringPlot = true,
    )

    permissions.expect(
        *observationIds.forOrgs(listOf(3, 4)),
        readObservation = true,
    )

    permissions.expect(
        *plantingIds.forOrgs(listOf(3, 4)),
        readPlanting = true,
    )

    permissions.expect(
        *plantingSiteIds.forOrgs(listOf(3, 4)),
        readPlantingSite = true,
    )

    permissions.expect(
        *plantingSubzoneIds.forOrgs(listOf(3, 4)),
        readPlantingSubzone = true,
    )

    permissions.expect(
        *plantingZoneIds.forOrgs(listOf(3, 4)),
        readPlantingZone = true,
    )

    permissions.expect(
        *subLocationIds.forOrgs(listOf(3, 4)),
        readSubLocation = true,
    )

    permissions.expect(
        *viabilityTestIds.forOrgs(listOf(3, 4)),
        readViabilityTest = true,
    )

    permissions.expect(
        *withdrawalIds.forOrgs(listOf(3, 4)),
        readWithdrawal = true,
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
        proxyGeoServerGetRequests = true,
        readAllAcceleratorDetails = true,
        readAllDeliverables = true,
        readCohort = true,
        readCohortParticipants = true,
        readCohorts = true,
        readGlobalRoles = false,
        readModuleEventParticipants = true,
        readInternalTags = true,
        readParticipant = true,
        readProjectReportConfigs = true,
        readPublishedProjects = true,
        readReportInternalComments = true,
        regenerateAllDeviceManagerTokens = false,
        setTestClock = false,
        updateAppVersions = false,
        updateCohort = false,
        updateDeviceTemplates = false,
        updateGlobalRoles = false,
        updateParticipant = false,
    )
    permissions.andNothingElse()

    // Read Only can't apply any global roles to a user
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.AcceleratorAdmin)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.ReadOnly)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.SuperAdmin)))
    assertFalse(user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.TFExpert)))
  }

  @Test
  fun `funder user has correct privileges`() {
    val permissions = PermissionsTracker()
    givenFunder(userFundingEntityId)

    permissions.expect(
        userFundingEntityId,
        updateFundingEntityUsers = true,
        listFundingEntityUsers = true,
        readFundingEntity = true,
    )

    permissions.expect(
        *projectIds.forOrg1(),
        listActivities = true,
        readProjectFunderDetails = true,
        readPublishedReports = true,
        readProject = true,
    )

    permissions.expect(
        *activityIds.forOrg1(),
        readActivity = true,
    )

    permissions.expect(
        userId,
        deleteFunder = true,
        readUser = true,
    )

    permissions.expect(
        *reportIds.forOrg1(),
        readPublishedReport = true,
    )

    val otherFunderSameEntity = insertUser(type = UserType.Funder)
    insertFundingEntityUser(
        fundingEntityId = getDatabaseId(userFundingEntityId),
        userId = otherFunderSameEntity,
    )
    val otherFunderDiffEntity = insertUser(type = UserType.Funder)
    insertFundingEntityUser(
        fundingEntityId = getDatabaseId(otherFundingEntityId),
        userId = otherFunderDiffEntity,
    )

    permissions.expect(otherFunderSameEntity, deleteFunder = true, readUser = true)
    permissions.expect(otherFunderDiffEntity)

    permissions.expect(
        acceptCurrentDisclaimer = true,
        deleteSelf = true,
        readCurrentDisclaimer = true,
    )
    permissions.andNothingElse()
  }

  @Test
  fun `user can create organization as themselves`() {
    assertTrue(user.canCreateEntityWithOwner(userId), "Can create organization as self")
  }

  @Test
  fun `user can access their own uploads`() {
    val uploadId = insertUpload(createdBy = userId)

    assertTrue(user.canReadUpload(uploadId), "Can read upload")
    assertTrue(user.canUpdateUpload(uploadId), "Can update upload")
    assertTrue(user.canDeleteUpload(uploadId), "Can delete upload")
  }

  @Test
  fun `user cannot access uploads of other users`() {
    val uploadId = insertUpload(createdBy = sameOrgUserId)

    assertFalse(user.canReadUpload(uploadId), "Can read upload")
    assertFalse(user.canUpdateUpload(uploadId), "Can update upload")
    assertFalse(user.canDeleteUpload(uploadId), "Can delete upload")
  }

  @Test
  fun `admin user can read but not write draft planting sites of other users in same org`() {
    val otherUserDraftSiteId =
        insertDraftPlantingSite(createdBy = sameOrgUserId, organizationId = getDatabaseId(org1Id))

    givenRole(org1Id, Role.Admin)

    assertTrue(user.canReadDraftPlantingSite(otherUserDraftSiteId), "Can read draft site")
    assertFalse(user.canDeleteDraftPlantingSite(otherUserDraftSiteId), "Can delete draft site")
    assertFalse(user.canUpdateDraftPlantingSite(otherUserDraftSiteId), "Can update draft site")
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T : Any> getDatabaseId(idInTest: T): T = mappedIds[idInTest] as T

  private fun <T : Any> putDatabaseId(idInTest: T, idInDatabase: T): T {
    mappedIds[idInTest] = idInDatabase
    return idInDatabase
  }

  private fun givenRole(organizationId: OrganizationId, role: Role) {
    with(ORGANIZATION_USERS) {
      dslContext
          .insertInto(ORGANIZATION_USERS)
          .set(USER_ID, userId)
          .set(ORGANIZATION_ID, getDatabaseId(organizationId))
          .set(ROLE_ID, role)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, Instant.EPOCH)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, Instant.EPOCH)
          .execute()
    }
  }

  private fun givenFunder(fundingEntityId: FundingEntityId) {
    dslContext
        .update(USERS)
        .set(USERS.USER_TYPE_ID, UserType.Funder)
        .where(USERS.ID.eq(userId))
        .execute()

    insertFundingEntityUser(getDatabaseId(fundingEntityId), userId)
    projectIds.forOrg1().forEach { projectId ->
      insertFundingEntityProject(getDatabaseId(fundingEntityId), getDatabaseId(projectId))
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

    dslContext.deleteFrom(ACTIVITIES).execute()
    dslContext.deleteFrom(SEED_FUND_REPORTS).execute()
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
    private val uncheckedActivities = activityIds.toMutableSet()
    private val uncheckedApplications = applicationIds.toMutableSet()
    private val uncheckedAutomations = automationIds.toMutableSet()
    private val uncheckedBatches = batchIds.toMutableSet()
    private val uncheckedDeliveries = deliveryIds.toMutableSet()
    private val uncheckedDeviceManagers = deviceManagerIds.toMutableSet()
    private val uncheckedDevices = deviceIds.toMutableSet()
    private val uncheckedDocuments = documentIds.toMutableSet()
    private val uncheckedDraftPlantingSites = draftPlantingSiteIds.toMutableSet()
    private val uncheckedFacilities = facilityIds.toMutableSet()
    private val uncheckedFundingEntities = fundingEntityIds.toMutableSet()
    private val uncheckedModuleEvents = moduleEventIds.toMutableSet()
    private val uncheckedModules = moduleIds.toMutableSet()
    private val uncheckedMonitoringPlots = monitoringPlotIds.toMutableSet()
    private val uncheckedObservations = observationIds.toMutableSet()
    private val uncheckedOrgs = organizationIds.toMutableSet()
    private val uncheckedParticipantProjectSpecies = participantProjectSpeciesIds.toMutableSet()
    private val uncheckedPlantings = plantingIds.toMutableSet()
    private val uncheckedPlantingSites = plantingSiteIds.toMutableSet()
    private val uncheckedPlantingSubzones = plantingSubzoneIds.toMutableSet()
    private val uncheckedPlantingZones = plantingZoneIds.toMutableSet()
    private val uncheckedProjects = projectIds.toMutableSet()
    private val uncheckedReports = reportIds.toMutableSet()
    private val uncheckedSeedFundReports = seedFundReportIds.toMutableSet()
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
        addTerraformationContact: Boolean = false,
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
        readOrganizationFeatures: Boolean = false,
        readOrganizationSelf: Boolean = false,
        readOrganizationUser: Boolean = false,
        removeOrganizationSelf: Boolean = false,
        removeOrganizationUser: Boolean = false,
        removeTerraformationContact: Boolean = false,
        updateOrganization: Boolean = false,
    ) {
      organizations.forEach { organizationId ->
        val idInDatabase = getDatabaseId(organizationId)
        assertEquals(
            addOrganizationUser,
            user.canAddOrganizationUser(idInDatabase),
            "Can add organization $organizationId user",
        )
        assertEquals(
            addTerraformationContact,
            user.canAddTerraformationContact(idInDatabase),
            "Can add TF contact for organization $organizationId",
        )
        assertEquals(
            createDraftPlantingSite,
            user.canCreateDraftPlantingSite(idInDatabase),
            "Can create draft planting site in organization $organizationId",
        )
        assertEquals(
            createFacility,
            user.canCreateFacility(idInDatabase),
            "Can create facility in organization $organizationId",
        )
        assertEquals(
            createPlantingSite,
            user.canCreatePlantingSite(idInDatabase),
            "Can create planting site in organization $organizationId",
        )
        assertEquals(
            createProject,
            user.canCreateProject(idInDatabase),
            "Can create project in organization $organizationId",
        )
        assertEquals(
            createReport,
            user.canCreateSeedFundReport(idInDatabase),
            "Can create report in organization $organizationId",
        )
        assertEquals(
            createSpecies,
            user.canCreateSpecies(idInDatabase),
            "Can create species in organization $organizationId",
        )
        assertEquals(
            deleteOrganization,
            user.canDeleteOrganization(idInDatabase),
            "Can delete organization $organizationId",
        )
        assertEquals(
            listFacilities,
            user.canListFacilities(idInDatabase),
            "Can list facilities in organization $organizationId",
        )
        assertEquals(
            listOrganizationUsers,
            user.canListOrganizationUsers(idInDatabase),
            "Can list users in organization $organizationId",
        )
        assertEquals(
            listReports,
            user.canListSeedFundReports(idInDatabase),
            "Can list reports in organization $organizationId",
        )
        assertEquals(
            readOrganization,
            user.canReadOrganization(idInDatabase),
            "Can read organization $organizationId",
        )
        assertEquals(
            readOrganizationDeliverables,
            user.canReadOrganizationDeliverables(idInDatabase),
            "Can read deliverables for organization $organizationId",
        )
        assertEquals(
            readOrganizationFeatures,
            user.canReadOrganizationFeatures(idInDatabase),
            "Can read features for organization $organizationId",
        )
        assertEquals(
            readOrganizationSelf,
            user.canReadOrganizationUser(idInDatabase, userId),
            "Can read self in organization $organizationId",
        )
        assertEquals(
            readOrganizationUser,
            user.canReadOrganizationUser(idInDatabase, otherUserIds[organizationId]!!),
            "Can read user in organization $organizationId",
        )
        assertEquals(
            removeOrganizationSelf,
            user.canRemoveOrganizationUser(idInDatabase, userId),
            "Can remove self from organization $organizationId",
        )
        assertEquals(
            removeOrganizationUser,
            user.canRemoveOrganizationUser(idInDatabase, otherUserIds[organizationId]!!),
            "Can remove user from organization $organizationId",
        )
        assertEquals(
            removeTerraformationContact,
            user.canRemoveTerraformationContact(idInDatabase),
            "Can remove TF contact from organization $organizationId",
        )
        assertEquals(
            updateOrganization,
            user.canUpdateOrganization(idInDatabase),
            "Can update organization $organizationId",
        )

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
        readFacility: Boolean = false,
        sendAlert: Boolean = false,
        updateFacility: Boolean = false,
    ) {
      facilities.forEach { facilityId ->
        val idInDatabase = getDatabaseId(facilityId)

        assertEquals(
            createAccession,
            user.canCreateAccession(idInDatabase),
            "Can create accession at facility $facilityId",
        )
        assertEquals(
            createAutomation,
            user.canCreateAutomation(idInDatabase),
            "Can create automation at facility $facilityId",
        )
        assertEquals(
            createBatch,
            user.canCreateBatch(idInDatabase),
            "Can create seedling batch at facility $facilityId",
        )
        assertEquals(
            createDevice,
            user.canCreateDevice(idInDatabase),
            "Can create device at facility $facilityId",
        )
        assertEquals(
            createSubLocation,
            user.canCreateSubLocation(idInDatabase),
            "Can create sub-location at facility $facilityId",
        )
        assertEquals(
            listAutomations,
            user.canListAutomations(idInDatabase),
            "Can list automations at facility $facilityId",
        )
        assertEquals(
            readFacility,
            user.canReadFacility(idInDatabase),
            "Can read facility $facilityId",
        )
        assertEquals(
            sendAlert,
            user.canSendAlert(idInDatabase),
            "Can send alert for facility $facilityId",
        )
        assertEquals(
            updateFacility,
            user.canUpdateFacility(idInDatabase),
            "Can update facility $facilityId",
        )

        uncheckedFacilities.remove(facilityId)
      }
    }

    fun expect(
        vararg accessions: AccessionId,
        deleteAccession: Boolean = false,
        readAccession: Boolean = false,
        setWithdrawalUser: Boolean = false,
        updateAccession: Boolean = false,
        updateAccessionProject: Boolean = false,
        uploadPhoto: Boolean = false,
    ) {
      accessions.forEach { accessionId ->
        val idInDatabase = getDatabaseId(accessionId)

        assertEquals(
            deleteAccession,
            user.canDeleteAccession(idInDatabase),
            "Can delete accession $accessionId",
        )
        assertEquals(
            readAccession,
            user.canReadAccession(idInDatabase),
            "Can read accession $accessionId",
        )
        assertEquals(
            setWithdrawalUser,
            user.canSetWithdrawalUser(idInDatabase),
            "Can set withdrawal user for accession $accessionId",
        )
        assertEquals(
            updateAccession,
            user.canUpdateAccession(idInDatabase),
            "Can update accession $accessionId",
        )
        assertEquals(
            updateAccessionProject,
            user.canUpdateAccessionProject(idInDatabase),
            "Can update project for accession $accessionId",
        )
        assertEquals(
            uploadPhoto,
            user.canUploadPhoto(idInDatabase),
            "Can upload photo for accession $accessionId",
        )

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
        val idInDatabase = getDatabaseId(automationId)

        assertEquals(
            deleteAutomation,
            user.canDeleteAutomation(idInDatabase),
            "Can delete automation $automationId",
        )
        assertEquals(
            readAutomation,
            user.canReadAutomation(idInDatabase),
            "Can read automation $automationId",
        )
        assertEquals(
            triggerAutomation,
            user.canTriggerAutomation(idInDatabase),
            "Can trigger automation $automationId",
        )
        assertEquals(
            updateAutomation,
            user.canUpdateAutomation(idInDatabase),
            "Can update automation $automationId",
        )

        uncheckedAutomations.remove(automationId)
      }
    }

    fun expect(
        vararg deviceManagerIds: DeviceManagerId,
        readDeviceManager: Boolean = false,
        updateDeviceManager: Boolean = false,
    ) {
      deviceManagerIds.forEach { deviceManagerId ->
        val idInDatabase = getDatabaseId(deviceManagerId)

        assertEquals(
            readDeviceManager,
            user.canReadDeviceManager(idInDatabase),
            "Can read device manager $deviceManagerId",
        )
        assertEquals(
            updateDeviceManager,
            user.canUpdateDeviceManager(idInDatabase),
            "Can update device manager $deviceManagerId",
        )

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
        val idInDatabase = getDatabaseId(deviceId)

        assertEquals(
            createTimeseries,
            user.canCreateTimeseries(idInDatabase),
            "Can create timeseries for device $deviceId",
        )
        assertEquals(readDevice, user.canReadDevice(idInDatabase), "Can read device $deviceId")
        assertEquals(
            readTimeseries,
            user.canReadTimeseries(idInDatabase),
            "Can read timeseries for device $deviceId",
        )
        assertEquals(
            updateDevice,
            user.canUpdateDevice(idInDatabase),
            "Can update device $deviceId",
        )
        assertEquals(
            updateTimeseries,
            user.canUpdateTimeseries(idInDatabase),
            "Can update timeseries for device $deviceId",
        )

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
        val idInDatabase = getDatabaseId(speciesId)

        assertEquals(
            deleteSpecies,
            user.canDeleteSpecies(idInDatabase),
            "Can delete species $speciesId",
        )
        assertEquals(readSpecies, user.canReadSpecies(idInDatabase), "Can read species $speciesId")
        assertEquals(
            updateSpecies,
            user.canUpdateSpecies(idInDatabase),
            "Can update species $speciesId",
        )

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
        val idInDatabase = getDatabaseId(subLocationId)

        assertEquals(
            deleteSubLocation,
            user.canDeleteSubLocation(idInDatabase),
            "Can delete sub-location $subLocationId",
        )
        assertEquals(
            readSubLocation,
            user.canReadSubLocation(idInDatabase),
            "Can read sub-location $subLocationId",
        )
        assertEquals(
            updateSubLocation,
            user.canUpdateSubLocation(idInDatabase),
            "Can update sub-location $subLocationId",
        )

        uncheckedSubLocations.remove(subLocationId)
      }
    }

    /** Checks for globally-scoped permissions. */
    fun expect(
        acceptCurrentDisclaimer: Boolean = false,
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
        deleteUsers: Boolean = false,
        importGlobalSpeciesData: Boolean = false,
        manageDeliverables: Boolean = false,
        manageDisclaimers: Boolean = false,
        manageInternalTags: Boolean = false,
        manageModuleEvents: Boolean = false,
        manageModuleEventStatuses: Boolean = false,
        manageModules: Boolean = false,
        manageNotifications: Boolean = false,
        manageProjectReportConfigs: Boolean = false,
        notifyUpcomingReports: Boolean = false,
        proxyGeoServerGetRequests: Boolean = false,
        publishProjectProfileDetails: Boolean = false,
        publishReports: Boolean = false,
        readAllAcceleratorDetails: Boolean = false,
        readAllDeliverables: Boolean = false,
        readCohort: Boolean = false,
        readCohortParticipants: Boolean = false,
        readCohorts: Boolean = false,
        readCurrentDisclaimer: Boolean = false,
        readGlobalRoles: Boolean = false,
        readInternalTags: Boolean = false,
        readModuleEventParticipants: Boolean = false,
        readParticipant: Boolean = false,
        readProjectReportConfigs: Boolean = false,
        readPublishedProjects: Boolean = false,
        readReportInternalComments: Boolean = false,
        regenerateAllDeviceManagerTokens: Boolean = false,
        reviewReports: Boolean = false,
        setTestClock: Boolean = false,
        updateAppVersions: Boolean = false,
        updateCohort: Boolean = false,
        updateDeviceTemplates: Boolean = false,
        updateGlobalRoles: Boolean = false,
        updateParticipant: Boolean = false,
        updateSpecificGlobalRoles: Boolean = false,
    ) {
      val cohortId = getDatabaseId(cohortIds[0])
      val participantId = getDatabaseId(participantIds[0])
      val projectId = getDatabaseId(projectIds[0])

      assertEquals(
          acceptCurrentDisclaimer,
          user.canAcceptCurrentDisclaimer(),
          "Can accept current disclaimer",
      )
      assertEquals(
          addAnyOrganizationUser,
          user.canAddAnyOrganizationUser(),
          "Can add any organization user",
      )
      assertEquals(
          addCohortParticipant,
          user.canAddCohortParticipant(cohortId, participantId),
          "Can add cohort participant",
      )
      assertEquals(
          addParticipantProject,
          user.canAddParticipantProject(participantId, projectId),
          "Can add participant project",
      )
      assertEquals(createCohort, user.canCreateCohort(), "Can create cohort")
      assertEquals(createCohortModule, user.canCreateCohortModule(), "Can create cohort module")
      assertEquals(createDeviceManager, user.canCreateDeviceManager(), "Can create device manager")
      assertEquals(createParticipant, user.canCreateParticipant(), "Can create participant")
      assertEquals(deleteCohort, user.canDeleteCohort(cohortId), "Can delete cohort")
      assertEquals(
          deleteCohortParticipant,
          user.canDeleteCohortParticipant(cohortId, participantId),
          "Can delete cohort participant",
      )
      assertEquals(
          deleteParticipant,
          user.canDeleteParticipant(participantId),
          "Can delete participant",
      )
      assertEquals(
          deleteParticipantProject,
          user.canDeleteParticipantProject(participantId, projectId),
          "Can delete participant project",
      )
      assertEquals(deleteSelf, user.canDeleteSelf(), "Can delete self")
      assertEquals(deleteSupportIssue, user.canDeleteSupportIssue(), "Can delete support issue")
      assertEquals(deleteUsers, user.canDeleteUsers(), "Can delete users")
      assertEquals(
          importGlobalSpeciesData,
          user.canImportGlobalSpeciesData(),
          "Can import global species data",
      )
      assertEquals(manageDeliverables, user.canManageDeliverables(), "Can manage deliverables")
      assertEquals(manageDisclaimers, user.canManageDisclaimers(), "Can manage disclaimers")
      assertEquals(manageInternalTags, user.canManageInternalTags(), "Can manage internal tags")
      assertEquals(manageModuleEvents, user.canManageModuleEvents(), "Can manage module events")
      assertEquals(
          manageModuleEventStatuses,
          user.canManageModuleEventStatuses(),
          "Can manage module event statuses",
      )
      assertEquals(manageModules, user.canManageModules(), "Can manage modules")
      assertEquals(manageNotifications, user.canManageNotifications(), "Can manage notifications")
      assertEquals(
          manageProjectReportConfigs,
          user.canManageProjectReportConfigs(),
          "Can manage project report configs",
      )
      assertEquals(
          notifyUpcomingReports,
          user.canNotifyUpcomingReports(),
          "Can notify upcoming reports",
      )
      assertEquals(
          proxyGeoServerGetRequests,
          user.canProxyGeoServerGetRequests(),
          "Can proxy GeoServer GET requests",
      )
      assertEquals(
          publishProjectProfileDetails,
          user.canPublishProjectProfileDetails(),
          "Can publish project profile details",
      )
      assertEquals(publishReports, user.canPublishReports(), "Can publish reports")
      assertEquals(
          readAllAcceleratorDetails,
          user.canReadAllAcceleratorDetails(),
          "Can read all accelerator details",
      )
      assertEquals(readAllDeliverables, user.canReadAllDeliverables(), "Can read all deliverables")
      assertEquals(readCohort, user.canReadCohort(cohortId), "Can read cohort")
      assertEquals(
          readCohortParticipants,
          user.canReadCohortParticipants(cohortId),
          "Can read cohort participants",
      )
      assertEquals(readCohorts, user.canReadCohorts(), "Can read all cohorts")
      assertEquals(
          readCurrentDisclaimer,
          user.canReadCurrentDisclaimer(),
          "Can read current disclaimer",
      )
      assertEquals(readGlobalRoles, user.canReadGlobalRoles(), "Can read global roles")
      assertEquals(readInternalTags, user.canReadInternalTags(), "Can read internal tags")
      assertEquals(
          readModuleEventParticipants,
          user.canReadModuleEventParticipants(),
          "Can read module event participants",
      )
      assertEquals(readParticipant, user.canReadParticipant(participantId), "Can read participant")
      assertEquals(
          readProjectReportConfigs,
          user.canReadProjectReportConfigs(),
          "Can read project report configs",
      )
      assertEquals(
          readPublishedProjects,
          user.canReadPublishedProjects(),
          "Can read published projects",
      )
      assertEquals(
          readReportInternalComments,
          user.canReadReportInternalComments(),
          "Can read report internal comment",
      )
      assertEquals(
          regenerateAllDeviceManagerTokens,
          user.canRegenerateAllDeviceManagerTokens(),
          "Can regenerate all device manager tokens",
      )
      assertEquals(reviewReports, user.canReviewReports(), "Can review reports")
      assertEquals(setTestClock, user.canSetTestClock(), "Can set test clock")
      assertEquals(updateAppVersions, user.canUpdateAppVersions(), "Can update app versions")
      assertEquals(updateCohort, user.canUpdateCohort(cohortId), "Can update cohort")
      assertEquals(
          updateDeviceTemplates,
          user.canUpdateDeviceTemplates(),
          "Can update device templates",
      )
      assertEquals(updateGlobalRoles, user.canUpdateGlobalRoles(), "Can update global roles")
      assertEquals(
          updateParticipant,
          user.canUpdateParticipant(participantId),
          "Can update participant",
      )
      assertEquals(
          updateSpecificGlobalRoles,
          user.canUpdateSpecificGlobalRoles(globalRoles),
          "Can update specific global roles",
      )

      hasCheckedGlobalPermissions = true
    }

    fun expect(
        vararg viabilityTestIds: ViabilityTestId,
        readViabilityTest: Boolean = false,
    ) {
      viabilityTestIds.forEach { viabilityTestId ->
        val idInDatabase = getDatabaseId(viabilityTestId)

        assertEquals(
            readViabilityTest,
            user.canReadViabilityTest(idInDatabase),
            "Can read viability test $viabilityTestId",
        )

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
        val idInDatabase = getDatabaseId(batchId)

        assertEquals(deleteBatch, user.canDeleteBatch(idInDatabase), "Can delete batch $batchId")
        assertEquals(readBatch, user.canReadBatch(idInDatabase), "Can read batch $batchId")
        assertEquals(updateBatch, user.canUpdateBatch(idInDatabase), "Can update batch $batchId")

        uncheckedBatches.remove(batchId)
      }
    }

    fun expect(
        vararg withdrawalIds: WithdrawalId,
        createWithdrawalPhoto: Boolean = false,
        readWithdrawal: Boolean = false,
    ) {
      withdrawalIds.forEach { withdrawalId ->
        val idInDatabase = getDatabaseId(withdrawalId)

        assertEquals(
            createWithdrawalPhoto,
            user.canCreateWithdrawalPhoto(idInDatabase),
            "Can create photo for withdrawal $withdrawalId",
        )
        assertEquals(
            readWithdrawal,
            user.canReadWithdrawal(idInDatabase),
            "Can read withdrawal $withdrawalId",
        )

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
        scheduleAdHocObservation: Boolean = false,
        scheduleObservation: Boolean = false,
        updatePlantingSite: Boolean = false,
        updatePlantingSiteProject: Boolean = false,
    ) {
      plantingSiteIds.forEach { plantingSiteId ->
        val idInDatabase = getDatabaseId(plantingSiteId)

        assertEquals(
            createDelivery,
            user.canCreateDelivery(idInDatabase),
            "Can create delivery at planting site $plantingSiteId",
        )
        assertEquals(
            createObservation,
            user.canCreateObservation(idInDatabase),
            "Can create observation of planting site $plantingSiteId",
        )
        assertEquals(
            deletePlantingSite,
            user.canDeletePlantingSite(idInDatabase),
            "Can delete planting site $plantingSiteId",
        )
        assertEquals(
            movePlantingSiteToAnyOrg,
            user.canMovePlantingSiteToAnyOrg(idInDatabase),
            "Can move planting site $plantingSiteId",
        )
        assertEquals(
            readPlantingSite,
            user.canReadPlantingSite(idInDatabase),
            "Can read planting site $plantingSiteId",
        )
        assertEquals(
            scheduleAdHocObservation,
            user.canScheduleAdHocObservation(idInDatabase),
            "Can schedule ad-hoc observation $plantingSiteId",
        )
        assertEquals(
            scheduleObservation,
            user.canScheduleObservation(idInDatabase),
            "Can schedule observation $plantingSiteId",
        )
        assertEquals(
            updatePlantingSite,
            user.canUpdatePlantingSite(idInDatabase),
            "Can update planting site $plantingSiteId",
        )
        assertEquals(
            updatePlantingSiteProject,
            user.canUpdatePlantingSiteProject(idInDatabase),
            "Can update project for planting site $plantingSiteId",
        )

        uncheckedPlantingSites.remove(plantingSiteId)
      }
    }

    fun expect(
        vararg plantingSubzoneIds: PlantingSubzoneId,
        readPlantingSubzone: Boolean = false,
        updatePlantingSubzoneCompleted: Boolean = false,
    ) {
      plantingSubzoneIds.forEach { plantingSubzoneId ->
        val idInDatabase = getDatabaseId(plantingSubzoneId)

        assertEquals(
            readPlantingSubzone,
            user.canReadPlantingSubzone(idInDatabase),
            "Can read planting subzone $plantingSubzoneId",
        )
        assertEquals(
            updatePlantingSubzoneCompleted,
            user.canUpdatePlantingSubzoneCompleted(idInDatabase),
            "Can update planting completed for subzone $plantingSubzoneId",
        )

        uncheckedPlantingSubzones.remove(plantingSubzoneId)
      }
    }

    fun expect(
        vararg plantingZoneIds: PlantingZoneId,
        readPlantingZone: Boolean = false,
        updatePlantingZone: Boolean = false,
        updateT0: Boolean = false,
    ) {
      plantingZoneIds.forEach { plantingZoneId ->
        val idInDatabase = getDatabaseId(plantingZoneId)

        assertEquals(
            readPlantingZone,
            user.canReadPlantingZone(idInDatabase),
            "Can read planting zone $plantingZoneId",
        )
        assertEquals(
            updatePlantingZone,
            user.canUpdatePlantingZone(idInDatabase),
            "Can update planting zone $plantingZoneId",
        )
        assertEquals(
            updateT0,
            user.canUpdateT0(idInDatabase),
            "Can update T0 for planting zone $plantingZoneId",
        )

        uncheckedPlantingZones.remove(plantingZoneId)
      }
    }

    fun expect(
        vararg monitoringPlotIds: MonitoringPlotId,
        readMonitoringPlot: Boolean = false,
        updateMonitoringPlot: Boolean = false,
        updateT0: Boolean = false,
    ) {
      monitoringPlotIds.forEach { monitoringPlotId ->
        val idInDatabase = getDatabaseId(monitoringPlotId)

        assertEquals(
            readMonitoringPlot,
            user.canReadMonitoringPlot(idInDatabase),
            "Can read monitoring plot $monitoringPlotId",
        )
        assertEquals(
            updateMonitoringPlot,
            user.canUpdateMonitoringPlot(idInDatabase),
            "Can update monitoring plot $monitoringPlotId",
        )
        assertEquals(
            updateT0,
            user.canUpdateT0(idInDatabase),
            "Can update T0 for monitoring plot $monitoringPlotId",
        )

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
        val idInDatabase = getDatabaseId(draftPlantingSiteId)

        assertEquals(
            deleteDraftPlantingSite,
            user.canDeleteDraftPlantingSite(idInDatabase),
            "Can delete draft planting site $draftPlantingSiteId",
        )
        assertEquals(
            readDraftPlantingSite,
            user.canReadDraftPlantingSite(idInDatabase),
            "Can read draft planting site $draftPlantingSiteId",
        )
        assertEquals(
            updateDraftPlantingSite,
            user.canUpdateDraftPlantingSite(idInDatabase),
            "Can update draft planting site $draftPlantingSiteId",
        )

        uncheckedDraftPlantingSites.remove(draftPlantingSiteId)
      }
    }

    fun expect(
        vararg deliveryIds: DeliveryId,
        readDelivery: Boolean = false,
        updateDelivery: Boolean = false,
    ) {
      deliveryIds.forEach { deliveryId ->
        val idInDatabase = getDatabaseId(deliveryId)

        assertEquals(
            readDelivery,
            user.canReadDelivery(idInDatabase),
            "Can read delivery $deliveryId",
        )
        assertEquals(
            updateDelivery,
            user.canUpdateDelivery(idInDatabase),
            "Can update delivery $deliveryId",
        )

        uncheckedDeliveries.remove(deliveryId)
      }
    }

    fun expect(
        vararg plantingIds: PlantingId,
        readPlanting: Boolean = false,
    ) {
      plantingIds.forEach { plantingId ->
        val idInDatabase = getDatabaseId(plantingId)

        assertEquals(
            readPlanting,
            user.canReadPlanting(idInDatabase),
            "Can read planting $plantingId",
        )

        uncheckedPlantings.remove(plantingId)
      }
    }

    fun expect(
        vararg reportIds: SeedFundReportId,
        deleteReport: Boolean = false,
        readReport: Boolean = false,
        updateReport: Boolean = false,
    ) {
      reportIds.forEach { reportId ->
        val idInDatabase = getDatabaseId(reportId)

        assertEquals(
            deleteReport,
            user.canDeleteSeedFundReport(idInDatabase),
            "Can delete report $reportId",
        )
        assertEquals(
            readReport,
            user.canReadSeedFundReport(idInDatabase),
            "Can read report $reportId",
        )
        assertEquals(
            updateReport,
            user.canUpdateSeedFundReport(idInDatabase),
            "Can update report $reportId",
        )

        uncheckedSeedFundReports.remove(reportId)
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
        val idInDatabase = getDatabaseId(observationId)

        assertEquals(
            manageObservation,
            user.canManageObservation(idInDatabase),
            "Can manage observation $observationId",
        )
        assertEquals(
            readObservation,
            user.canReadObservation(idInDatabase),
            "Can read observation $observationId",
        )
        assertEquals(
            replaceObservationPlot,
            user.canReplaceObservationPlot(idInDatabase),
            "Can replace plot in observation $observationId",
        )
        assertEquals(
            rescheduleObservation,
            user.canRescheduleObservation(idInDatabase),
            "Can reschedule observation $observationId",
        )
        assertEquals(
            updateObservation,
            user.canUpdateObservation(idInDatabase),
            "Can update observation $observationId",
        )

        uncheckedObservations.remove(observationId)
      }
    }

    fun expect(
        vararg projectIds: ProjectId,
        createActivity: Boolean = false,
        createApplication: Boolean = false,
        createParticipantProjectSpecies: Boolean = false,
        createSubmission: Boolean = false,
        deleteProject: Boolean = false,
        listActivities: Boolean = false,
        readDefaultVoters: Boolean = false,
        readInternalVariableWorkflowDetails: Boolean = false,
        readProject: Boolean = false,
        readProjectAcceleratorDetails: Boolean = false,
        readProjectDeliverables: Boolean = false,
        readProjectFunderDetails: Boolean = false,
        readProjectModules: Boolean = false,
        readProjectScores: Boolean = false,
        readProjectVotes: Boolean = false,
        readPublishedReports: Boolean = false,
        updateDefaultVoters: Boolean = false,
        updateInternalVariableWorkflowDetails: Boolean = false,
        updateProject: Boolean = false,
        updateProjectAcceleratorDetails: Boolean = false,
        updateProjectDocumentSettings: Boolean = false,
        updateProjectInternalUsers: Boolean = false,
        updateProjectReports: Boolean = false,
        updateProjectScores: Boolean = false,
        updateProjectVotes: Boolean = false,
        updateSubmissionStatus: Boolean = false,
    ) {
      projectIds.forEach { projectId ->
        val idInDatabase = getDatabaseId(projectId)

        assertEquals(
            createActivity,
            user.canCreateActivity(idInDatabase),
            "Can create activity in project $projectId",
        )
        assertEquals(
            createApplication,
            user.canCreateApplication(idInDatabase),
            "Can create application in project $projectId",
        )
        assertEquals(
            createSubmission,
            user.canCreateSubmission(idInDatabase),
            "Can create submission for project $projectId",
        )
        assertEquals(
            createParticipantProjectSpecies,
            user.canCreateParticipantProjectSpecies(idInDatabase),
            "Can create participant project species for project $projectId",
        )
        assertEquals(
            deleteProject,
            user.canDeleteProject(idInDatabase),
            "Can delete project $projectId",
        )
        assertEquals(
            listActivities,
            user.canListActivities(idInDatabase),
            "Can list activities for project $projectId",
        )
        assertEquals(readDefaultVoters, user.canReadDefaultVoters(), "Can read default voters")
        assertEquals(
            readInternalVariableWorkflowDetails,
            user.canReadInternalVariableWorkflowDetails(idInDatabase),
            "Can read variable owners for project $projectId",
        )
        assertEquals(
            readProjectModules,
            user.canReadProjectModules(idInDatabase),
            "Can read project modules",
        )
        assertEquals(readProject, user.canReadProject(idInDatabase), "Can read project $projectId")
        assertEquals(
            readProjectAcceleratorDetails,
            user.canReadProjectAcceleratorDetails(idInDatabase),
            "Can read accelerator details for project $projectId",
        )
        assertEquals(
            readProjectDeliverables,
            user.canReadProjectDeliverables(idInDatabase),
            "Can read deliverables for project $projectId",
        )
        assertEquals(
            readProjectFunderDetails,
            user.canReadProjectFunderDetails(idInDatabase),
            "Can read project funder details for project $projectId",
        )
        assertEquals(
            readProjectScores,
            user.canReadProjectScores(idInDatabase),
            "Can read scores for project $projectId",
        )
        assertEquals(
            readProjectVotes,
            user.canReadProjectVotes(idInDatabase),
            "Can read votes for project $projectId",
        )
        assertEquals(
            readPublishedReports,
            user.canReadPublishedReports(idInDatabase),
            "Can read published reports for project $projectId",
        )
        assertEquals(
            updateDefaultVoters,
            user.canUpdateDefaultVoters(),
            "Can update default voters",
        )
        assertEquals(
            updateInternalVariableWorkflowDetails,
            user.canUpdateInternalVariableWorkflowDetails(idInDatabase),
            "Can update variable owners for project $projectId",
        )
        assertEquals(
            updateProject,
            user.canUpdateProject(idInDatabase),
            "Can update project $projectId",
        )
        assertEquals(
            updateProjectAcceleratorDetails,
            user.canUpdateProjectAcceleratorDetails(idInDatabase),
            "Can update accelerator details for project $projectId",
        )
        assertEquals(
            updateProjectDocumentSettings,
            user.canUpdateProjectDocumentSettings(idInDatabase),
            "Can update project document settings for project $projectId",
        )
        assertEquals(
            updateProjectInternalUsers,
            user.canUpdateProjectInternalUsers(idInDatabase),
            "Can update internal project users for project $projectId",
        )
        assertEquals(
            updateProjectReports,
            user.canUpdateProjectReports(idInDatabase),
            "Can update reports for project $projectId",
        )
        assertEquals(
            updateProjectScores,
            user.canUpdateProjectScores(idInDatabase),
            "Can update scores for project $projectId",
        )
        assertEquals(
            updateProjectVotes,
            user.canUpdateProjectVotes(idInDatabase),
            "Can update votes for project $projectId",
        )

        assertEquals(
            updateSubmissionStatus,
            user.canUpdateSubmissionStatus(deliverableIds.first(), projectId),
            "Can update submission status for project $projectId",
        )

        uncheckedProjects.remove(projectId)
      }
    }

    fun expect(
        vararg eventIds: EventId,
        readModuleEvent: Boolean = false,
    ) {
      eventIds.forEach { eventId ->
        val idInDatabase = getDatabaseId(eventId)

        assertEquals(
            readModuleEvent,
            user.canReadModuleEvent(idInDatabase),
            "Can read module event $eventId",
        )

        uncheckedModuleEvents.remove(eventId)
      }
    }

    fun expect(
        vararg moduleIds: ModuleId,
        readModule: Boolean = false,
        readModuleDetails: Boolean = false,
    ) {
      moduleIds.forEach { moduleId ->
        val idInDatabase = getDatabaseId(moduleId)

        assertEquals(readModule, user.canReadModule(idInDatabase), "Can read module $moduleId")
        assertEquals(
            readModuleDetails,
            user.canReadModuleDetails(idInDatabase),
            "Can read module details $moduleId",
        )

        uncheckedModules.remove(moduleId)
      }
    }

    fun expect(
        vararg participantProjectSpeciesIds: ParticipantProjectSpeciesId,
        deleteParticipantProjectSpecies: Boolean = false,
        readParticipantProjectSpecies: Boolean = false,
        updateParticipantProjectSpecies: Boolean = false,
    ) {
      participantProjectSpeciesIds.forEach { participantProjectSpeciesId ->
        val idInDatabase = getDatabaseId(participantProjectSpeciesId)

        assertEquals(
            deleteParticipantProjectSpecies,
            user.canDeleteParticipantProjectSpecies(idInDatabase),
            "Can delete participant project species $participantProjectSpeciesId",
        )

        assertEquals(
            readParticipantProjectSpecies,
            user.canReadParticipantProjectSpecies(idInDatabase),
            "Can read participant project species $participantProjectSpeciesId",
        )

        assertEquals(
            updateParticipantProjectSpecies,
            user.canUpdateParticipantProjectSpecies(idInDatabase),
            "Can update participant project species $participantProjectSpeciesId",
        )

        uncheckedParticipantProjectSpecies.remove(participantProjectSpeciesId)
      }
    }

    fun expect(
        vararg submissionIds: SubmissionId,
        readSubmission: Boolean = false,
    ) {
      submissionIds.forEach { submissionId ->
        val idInDatabase = getDatabaseId(submissionId)

        assertEquals(
            readSubmission,
            user.canReadSubmission(idInDatabase),
            "Can read submission $submissionId",
        )

        uncheckedSubmissions.remove(submissionId)
      }
    }

    fun expect(
        vararg submissionDocumentIds: SubmissionDocumentId,
        readSubmissionDocument: Boolean = false,
    ) {
      submissionDocumentIds.forEach { documentId ->
        assertEquals(
            readSubmissionDocument,
            user.canReadSubmissionDocument(documentId),
            "Can read submission document $documentId",
        )

        uncheckedSubmissionDocuments.remove(documentId)
      }
    }

    fun expect(
        vararg documentIds: DocumentId,
        createSavedVersion: Boolean = false,
        readDocument: Boolean = false,
        updateDocument: Boolean = false,
    ) {
      documentIds.forEach { documentId ->
        assertEquals(
            createSavedVersion,
            user.canCreateSavedVersion(documentId),
            "Can create saved version of document $documentId",
        )
        assertEquals(
            readDocument,
            user.canReadDocument(documentId),
            "Can read document $documentId",
        )
        assertEquals(
            updateDocument,
            user.canUpdateDocument(documentId),
            "Can update document $documentId",
        )

        uncheckedDocuments.remove(documentId)
      }
    }

    fun expect(
        vararg userIds: UserId,
        createEntityWithOwner: Boolean = false,
        createNotifications: Boolean = false,
        deleteFunder: Boolean = false,
        readUser: Boolean = false,
        readUserInternalInterests: Boolean = false,
        updateUserInternalInterests: Boolean = false,
    ) {
      userIds.forEach { userId ->
        assertEquals(
            createEntityWithOwner,
            user.canCreateEntityWithOwner(userId),
            "Can create entity with owner $userId",
        )
        assertEquals(
            createNotifications,
            user.canCreateNotification(userId),
            "Can create notifications for $userId",
        )
        assertEquals(deleteFunder, user.canDeleteFunder(userId), "Can delete funder $userId")
        assertEquals(readUser, user.canReadUser(userId), "Can read user $userId")
        assertEquals(
            readUserInternalInterests,
            user.canReadUserInternalInterests(userId),
            "Can read deliverable categories for user $userId",
        )
        assertEquals(
            updateUserInternalInterests,
            user.canUpdateUserInternalInterests(userId),
            "Can update deliverable categories for user $userId",
        )

        uncheckedUsers.remove(userId)
      }
    }

    fun expect(
        vararg applicationIds: ApplicationId,
        readApplication: Boolean = false,
        reviewApplication: Boolean = false,
        updateApplicationBoundary: Boolean = false,
        updateApplicationCountry: Boolean = false,
        updateApplicationSubmissionStatus: Boolean = false,
    ) {
      applicationIds
          .filter { it in uncheckedApplications }
          .forEach { applicationId ->
            val idInDatabase = getDatabaseId(applicationId)

            assertEquals(
                readApplication,
                user.canReadApplication(idInDatabase),
                "Can read application $applicationId",
            )
            assertEquals(
                reviewApplication,
                user.canReviewApplication(idInDatabase),
                "Can review application $applicationId",
            )
            assertEquals(
                updateApplicationBoundary,
                user.canUpdateApplicationBoundary(idInDatabase),
                "Can update boundary for application $applicationId",
            )
            assertEquals(
                updateApplicationCountry,
                user.canUpdateApplicationCountry(idInDatabase),
                "Can update country for application $applicationId",
            )
            assertEquals(
                updateApplicationSubmissionStatus,
                user.canUpdateApplicationSubmissionStatus(idInDatabase),
                "Can update submission status of application $applicationId",
            )

            uncheckedApplications.remove(applicationId)
          }
    }

    fun expect(
        vararg reportIds: ReportId,
        readPublishedReport: Boolean = false,
        readReport: Boolean = false,
        updateReport: Boolean = false,
    ) {
      reportIds
          .filter { it in uncheckedReports }
          .forEach { reportId ->
            val idInDatabase = getDatabaseId(reportId)

            assertEquals(
                readPublishedReport,
                user.canReadPublishedReport(idInDatabase),
                "Can read published report $reportId",
            )
            assertEquals(readReport, user.canReadReport(idInDatabase), "Can read report $reportId")
            assertEquals(
                updateReport,
                user.canUpdateReport(idInDatabase),
                "Can update report $reportId",
            )

            uncheckedReports.remove(reportId)
          }
    }

    fun expect(
        vararg fundingEntityIds: FundingEntityId,
        updateFundingEntityUsers: Boolean = false,
        listFundingEntityUsers: Boolean = false,
        readFundingEntity: Boolean = false,
    ) {
      fundingEntityIds
          .filter { it in uncheckedFundingEntities }
          .forEach { entityId ->
            val idInDatabase = getDatabaseId(entityId)

            assertEquals(
                readFundingEntity,
                user.canReadFundingEntity(idInDatabase),
                "Can read funding entity $entityId",
            )
            assertEquals(
                listFundingEntityUsers,
                user.canListFundingEntityUsers(idInDatabase),
                "Can list users for funding entity $entityId",
            )
            assertEquals(
                updateFundingEntityUsers,
                user.canUpdateFundingEntityUsers(idInDatabase),
                "Can update funding entity users for funding entity $entityId",
            )

            uncheckedFundingEntities.remove(entityId)
          }
    }

    fun expect(
        vararg activityIds: ActivityId,
        deleteActivity: Boolean = false,
        manageActivity: Boolean = false,
        readActivity: Boolean = false,
        updateActivity: Boolean = false,
    ) {
      activityIds
          .filter { it in uncheckedActivities }
          .forEach { entityId ->
            val idInDatabase = getDatabaseId(entityId)

            assertEquals(
                deleteActivity,
                user.canDeleteActivity(idInDatabase),
                "Can delete activity $entityId",
            )
            assertEquals(
                manageActivity,
                user.canManageActivity(idInDatabase),
                "Can manage activity $entityId",
            )
            assertEquals(
                readActivity,
                user.canReadActivity(idInDatabase),
                "Can read activity $entityId",
            )
            assertEquals(
                updateActivity,
                user.canUpdateActivity(idInDatabase),
                "Can update activity $entityId",
            )

            uncheckedActivities.remove(entityId)
          }
    }

    fun andNothingElse() {
      expect(*uncheckedAccessions.toTypedArray())
      expect(*uncheckedActivities.toTypedArray())
      expect(*uncheckedApplications.toTypedArray())
      expect(*uncheckedAutomations.toTypedArray())
      expect(*uncheckedBatches.toTypedArray())
      expect(*uncheckedDeliveries.toTypedArray())
      expect(*uncheckedDeviceManagers.toTypedArray())
      expect(*uncheckedDevices.toTypedArray())
      expect(*uncheckedDocuments.toTypedArray())
      expect(*uncheckedDraftPlantingSites.toTypedArray())
      expect(*uncheckedFacilities.toTypedArray())
      expect(*uncheckedFundingEntities.toTypedArray())
      expect(*uncheckedModuleEvents.toTypedArray())
      expect(*uncheckedModules.toTypedArray())
      expect(*uncheckedMonitoringPlots.toTypedArray())
      expect(*uncheckedObservations.toTypedArray())
      expect(*uncheckedOrgs.toTypedArray())
      expect(*uncheckedParticipantProjectSpecies.toTypedArray())
      expect(*uncheckedPlantings.toTypedArray())
      expect(*uncheckedPlantingSites.toTypedArray())
      expect(*uncheckedPlantingSubzones.toTypedArray())
      expect(*uncheckedPlantingZones.toTypedArray())
      expect(*uncheckedProjects.toTypedArray())
      expect(*uncheckedReports.toTypedArray())
      expect(*uncheckedSeedFundReports.toTypedArray())
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
