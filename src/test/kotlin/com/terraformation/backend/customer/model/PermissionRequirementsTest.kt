package com.terraformation.backend.customer.model

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.db.ActivityNotFoundException
import com.terraformation.backend.accelerator.db.ApplicationNotFoundException
import com.terraformation.backend.accelerator.db.CohortNotFoundException
import com.terraformation.backend.accelerator.db.ModuleNotFoundException
import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesNotFoundException
import com.terraformation.backend.accelerator.db.SubmissionDocumentNotFoundException
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.DeviceManagerNotFoundException
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.EventNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.NotificationNotFoundException
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.SeedFundReportNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.SubLocationNotFoundException
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.ViabilityTestNotFoundException
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.funder.db.FundingEntityNotFoundException
import com.terraformation.backend.nursery.db.BatchNotFoundException
import com.terraformation.backend.nursery.db.WithdrawalNotFoundException
import com.terraformation.backend.tracking.db.DeliveryNotFoundException
import com.terraformation.backend.tracking.db.DraftPlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.PlantingNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlotNotFoundException
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.db.SubstratumNotFoundException
import io.mockk.CapturingSlot
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import kotlin.reflect.KClass
import kotlin.reflect.typeOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

/**
 * Tests the exception-throwing logic in [PermissionRequirements].
 *
 * For a read permission on a single object ID, where there should be an [EntityNotFoundException]
 * if the user doesn't have the permission, you can call [testRead] and pass in an ID that is
 * created by the [readableId] method.
 *
 * For a write permission on a single object ID, where there should be an [EntityNotFoundException]
 * if the user doesn't have permission to read the object and an [AccessDeniedException] if they
 * don't have write permission, the test will look like
 *
 * ```
 * allow { methodUnderTest(objectId) } ifUser { canDoWhatever(objectId) }
 * ```
 *
 * The general approach to these tests, which is implemented by [testRead] and [ifUser] for simple
 * scenarios and explicitly in test methods for more complex scenarios, is
 * 1. Assert the exception that's thrown if the exception-checking method is called when the user
 *    has none of the relevant permissions at all
 * 2. For each additional exception that can be thrown (if any), grant the user a new permission and
 *    assert that the alternate exception is thrown
 * 3. Grant the final permission that allows the check to succeed
 * 4. Call the permission checking method again; the test will fail if it throws an exception
 */
internal class PermissionRequirementsTest : RunsAsUser {
  override val user: TerrawareUser = mockk(relaxed = true)
  private val requirements = PermissionRequirements(user)

  /**
   * List of callback functions that will test that an exception is thrown if the user doesn't have
   * read permission on an object, then will grant permission. This is populated implicitly when a
   * test method accesses one of the IDs that's lazy-created by [readableId].
   */
  private val readChecks = mutableListOf<(() -> Unit) -> Unit>()

  // Lazy-instantiated object IDs with implicit testing of read permission handling. When you access
  // one of these IDs for the first time, an entry is added to [readChecks].

  private val accessionId: AccessionId by
      readableId(AccessionNotFoundException::class) { canReadAccession(it) }
  private val activityId: ActivityId by
      readableId(ActivityNotFoundException::class) { canReadActivity(it) }
  private val applicationId: ApplicationId by
      readableId(ApplicationNotFoundException::class) { canReadApplication(it) }
  private val automationId: AutomationId by
      readableId(AutomationNotFoundException::class) { canReadAutomation(it) }
  private val batchId: BatchId by readableId(BatchNotFoundException::class) { canReadBatch(it) }
  private val cohortId: CohortId by readableId(CohortNotFoundException::class) { canReadCohort(it) }
  private val deliverableId: DeliverableId = DeliverableId(1)
  private val deliveryId: DeliveryId by
      readableId(DeliveryNotFoundException::class) { canReadDelivery(it) }
  private val deviceId: DeviceId by readableId(DeviceNotFoundException::class) { canReadDevice(it) }
  private val deviceManagerId: DeviceManagerId by
      readableId(DeviceManagerNotFoundException::class) { canReadDeviceManager(it) }
  private val draftPlantingSiteId: DraftPlantingSiteId by
      readableId(DraftPlantingSiteNotFoundException::class) { canReadDraftPlantingSite(it) }
  private val eventId: EventId by
      readableId(EventNotFoundException::class) { canReadModuleEvent(it) }
  private val facilityId: FacilityId by
      readableId(FacilityNotFoundException::class) { canReadFacility(it) }
  private val funderId: UserId by readableId(AccessDeniedException::class) { canReadUser(it) }
  private val fundingEntityId: FundingEntityId by
      readableId(FundingEntityNotFoundException::class) { canReadFundingEntity(it) }
  private val moduleId: ModuleId by readableId(ModuleNotFoundException::class) { canReadModule(it) }
  private val monitoringPlotId: MonitoringPlotId by
      readableId(PlotNotFoundException::class) { canReadMonitoringPlot(it) }
  private val notificationUserId = UserId(2)
  private val notificationId: NotificationId by
      readableId(NotificationNotFoundException::class) { canReadNotification(it) }
  private val observationId: ObservationId by
      readableId(ObservationNotFoundException::class) { canReadObservation(it) }
  private val organizationId: OrganizationId by
      readableId(OrganizationNotFoundException::class) { canReadOrganization(it) }
  private val otherUserId: UserId by readableId(UserNotFoundException::class) { canReadUser(it) }
  private val participantProjectSpeciesId: ParticipantProjectSpeciesId by
      readableId(ParticipantProjectSpeciesNotFoundException::class) {
        canReadParticipantProjectSpecies(it)
      }
  private val plantingId: PlantingId by
      readableId(PlantingNotFoundException::class) { canReadPlanting(it) }
  private val plantingSiteId: PlantingSiteId by
      readableId(PlantingSiteNotFoundException::class) { canReadPlantingSite(it) }
  private val projectId: ProjectId by
      readableId(ProjectNotFoundException::class) { canReadProject(it) }
  private val publishedActivityId: ActivityId by
      readableId(ActivityNotFoundException::class) { canReadPublishedActivity(it) }
  private val reportId: ReportId by readableId(ReportNotFoundException::class) { canReadReport(it) }
  private val seedFundReportId: SeedFundReportId by
      readableId(SeedFundReportNotFoundException::class) { canReadSeedFundReport(it) }
  private val role = Role.Contributor
  private val speciesId: SpeciesId by
      readableId(SpeciesNotFoundException::class) { canReadSpecies(it) }
  private val stratumId: StratumId by
      readableId(StratumNotFoundException::class) { canReadStratum(it) }
  private val subLocationId: SubLocationId by
      readableId(SubLocationNotFoundException::class) { canReadSubLocation(it) }
  private val submissionDocumentId: SubmissionDocumentId by
      readableId(SubmissionDocumentNotFoundException::class) { canReadSubmissionDocument(it) }
  private val substratumId: SubstratumId by
      readableId(SubstratumNotFoundException::class) { canReadSubstratum(it) }
  private val uploadId: UploadId by readableId(UploadNotFoundException::class) { canReadUpload(it) }
  private val userId = UserId(1)
  private val viabilityTestId: ViabilityTestId by
      readableId(ViabilityTestNotFoundException::class) { canReadViabilityTest(it) }
  private val withdrawalId: WithdrawalId by
      readableId(WithdrawalNotFoundException::class) { canReadWithdrawal(it) }

  /**
   * Grants permission to perform a particular operation. This is a simple wrapper around a MockK
   * `every { user.canX() } returns true` call, but with a more concise syntax and a more meaningful
   * name.
   */
  private fun grant(stubBlock: MockKMatcherScope.() -> Boolean) {
    every(stubBlock) returns true
  }

  /**
   * Lazy initialization wrapper that returns an ID. Adds a function to [readChecks] that verifies
   * that an exception of the correct class is thrown by the method under test if the user doesn't
   * have permission to read the ID, then grants read permission.
   */
  private inline fun <reified T> readableId(
      exceptionClass: KClass<out Exception>,
      crossinline grantBlock: TerrawareUser.(T) -> Boolean,
  ): Lazy<T> = lazy {
    val id =
        T::class
            .constructors
            .first { it.parameters.size == 1 && it.parameters[0].type == typeOf<Long>() }
            .call(1L)
    readChecks.add { operation ->
      assertThrows(exceptionClass.java, operation)
      grant { grantBlock.invoke(user, id) }
    }
    id
  }

  private infix fun (() -> Unit).ifUser(grantBlock: TerrawareUser.() -> Boolean) {
    // Evaluate any lazy arguments, which will have the side effect of adding read checks; we
    // don't care about the specific exception here since the read checks will test for them.
    assertThrows<Exception>(this)

    readChecks.forEach { check -> check(this) }

    assertThrows<AccessDeniedException>(this)

    grant { grantBlock.invoke(user) }
    assertDoesNotThrow(this)
  }

  /**
   * Returns a function that invokes the supplied lambda on the [requirements] object. This should
   * almost always be followed by [ifUser], which will perform the actual testing.
   *
   * This is syntactic sugar for readability and brevity; the following two lines are equivalent:
   * ```
   * allow { foo() }
   * { requirements.foo() }
   * ```
   */
  private fun allow(func: PermissionRequirements.() -> Unit): () -> Unit {
    return { func.invoke(requirements) }
  }

  /**
   * Calls the supplied lambda on the [requirements] object, including any read checks that are
   * required for any IDs that are referenced in the lambda.
   */
  private fun testRead(func: PermissionRequirements.() -> Unit) {
    val funcWithReceiver = { func.invoke(requirements) }

    // Evaluate any lazy arguments, which will have the side effect of adding read checks; we
    // don't care about the specific exception here since the read checks will test for them.
    assertThrows<Exception>(funcWithReceiver)

    readChecks.forEach { check -> check(funcWithReceiver) }

    assertDoesNotThrow(funcWithReceiver)
  }

  @BeforeEach
  fun setUp() {
    val funcSlot = CapturingSlot<() -> Any>()

    every { user.recordPermissionChecks(capture(funcSlot)) } answers { funcSlot.captured() }
  }

  @Test
  fun acceptCurrentDisclaimer() =
      allow { acceptCurrentDisclaimer() } ifUser { canAcceptCurrentDisclaimer() }

  @Test
  fun addCohortProject() {
    assertThrows<CohortNotFoundException> { requirements.addCohortProject(cohortId, projectId) }

    grant { user.canReadCohort(cohortId) }
    assertThrows<ProjectNotFoundException> { requirements.addCohortProject(cohortId, projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.addCohortProject(cohortId, projectId) }

    grant { user.canAddCohortProject(cohortId, projectId) }
    requirements.addCohortProject(cohortId, projectId)
  }

  @Test
  fun addOrganizationUser() =
      allow { addOrganizationUser(organizationId) } ifUser
          {
            canAddOrganizationUser(organizationId)
          }

  @Test
  fun updateProjectInternalUsers() {
    assertThrows<ProjectNotFoundException> { requirements.updateProjectInternalUsers(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.updateProjectInternalUsers(projectId) }

    grant { user.canUpdateProjectInternalUsers(projectId) }
    requirements.updateProjectInternalUsers(projectId)
  }

  @Test
  fun addTerraformationContact() =
      allow { addTerraformationContact(organizationId) } ifUser
          {
            canAddTerraformationContact(organizationId)
          }

  @Test
  fun removeTerraformationContact() =
      allow { removeTerraformationContact(organizationId) } ifUser
          {
            canRemoveTerraformationContact(organizationId)
          }

  @Test fun manageInternalTags() = allow { manageInternalTags() } ifUser { canManageInternalTags() }

  @Test
  fun manageNotifications() = allow { manageNotifications() } ifUser { canManageNotifications() }

  @Test
  fun createAccession() =
      allow { createAccession(facilityId) } ifUser { canCreateAccession(facilityId) }

  @Test
  fun createActivity() = allow { createActivity(projectId) } ifUser { canCreateActivity(projectId) }

  @Test
  fun createApiKey() =
      allow { createApiKey(organizationId) } ifUser { canCreateApiKey(organizationId) }

  @Test
  fun createApplication() =
      allow { createApplication(projectId) } ifUser { canCreateApplication(projectId) }

  @Test
  fun createAutomation() =
      allow { createAutomation(facilityId) } ifUser { canCreateAutomation(facilityId) }

  @Test fun createBatch() = allow { createBatch(facilityId) } ifUser { canCreateBatch(facilityId) }

  @Test fun createCohort() = allow { createCohort() } ifUser { canCreateCohort() }

  @Test fun createCohortModule() = allow { createCohortModule() } ifUser { canCreateCohortModule() }

  @Test
  fun createDelivery() =
      allow { createDelivery(plantingSiteId) } ifUser { canCreateDelivery(plantingSiteId) }

  @Test
  fun createDevice() = allow { createDevice(facilityId) } ifUser { canCreateDevice(facilityId) }

  @Test
  fun createDeviceManager() = allow { createDeviceManager() } ifUser { canCreateDeviceManager() }

  @Test
  fun createDraftPlantingSite() =
      allow { createDraftPlantingSite(organizationId) } ifUser
          {
            canCreateDraftPlantingSite(organizationId)
          }

  @Test
  fun createEntityWithOwner() =
      allow { createEntityWithOwner(otherUserId) } ifUser { canCreateEntityWithOwner(otherUserId) }

  @Test
  fun createFacility() =
      allow { createFacility(organizationId) } ifUser { canCreateFacility(organizationId) }

  @Test
  fun createFundingEntities() =
      allow { createFundingEntities() } ifUser { canCreateFundingEntities() }

  @Test
  fun createNotification() =
      allow { createNotification(notificationUserId) } ifUser
          {
            canCreateNotification(notificationUserId)
          }

  @Test
  fun createObservation() =
      allow { createObservation(plantingSiteId) } ifUser { canCreateObservation(plantingSiteId) }

  @Test
  fun createParticipantProjectSpecies() {
    assertThrows<AccessDeniedException> { requirements.createParticipantProjectSpecies(projectId) }

    grant { user.canCreateParticipantProjectSpecies(projectId) }
    requirements.createParticipantProjectSpecies(projectId)
  }

  @Test
  fun createPlantingSite() =
      allow { createPlantingSite(organizationId) } ifUser { canCreatePlantingSite(organizationId) }

  @Test
  fun createProject() =
      allow { createProject(organizationId) } ifUser { canCreateProject(organizationId) }

  @Test
  fun createReport() =
      allow { createSeedFundReport(organizationId) } ifUser
          {
            canCreateSeedFundReport(organizationId)
          }

  @Test
  fun createSpecies() =
      allow { createSpecies(organizationId) } ifUser { canCreateSpecies(organizationId) }

  @Test
  fun createSubLocation() =
      allow { createSubLocation(facilityId) } ifUser { canCreateSubLocation(facilityId) }

  @Test
  fun createSubmission() =
      allow { createSubmission(projectId) } ifUser { canCreateSubmission(projectId) }

  @Test
  fun createTimeseries() =
      allow { createTimeseries(deviceId) } ifUser { canCreateTimeseries(deviceId) }

  @Test
  fun createWithdrawalPhoto() =
      allow { createWithdrawalPhoto(withdrawalId) } ifUser
          {
            canCreateWithdrawalPhoto(withdrawalId)
          }

  @Test
  fun deleteAccession() =
      allow { deleteAccession(accessionId) } ifUser { canDeleteAccession(accessionId) }

  @Test
  fun deleteActivity() =
      allow { deleteActivity(activityId) } ifUser { canDeleteActivity(activityId) }

  @Test
  fun deleteAutomation() =
      allow { deleteAutomation(automationId) } ifUser { canDeleteAutomation(automationId) }

  @Test fun deleteBatch() = allow { deleteBatch(batchId) } ifUser { canDeleteBatch(batchId) }

  @Test fun deleteCohort() = allow { deleteCohort(cohortId) } ifUser { canDeleteCohort(cohortId) }

  @Test
  fun deleteCohortProject() =
      allow { deleteCohortProject(cohortId, projectId) } ifUser
          {
            canDeleteCohortProject(cohortId, projectId)
          }

  @Test
  fun deleteDraftPlantingSite() =
      allow { deleteDraftPlantingSite(draftPlantingSiteId) } ifUser
          {
            canDeleteDraftPlantingSite(draftPlantingSiteId)
          }

  @Test fun deleteFunder() = allow { deleteFunder(funderId) } ifUser { canDeleteFunder(funderId) }

  @Test
  fun deleteFundingEntities() =
      allow { deleteFundingEntities() } ifUser { canDeleteFundingEntities() }

  @Test
  fun deleteOrganization() =
      allow { deleteOrganization(organizationId) } ifUser { canDeleteOrganization(organizationId) }

  @Test
  fun deleteParticipantProjectSpecies() =
      allow { deleteParticipantProjectSpecies(participantProjectSpeciesId) } ifUser
          {
            canDeleteParticipantProjectSpecies(participantProjectSpeciesId)
          }

  @Test
  fun deletePlantingSite() =
      allow { deletePlantingSite(plantingSiteId) } ifUser { canDeletePlantingSite(plantingSiteId) }

  @Test
  fun deleteProject() = allow { deleteProject(projectId) } ifUser { canDeleteProject(projectId) }

  @Test
  fun deleteReport() =
      allow { deleteSeedFundReport(seedFundReportId) } ifUser
          {
            canDeleteSeedFundReport(seedFundReportId)
          }

  @Test fun deleteSelf() = allow { deleteSelf() } ifUser { canDeleteSelf() }

  @Test
  fun deleteSpecies() = allow { deleteSpecies(speciesId) } ifUser { canDeleteSpecies(speciesId) }

  @Test
  fun deleteSubLocation() =
      allow { deleteSubLocation(subLocationId) } ifUser { canDeleteSubLocation(subLocationId) }

  @Test fun deleteSupportIssue() = allow { deleteSupportIssue() } ifUser { canDeleteSupportIssue() }

  @Test fun deleteUpload() = allow { deleteUpload(uploadId) } ifUser { canDeleteUpload(uploadId) }

  @Test fun deleteUsers() = allow { deleteUsers() } ifUser { canDeleteUsers() }

  @Test
  fun importGlobalSpeciesData() =
      allow { importGlobalSpeciesData() } ifUser { canImportGlobalSpeciesData() }

  @Test
  fun listActivities() = allow { listActivities(projectId) } ifUser { canListActivities(projectId) }

  @Test
  fun listAutomations() =
      allow { listAutomations(facilityId) } ifUser { canListAutomations(facilityId) }

  @Test
  fun listFundingEntityUsers() {
    assertThrows<FundingEntityNotFoundException> {
      requirements.listFundingEntityUsers(fundingEntityId)
    }

    grant { user.canReadFundingEntity(fundingEntityId) }
    assertThrows<AccessDeniedException> { requirements.listFundingEntityUsers(fundingEntityId) }

    grant { user.canListFundingEntityUsers(fundingEntityId) }
    requirements.listFundingEntityUsers(fundingEntityId)
  }

  @Test
  fun listGlobalNotifications() =
      allow { listNotifications(null) } ifUser { canListNotifications(null) }

  @Test
  fun listOrganizationNotifications() =
      allow { listNotifications(organizationId) } ifUser { canListNotifications(organizationId) }

  @Test
  fun listOrganizationUsers() =
      allow { listOrganizationUsers(organizationId) } ifUser
          {
            canListOrganizationUsers(organizationId)
          }

  @Test
  fun listPublishedActivities() =
      allow { listPublishedActivities(projectId) } ifUser { canListPublishedActivities(projectId) }

  @Test
  fun listReports() =
      allow { listSeedFundReports(organizationId) } ifUser
          {
            canListSeedFundReports(organizationId)
          }

  @Test
  fun manageActivity() =
      allow { manageActivity(activityId) } ifUser { canManageActivity(activityId) }

  @Test fun manageDeliverables() = allow { manageDeliverables() } ifUser { canManageDeliverables() }

  @Test fun manageDisclaimers() = allow { manageDisclaimers() } ifUser { canManageDisclaimers() }

  @Test fun manageModuleEvents() = allow { manageModuleEvents() } ifUser { canManageModuleEvents() }

  @Test
  fun manageModuleEventStatuses() =
      allow { manageModuleEventStatuses() } ifUser { canManageModuleEventStatuses() }

  @Test fun manageModules() = allow { manageModules() } ifUser { canManageModules() }

  @Test
  fun manageObservation() =
      allow { manageObservation(observationId) } ifUser { canManageObservation(observationId) }

  @Test
  fun manageProjectReportConfigs() =
      allow { manageProjectReportConfigs() } ifUser { canManageProjectReportConfigs() }

  @Test
  fun movePlantingSite() {
    assertThrows<PlantingSiteNotFoundException> {
      requirements.movePlantingSiteToAnyOrg(plantingSiteId)
    }

    grant { user.canReadPlantingSite(plantingSiteId) }
    assertThrows<AccessDeniedException> { requirements.movePlantingSiteToAnyOrg(plantingSiteId) }

    grant { user.canUpdatePlantingSite(plantingSiteId) }
    assertThrows<AccessDeniedException> { requirements.movePlantingSiteToAnyOrg(plantingSiteId) }

    grant { user.canMovePlantingSiteToAnyOrg(plantingSiteId) }
    requirements.movePlantingSiteToAnyOrg(plantingSiteId)
  }

  @Test
  fun notifyUpcomingReports() =
      allow { notifyUpcomingReports() } ifUser { canNotifyUpcomingReports() }

  @Test
  fun proxyGeoServerGetRequests() =
      allow { proxyGeoServerGetRequests() } ifUser { canProxyGeoServerGetRequests() }

  @Test
  fun publishProjectProfileDetails() =
      allow { publishProjectProfileDetails() } ifUser { canPublishProjectProfileDetails() }

  @Test fun publishReports() = allow { publishReports() } ifUser { canPublishReports() }

  @Test fun readAccession() = testRead { readAccession(accessionId) }

  @Test fun readActivity() = testRead { readActivity(activityId) }

  @Test
  fun readAllAcceleratorDetails() =
      allow { readAllAcceleratorDetails() } ifUser { canReadAllAcceleratorDetails() }

  @Test
  fun readAllDeliverables() = allow { readAllDeliverables() } ifUser { canReadAllDeliverables() }

  @Test fun readApplication() = testRead { readApplication(applicationId) }

  @Test fun readAutomation() = testRead { readAutomation(automationId) }

  @Test fun readBatch() = testRead { readBatch(batchId) }

  @Test fun readCohort() = testRead { readCohort(cohortId) }

  @Test
  fun readCohortProjects() {
    assertThrows<CohortNotFoundException> { requirements.readCohortProjects(cohortId) }

    grant { user.canReadCohort(cohortId) }
    assertThrows<AccessDeniedException> { requirements.readCohortProjects(cohortId) }

    grant { user.canReadCohortProjects(cohortId) }
    requirements.readCohortProjects(cohortId)
  }

  @Test fun readCohorts() = allow { readCohorts() } ifUser { canReadCohorts() }

  @Test
  fun readCurrentDisclaimer() =
      allow { readCurrentDisclaimer() } ifUser { canReadCurrentDisclaimer() }

  @Test fun readDelivery() = testRead { readDelivery(deliveryId) }

  @Test
  fun readDefaultVoters() {
    assertThrows<AccessDeniedException> { requirements.readDefaultVoters() }

    grant { user.canReadDefaultVoters() }
    requirements.readDefaultVoters()
  }

  @Test fun readDevice() = testRead { readDevice(deviceId) }

  @Test fun readDeviceManager() = testRead { readDeviceManager(deviceManagerId) }

  @Test fun readDraftPlantingSite() = testRead { readDraftPlantingSite(draftPlantingSiteId) }

  @Test fun readFacility() = testRead { readFacility(facilityId) }

  @Test
  fun readFundingEntities() = allow { readFundingEntities() } ifUser { canReadFundingEntities() }

  @Test fun readFundingEntity() = testRead { readFundingEntity(fundingEntityId) }

  @Test fun readGlobalRoles() = allow { readGlobalRoles() } ifUser { canReadGlobalRoles() }

  @Test fun readInternalTags() = allow { readInternalTags() } ifUser { canReadInternalTags() }

  @Test
  fun readInternalVariableWorkflowDetails() {
    assertThrows<ProjectNotFoundException> {
      requirements.readInternalVariableWorkflowDetails(projectId)
    }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> {
      requirements.readInternalVariableWorkflowDetails(projectId)
    }

    grant { user.canReadInternalVariableWorkflowDetails(projectId) }
    requirements.readInternalVariableWorkflowDetails(projectId)
  }

  @Test fun readModule() = testRead { readModule(moduleId) }

  @Test
  fun readModuleDetails() =
      allow { readModuleDetails(moduleId) } ifUser { canReadModuleDetails(moduleId) }

  @Test fun readModuleEvents() = testRead { readModuleEvent(eventId) }

  @Test
  fun readModuleEventParticipants() =
      allow { readModuleEventParticipants() } ifUser { canReadModuleEventParticipants() }

  @Test fun readMonitoringPlot() = testRead { readMonitoringPlot(monitoringPlotId) }

  @Test fun readNotification() = testRead { readNotification(notificationId) }

  @Test fun readObservation() = testRead { readObservation(observationId) }

  @Test fun readOrganization() = testRead { readOrganization(organizationId) }

  @Test
  fun readOrganizationDeliverables() {
    assertThrows<OrganizationNotFoundException> {
      requirements.readOrganizationDeliverables(organizationId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> {
      requirements.readOrganizationDeliverables(organizationId)
    }

    grant { user.canReadOrganizationDeliverables(organizationId) }
    requirements.readOrganizationDeliverables(organizationId)
  }

  @Test
  fun readOrganizationFeatures() {
    assertThrows<OrganizationNotFoundException> {
      requirements.readOrganizationFeatures(organizationId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.readOrganizationFeatures(organizationId) }

    grant { user.canReadOrganizationFeatures(organizationId) }
    requirements.readOrganizationFeatures(organizationId)
  }

  @Test
  fun readOrganizationUser() {
    assertThrows<OrganizationNotFoundException> {
      requirements.readOrganizationUser(organizationId, userId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<UserNotFoundException> {
      requirements.readOrganizationUser(organizationId, userId)
    }

    grant { user.canReadOrganizationUser(organizationId, userId) }
    requirements.readOrganizationUser(organizationId, userId)
  }

  @Test
  fun readParticipantProjectSpecies() = testRead {
    readParticipantProjectSpecies(participantProjectSpeciesId)
  }

  @Test fun readPlanting() = testRead { readPlanting(plantingId) }

  @Test fun readPlantingSite() = testRead { readPlantingSite(plantingSiteId) }

  @Test fun readProject() = testRead { readProject(projectId) }

  @Test
  fun readProjectAcceleratorDetails() {
    assertThrows<ProjectNotFoundException> { requirements.readProjectAcceleratorDetails(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.readProjectAcceleratorDetails(projectId) }

    grant { user.canReadProjectAcceleratorDetails(projectId) }
    requirements.readProjectAcceleratorDetails(projectId)
  }

  @Test
  fun readProjectFunderDetails() {
    assertThrows<ProjectNotFoundException> { requirements.readProjectFunderDetails(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.readProjectFunderDetails(projectId) }

    grant { user.canReadProjectFunderDetails(projectId) }
    requirements.readProjectFunderDetails(projectId)
  }

  @Test
  fun readProjectDeliverables() {
    assertThrows<ProjectNotFoundException> { requirements.readProjectDeliverables(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.readProjectDeliverables(projectId) }

    grant { user.canReadProjectDeliverables(projectId) }
    requirements.readProjectDeliverables(projectId)
  }

  @Test
  fun readProjectModules() {
    assertThrows<ProjectNotFoundException> { requirements.readProjectModules(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.readProjectModules(projectId) }

    grant { user.canReadProjectModules(projectId) }
    requirements.readProjectModules(projectId)
  }

  @Test
  fun readProjectReportConfigs() =
      allow { readProjectReportConfigs() } ifUser { canReadProjectReportConfigs() }

  @Test
  fun readProjectScores() {
    assertThrows<ProjectNotFoundException> { requirements.readProjectScores(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.readProjectScores(projectId) }

    grant { user.canReadProjectScores(projectId) }
    requirements.readProjectScores(projectId)
  }

  @Test fun readPublishedActivity() = testRead { readPublishedActivity(publishedActivityId) }

  @Test
  fun readPublishedProjects() {
    assertThrows<AccessDeniedException> { requirements.readPublishedProjects() }

    grant { user.canReadPublishedProjects() }
    requirements.readPublishedProjects()
  }

  @Test
  fun readPublishedReport() {
    assertThrows<ReportNotFoundException> { requirements.readPublishedReport(reportId) }

    grant { user.canReadPublishedReport(reportId) }
    requirements.readPublishedReport(reportId)
  }

  @Test
  fun readPublishedReports() {
    assertThrows<ProjectNotFoundException> { requirements.readPublishedReports(projectId) }

    grant { user.canReadPublishedReports(projectId) }
    requirements.readPublishedReports(projectId)
  }

  @Test fun readReport() = testRead { readReport(reportId) }

  @Test fun readSeedFundReport() = testRead { readSeedFundReport(seedFundReportId) }

  @Test fun readSpecies() = testRead { readSpecies(speciesId) }

  @Test fun readStratum() = testRead { readStratum(stratumId) }

  @Test fun readSubLocation() = testRead { readSubLocation(subLocationId) }

  @Test fun readSubmissionDocument() = testRead { readSubmissionDocument(submissionDocumentId) }

  @Test fun readSubstratum() = testRead { readSubstratum(substratumId) }

  @Test fun readUser() = testRead { readUser(otherUserId) }

  @Test
  fun readUserDeliverableCategories() {
    assertThrows<UserNotFoundException> {
      requirements.readUserDeliverableInternalInterests(otherUserId)
    }

    grant { user.canReadUser(otherUserId) }
    assertThrows<AccessDeniedException> {
      requirements.readUserDeliverableInternalInterests(otherUserId)
    }

    grant { user.canReadUserInternalInterests(otherUserId) }
    requirements.readUserDeliverableInternalInterests(otherUserId)
  }

  @Test fun readUpload() = testRead { readUpload(uploadId) }

  @Test fun readViabilityTest() = testRead { readViabilityTest(viabilityTestId) }

  @Test fun readWithdrawal() = testRead { readWithdrawal(withdrawalId) }

  @Test
  fun regenerateAllDeviceManagerTokens() =
      allow { regenerateAllDeviceManagerTokens() } ifUser { canRegenerateAllDeviceManagerTokens() }

  @Test
  fun removeOrganizationUser() {
    assertThrows<OrganizationNotFoundException> {
      requirements.removeOrganizationUser(organizationId, userId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> {
      requirements.removeOrganizationUser(organizationId, userId)
    }

    grant { user.canRemoveOrganizationUser(organizationId, userId) }
    requirements.removeOrganizationUser(organizationId, userId)
  }

  @Test
  fun replaceObservationPlot() =
      allow { replaceObservationPlot(observationId) } ifUser
          {
            canReplaceObservationPlot(observationId)
          }

  @Test
  fun rescheduleObservation() =
      allow { rescheduleObservation(observationId) } ifUser
          {
            canRescheduleObservation(observationId)
          }

  @Test
  fun reviewApplication() =
      allow { reviewApplication(applicationId) } ifUser { canReviewApplication(applicationId) }

  @Test
  fun scheduleObservation() =
      allow { scheduleObservation(plantingSiteId) } ifUser
          {
            canScheduleObservation(plantingSiteId)
          }

  @Test
  fun scheduleAdHocObservation() =
      allow { scheduleAdHocObservation(plantingSiteId) } ifUser
          {
            canScheduleAdHocObservation(plantingSiteId)
          }

  @Test fun sendAlert() = allow { sendAlert(facilityId) } ifUser { canSendAlert(facilityId) }

  @Test
  fun setOrganizationUserRole() {
    allow { setOrganizationUserRole(organizationId, role) } ifUser
        {
          canSetOrganizationUserRole(organizationId, role)
        }
  }

  @Test fun setTestClock() = allow { setTestClock() } ifUser { canSetTestClock() }

  @Test
  fun setWithdrawalUser() =
      allow { setWithdrawalUser(accessionId) } ifUser { canSetWithdrawalUser(accessionId) }

  @Test
  fun triggerAutomation() =
      allow { triggerAutomation(automationId) } ifUser { canTriggerAutomation(automationId) }

  @Test
  fun updateAccession() =
      allow { updateAccession(accessionId) } ifUser { canUpdateAccession(accessionId) }

  @Test
  fun updateAccessionProject() =
      allow { updateAccessionProject(accessionId) } ifUser
          {
            canUpdateAccessionProject(accessionId)
          }

  @Test
  fun updateActivity() =
      allow { updateActivity(activityId) } ifUser { canUpdateActivity(activityId) }

  @Test
  fun updateApplicationBoundary() =
      allow { updateApplicationBoundary(applicationId) } ifUser
          {
            canUpdateApplicationBoundary(applicationId)
          }

  @Test
  fun updateApplicationCountry() =
      allow { updateApplicationCountry(applicationId) } ifUser
          {
            canUpdateApplicationCountry(applicationId)
          }

  @Test
  fun updateApplicationSubmissionStatus() =
      allow { updateApplicationSubmissionStatus(applicationId) } ifUser
          {
            canUpdateApplicationSubmissionStatus(applicationId)
          }

  @Test fun updateAppVersions() = allow { updateAppVersions() } ifUser { canUpdateAppVersions() }

  @Test
  fun updateAutomation() =
      allow { updateAutomation(automationId) } ifUser { canUpdateAutomation(automationId) }

  @Test fun updateBatch() = allow { updateBatch(batchId) } ifUser { canUpdateBatch(batchId) }

  @Test fun updateCohort() = allow { updateCohort(cohortId) } ifUser { canUpdateCohort(cohortId) }

  @Test
  fun updateDelivery() =
      allow { updateDelivery(deliveryId) } ifUser { canUpdateDelivery(deliveryId) }

  @Test fun updateDevice() = allow { updateDevice(deviceId) } ifUser { canUpdateDevice(deviceId) }

  @Test
  fun updateDeviceManager() =
      allow { updateDeviceManager(deviceManagerId) } ifUser
          {
            canUpdateDeviceManager(deviceManagerId)
          }

  @Test
  fun updateDefaultVoters() = allow { updateDefaultVoters() } ifUser { canUpdateDefaultVoters() }

  @Test
  fun updateDeviceTemplates() =
      allow { updateDeviceTemplates() } ifUser { canUpdateDeviceTemplates() }

  @Test
  fun updateDraftPlantingSite() =
      allow { updateDraftPlantingSite(draftPlantingSiteId) } ifUser
          {
            canUpdateDraftPlantingSite(draftPlantingSiteId)
          }

  @Test
  fun updateFacility() =
      allow { updateFacility(facilityId) } ifUser { canUpdateFacility(facilityId) }

  @Test
  fun updateFundingEntities() =
      allow { updateFundingEntities() } ifUser { canUpdateFundingEntities() }

  @Test
  fun updateFundingEntityProjects() =
      allow { updateFundingEntityProjects() } ifUser { canUpdateFundingEntityProjects() }

  @Test
  fun updateFundingEntityUsers() {
    assertThrows<AccessDeniedException> { requirements.updateFundingEntityUsers(fundingEntityId) }

    grant { user.canUpdateFundingEntityUsers(fundingEntityId) }
    requirements.updateFundingEntityUsers(fundingEntityId)
  }

  @Test
  fun updateGlobalNotifications() =
      allow { updateNotifications(null) } ifUser { canUpdateNotifications(null) }

  @Test fun updateGlobalRoles() = allow { updateGlobalRoles() } ifUser { canUpdateGlobalRoles() }

  @Test
  fun updateInternalVariableWorkflowDetails() =
      allow { updateInternalVariableWorkflowDetails(projectId) } ifUser
          {
            canUpdateInternalVariableWorkflowDetails(projectId)
          }

  @Test
  fun updateMonitoringPlot() =
      allow { updateMonitoringPlot(monitoringPlotId) } ifUser
          {
            canUpdateMonitoringPlot(monitoringPlotId)
          }

  @Test
  fun updateNotification() =
      allow { updateNotification(notificationId) } ifUser { canUpdateNotification(notificationId) }

  @Test
  fun updateObservation() =
      allow { updateObservation(observationId) } ifUser { canUpdateObservation(observationId) }

  @Test
  fun updateObservationQuantities() =
      allow { updateObservationQuantities(observationId) } ifUser
          {
            canUpdateObservationQuantities(observationId)
          }

  @Test
  fun updateOrganization() =
      allow { updateOrganization(organizationId) } ifUser { canUpdateOrganization(organizationId) }

  @Test
  fun updateOrganizationNotifications() =
      allow { updateNotifications(organizationId) } ifUser
          {
            canUpdateNotifications(organizationId)
          }

  @Test
  fun updateParticipantProjectSpecies() =
      allow { updateParticipantProjectSpecies(participantProjectSpeciesId) } ifUser
          {
            canUpdateParticipantProjectSpecies(participantProjectSpeciesId)
          }

  @Test
  fun updatePlantingSite() =
      allow { updatePlantingSite(plantingSiteId) } ifUser { canUpdatePlantingSite(plantingSiteId) }

  @Test
  fun updatePlantingSiteProject() =
      allow { updatePlantingSiteProject(plantingSiteId) } ifUser
          {
            canUpdatePlantingSiteProject(plantingSiteId)
          }

  @Test
  fun updateProject() = allow { updateProject(projectId) } ifUser { canUpdateProject(projectId) }

  @Test
  fun updateProjectAcceleratorDetails() =
      allow { updateProjectAcceleratorDetails(projectId) } ifUser
          {
            canUpdateProjectAcceleratorDetails(projectId)
          }

  @Test
  fun updateProjectDocumentSettings() =
      allow { updateProjectDocumentSettings(projectId) } ifUser
          {
            canUpdateProjectDocumentSettings(projectId)
          }

  @Test
  fun updateProjectReports() =
      allow { updateProjectReports(projectId) } ifUser { canUpdateProjectReports(projectId) }

  @Test
  fun updateProjectScores() =
      allow { updateProjectScores(projectId) } ifUser { canUpdateProjectScores(projectId) }

  @Test
  fun updateProjectVotes() =
      allow { updateProjectVotes(projectId) } ifUser { canUpdateProjectVotes(projectId) }

  @Test fun updateReport() = allow { updateReport(reportId) } ifUser { canUpdateReport(reportId) }

  @Test
  fun updateSeedFundReport() =
      allow { updateSeedFundReport(seedFundReportId) } ifUser
          {
            canUpdateSeedFundReport(seedFundReportId)
          }

  @Test
  fun updateSpecies() = allow { updateSpecies(speciesId) } ifUser { canUpdateSpecies(speciesId) }

  @Test
  fun updateStratum() = allow { updateStratum(stratumId) } ifUser { canUpdateStratum(stratumId) }

  @Test
  fun updateSubLocation() =
      allow { updateSubLocation(subLocationId) } ifUser { canUpdateSubLocation(subLocationId) }

  @Test
  fun updateSubmissionStatus() =
      allow { updateSubmissionStatus(deliverableId, projectId) } ifUser
          {
            canUpdateSubmissionStatus(deliverableId, projectId)
          }

  @Test
  fun updateSubstratumCompleted() =
      allow { updateSubstratumCompleted(substratumId) } ifUser
          {
            canUpdateSubstratumCompleted(substratumId)
          }

  @Test
  fun updateT0Plot() = allow { updateT0(monitoringPlotId) } ifUser { canUpdateT0(monitoringPlotId) }

  @Test fun updateT0() = allow { updateT0(stratumId) } ifUser { canUpdateT0(stratumId) }

  @Test fun updateUpload() = allow { updateUpload(uploadId) } ifUser { canUpdateUpload(uploadId) }

  @Test
  fun updateUserDeliverableCategories() =
      allow { updateUserInternalInterests(otherUserId) } ifUser
          {
            canUpdateUserInternalInterests(otherUserId)
          }

  @Test
  fun uploadAccessionPhoto() =
      allow { uploadPhoto(accessionId) } ifUser { canUploadPhoto(accessionId) }

  // When adding new permission tests, put them in alphabetical order.
}
