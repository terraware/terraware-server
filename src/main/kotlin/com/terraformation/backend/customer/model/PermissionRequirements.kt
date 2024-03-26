package com.terraformation.backend.customer.model

import com.terraformation.backend.accelerator.db.CohortNotFoundException
import com.terraformation.backend.accelerator.db.ParticipantNotFoundException
import com.terraformation.backend.accelerator.db.SubmissionDocumentNotFoundException
import com.terraformation.backend.accelerator.db.SubmissionNotFoundException
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.DeviceManagerNotFoundException
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.InvalidRoleUpdateException
import com.terraformation.backend.db.NotificationNotFoundException
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.SubLocationNotFoundException
import com.terraformation.backend.db.TimeseriesNotFoundException
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.ViabilityTestNotFoundException
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.SubmissionDocumentId
import com.terraformation.backend.db.accelerator.SubmissionId
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.nursery.db.BatchNotFoundException
import com.terraformation.backend.nursery.db.WithdrawalNotFoundException
import com.terraformation.backend.tracking.db.DeliveryNotFoundException
import com.terraformation.backend.tracking.db.DraftPlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.PlantingNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSubzoneNotFoundException
import com.terraformation.backend.tracking.db.PlantingZoneNotFoundException
import org.springframework.security.access.AccessDeniedException

/**
 * Declarative permission checks. The methods here check whether the user has permission to perform
 * various actions. If the user doesn't have permission, they throw exceptions. A method may throw
 * more than one kind of exception.
 *
 * You will usually not reference this class directly, but rather call its methods from a lambda
 * function you pass to [requirePermissions]. For example:
 * ```
 * requirePermissions { createDevice(facilityId) }
 * ```
 *
 * This class should not do any data access of its own; it should call the various `canX` methods on
 * [TerrawareUser] to determine whether the user has permission to do something.
 *
 * ## Exception behavior
 *
 * To ensure consistent behavior of the permission checks, the methods here should throw exceptions
 * using the following set of rules.
 * - Always throw the most specific exception class that describes the failure. For example, the
 *   rules will say to throw [EntityNotFoundException] but you'd actually want to throw, e.g.,
 *   [AccessionNotFoundException] to give the caller more information about what failed.
 * - Exception messages may be returned to the client and should never include any information that
 *   you wouldn't want end users to see.
 * - Exception messages should, if possible, include the identifier of the object the user didn't
 *   have permission to operate on.
 * - For read actions, if the object doesn't exist, throw [EntityNotFoundException]. Note that the
 *   permission checking methods in [TerrawareUser] are required to return false if the target
 *   object doesn't exist, so in most cases, it should suffice to just check for read permission.
 * - For read actions, if the user doesn't have permission to see the object at all, throw
 *   [EntityNotFoundException]. We want inaccessible data to act as if it doesn't exist at all.
 * - For fine-grained read actions, if the user has permission to see the object as a whole but
 *   doesn't have permission to see the specific part of the object, throw [AccessDeniedException].
 * - For creation actions, if the user doesn't have permission to see the parent object (if any),
 *   throw [EntityNotFoundException].
 * - For write actions (including delete), if the user has permission to read the object but doesn't
 *   have permission to perform the write operation, throw [AccessDeniedException].
 * - For write actions, if the user doesn't have permission to read the object or to perform the
 *   write operation, throw [EntityNotFoundException].
 *
 * ## Naming convention
 *
 * The methods in this class are named with an eye toward the call sites looking like declarations
 * of the required permissions, rather than looking like imperative operations. They describe what
 * the caller is asking permission to do. That is, if you call [createFacility], you're expressing
 * that you intend to create a facility, but [createFacility] doesn't actually create anything.
 * Although that isn't consistent with method naming conventions in the application as a whole,
 * you'll always be calling this code from inside a [requirePermissions] block, so the context
 * should make it clear what's going on.
 */
class PermissionRequirements(private val user: TerrawareUser) {
  fun addCohortParticipant(cohortId: CohortId, participantId: ParticipantId) {
    if (!user.canAddCohortParticipant(cohortId, participantId)) {
      readCohort(cohortId)
      readParticipant(participantId)
      throw AccessDeniedException(
          "No permission to add participant $participantId to cohort $cohortId")
    }
  }

  fun addOrganizationUser(organizationId: OrganizationId) {
    if (!user.canAddOrganizationUser(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to add users to organization $organizationId")
    }
  }

  fun addParticipantProject(participantId: ParticipantId, projectId: ProjectId) {
    if (!user.canAddParticipantProject(participantId, projectId)) {
      readParticipant(participantId)
      readProject(projectId)
      throw AccessDeniedException("No permission to add project to participant $participantId")
    }
  }

  fun addTerraformationContact(organizationId: OrganizationId) {
    if (!user.canAddTerraformationContact(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to add terraformation contact to organization $organizationId")
    }
  }

  fun countNotifications() {
    if (!user.canCountNotifications()) {
      throw AccessDeniedException("No permission to count notifications")
    }
  }

  fun createAccession(facilityId: FacilityId) {
    if (!user.canCreateAccession(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create accessions in facility $facilityId")
    }
  }

  fun createApiKey(organizationId: OrganizationId) {
    if (!user.canCreateApiKey(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create API keys in organization $organizationId")
    }
  }

  fun createAutomation(facilityId: FacilityId) {
    if (!user.canCreateAutomation(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create automations in facility $facilityId")
    }
  }

  fun createBatch(facilityId: FacilityId) {
    if (!user.canCreateBatch(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create seedling batch")
    }
  }

  fun createCohort() {
    if (!user.canCreateCohort()) {
      throw AccessDeniedException("No permission to create cohort")
    }
  }

  fun createCohortModule() {
    if (!user.canCreateCohortModule()) {
      throw AccessDeniedException("No permission to create cohort module")
    }
  }

  fun createDelivery(plantingSiteId: PlantingSiteId) {
    if (!user.canCreateDelivery(plantingSiteId)) {
      readPlantingSite(plantingSiteId)
      throw AccessDeniedException(
          "No permission to create delivery at planting site $plantingSiteId")
    }
  }

  fun createDevice(facilityId: FacilityId) {
    if (!user.canCreateDevice(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create device in facility $facilityId")
    }
  }

  fun createDeviceManager() {
    if (!user.canCreateDeviceManager()) {
      throw AccessDeniedException("No permission to create device manager")
    }
  }

  fun createDraftPlantingSite(organizationId: OrganizationId) {
    if (!user.canCreateDraftPlantingSite(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create draft planting site in organization $organizationId")
    }
  }

  fun createFacility(organizationId: OrganizationId) {
    if (!user.canCreateFacility(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create facilities in organization $organizationId")
    }
  }

  fun createNotification(userId: UserId, organizationId: OrganizationId) {
    readOrganization(organizationId)
    if (!user.canCreateNotification(userId, organizationId)) {
      throw AccessDeniedException("No permission to create notification")
    }
  }

  fun createObservation(plantingSiteId: PlantingSiteId) {
    if (!user.canCreateObservation(plantingSiteId)) {
      readPlantingSite(plantingSiteId)
      throw AccessDeniedException("No permission to create observation")
    }
  }

  fun createParticipant() {
    if (!user.canCreateParticipant()) {
      throw AccessDeniedException("No permission to create participant")
    }
  }

  fun createPlantingSite(organizationId: OrganizationId) {
    if (!user.canCreatePlantingSite(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create planting sites in organization $organizationId")
    }
  }

  fun createProject(organizationId: OrganizationId) {
    if (!user.canCreateProject(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create projects in organization $organizationId")
    }
  }

  fun createReport(organizationId: OrganizationId) {
    if (!user.canCreateReport(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to create reports in organization $organizationId")
    }
  }

  fun createSpecies(organizationId: OrganizationId) {
    if (!user.canCreateSpecies(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to create species")
    }
  }

  fun createSubLocation(facilityId: FacilityId) {
    if (!user.canCreateSubLocation(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create sub-location at facility $facilityId")
    }
  }

  fun createSubmission(projectId: ProjectId) {
    if (!user.canCreateSubmission(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to create submission for project $projectId")
    }
  }

  fun createTimeseries(deviceId: DeviceId) {
    if (!user.canCreateTimeseries(deviceId)) {
      readDevice(deviceId)
      throw AccessDeniedException("No permission to create timeseries on device $deviceId")
    }
  }

  fun createWithdrawalPhoto(withdrawalId: WithdrawalId) {
    if (!user.canCreateWithdrawalPhoto(withdrawalId)) {
      readWithdrawal(withdrawalId)
      throw AccessDeniedException("No permission to create photo on withdrawal $withdrawalId")
    }
  }

  fun deleteAccession(accessionId: AccessionId) {
    if (!user.canDeleteAccession(accessionId)) {
      readAccession(accessionId)
      throw AccessDeniedException("No permission to delete accession $accessionId")
    }
  }

  fun deleteAutomation(automationId: AutomationId) {
    if (!user.canDeleteAutomation(automationId)) {
      readAutomation(automationId)
      throw AccessDeniedException("No permission to delete automation $automationId")
    }
  }

  fun deleteBatch(batchId: BatchId) {
    if (!user.canDeleteBatch(batchId)) {
      readBatch(batchId)
      throw AccessDeniedException("No permission to delete seedling batch $batchId")
    }
  }

  fun deleteCohort(cohortId: CohortId) {
    if (!user.canDeleteCohort(cohortId)) {
      readCohort(cohortId)
      throw AccessDeniedException("No permission to delete cohort $cohortId")
    }
  }

  fun deleteCohortParticipant(cohortId: CohortId, participantId: ParticipantId) {
    if (!user.canDeleteCohortParticipant(cohortId, participantId)) {
      readCohort(cohortId)
      readParticipant(participantId)
      throw AccessDeniedException("No permission to delete cohort $cohortId")
    }
  }

  fun deleteDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) {
    if (!user.canDeleteDraftPlantingSite(draftPlantingSiteId)) {
      readDraftPlantingSite(draftPlantingSiteId)
      throw AccessDeniedException(
          "No permission to delete draft planting site $draftPlantingSiteId")
    }
  }

  fun deleteOrganization(organizationId: OrganizationId) {
    if (!user.canDeleteOrganization(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to delete organization $organizationId")
    }
  }

  fun deleteParticipant(participantId: ParticipantId) {
    if (!user.canDeleteParticipant(participantId)) {
      readParticipant(participantId)
      throw AccessDeniedException("No permission to delete participant $participantId")
    }
  }

  fun deleteParticipantProject(participantId: ParticipantId, projectId: ProjectId) {
    if (!user.canDeleteParticipantProject(participantId, projectId)) {
      readParticipant(participantId)
      readProject(projectId)
      throw AccessDeniedException("No permission to delete project from participant $participantId")
    }
  }

  fun deletePlantingSite(plantingSiteId: PlantingSiteId) {
    if (!user.canDeletePlantingSite(plantingSiteId)) {
      readPlantingSite(plantingSiteId)
      throw AccessDeniedException("No permission to delete planting site $plantingSiteId")
    }
  }

  fun deleteProject(projectId: ProjectId) {
    if (!user.canDeleteProject(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to delete project $projectId")
    }
  }

  fun deleteReport(reportId: ReportId) {
    if (!user.canDeleteReport(reportId)) {
      readReport(reportId)
      throw AccessDeniedException("No permission to delete report $reportId")
    }
  }

  fun deleteSelf() {
    if (!user.canDeleteSelf()) {
      throw AccessDeniedException("No permission to delete self")
    }
  }

  fun deleteSpecies(speciesId: SpeciesId) {
    if (!user.canDeleteSpecies(speciesId)) {
      readSpecies(speciesId)
      throw AccessDeniedException("No permission to delete species $speciesId")
    }
  }

  fun deleteSubLocation(subLocationId: SubLocationId) {
    if (!user.canDeleteSubLocation(subLocationId)) {
      readSubLocation(subLocationId)
      throw AccessDeniedException("No permission to delete sub-location")
    }
  }

  fun deleteUpload(uploadId: UploadId) {
    if (!user.canDeleteUpload(uploadId)) {
      readUpload(uploadId)
      throw AccessDeniedException("No permission to delete upload")
    }
  }

  fun importGlobalSpeciesData() {
    if (!user.canImportGlobalSpeciesData()) {
      throw AccessDeniedException("No permission to import global species data")
    }
  }

  fun listAutomations(facilityId: FacilityId) {
    if (!user.canListAutomations(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to list automations in facility $facilityId")
    }
  }

  fun listNotifications(organizationId: OrganizationId?) {
    if (!user.canListNotifications(organizationId)) {
      if (organizationId != null) {
        readOrganization(organizationId)
      }
      throw AccessDeniedException("No permission to list notifications")
    }
  }

  fun listOrganizationUsers(organizationId: OrganizationId) {
    if (!user.canListOrganizationUsers(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to list users in organization $organizationId")
    }
  }

  fun listReports(organizationId: OrganizationId) {
    if (!user.canListReports(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to list reports in organization $organizationId")
    }
  }

  fun manageDeliverables() {
    if (!user.canManageDeliverables()) {
      throw AccessDeniedException("No permission to manage deliverables")
    }
  }

  fun manageInternalTags() {
    if (!user.canManageInternalTags()) {
      throw AccessDeniedException("No permission to manage internal tags")
    }
  }

  fun manageModules() {
    if (!user.canManageModules()) {
      throw AccessDeniedException("No permission to manage modules")
    }
  }

  fun manageNotifications() {
    if (!user.canManageNotifications()) {
      throw AccessDeniedException("No permissions to manage notifications")
    }
  }

  fun manageObservation(observationId: ObservationId) {
    if (!user.canManageObservation(observationId)) {
      readObservation(observationId)
      throw AccessDeniedException("No permission to manage observation $observationId")
    }
  }

  fun movePlantingSiteToAnyOrg(plantingSiteId: PlantingSiteId) {
    if (!user.canMovePlantingSiteToAnyOrg(plantingSiteId)) {
      readPlantingSite(plantingSiteId)
      throw AccessDeniedException("No permission to move planting site $plantingSiteId")
    }
  }

  fun readAccession(accessionId: AccessionId) {
    if (!user.canReadAccession(accessionId)) {
      throw AccessionNotFoundException(accessionId)
    }
  }

  fun readAllAcceleratorDetails() {
    if (!user.canReadAllAcceleratorDetails()) {
      throw AccessDeniedException("No permission to read accelerator details")
    }
  }

  fun readAllDeliverables() {
    if (!user.canReadAllDeliverables()) {
      throw AccessDeniedException("No permission to read all deliverables")
    }
  }

  fun readAutomation(automationId: AutomationId) {
    if (!user.canReadAutomation(automationId)) {
      throw AutomationNotFoundException(automationId)
    }
  }

  fun readBatch(batchId: BatchId) {
    if (!user.canReadBatch(batchId)) {
      throw BatchNotFoundException(batchId)
    }
  }

  fun readCohort(cohortId: CohortId) {
    if (!user.canReadCohort(cohortId)) {
      throw CohortNotFoundException(cohortId)
    }
  }

  fun readDefaultVoters() {
    if (!user.canReadDefaultVoters()) {
      throw AccessDeniedException("No permission to read default voters")
    }
  }

  fun readDelivery(deliveryId: DeliveryId) {
    if (!user.canReadDelivery(deliveryId)) {
      throw DeliveryNotFoundException(deliveryId)
    }
  }

  fun readDevice(deviceId: DeviceId) {
    if (!user.canReadDevice(deviceId)) {
      throw DeviceNotFoundException(deviceId)
    }
  }

  fun readDeviceManager(deviceManagerId: DeviceManagerId) {
    if (!user.canReadDeviceManager(deviceManagerId)) {
      throw DeviceManagerNotFoundException(deviceManagerId)
    }
  }

  fun readDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) {
    if (!user.canReadDraftPlantingSite(draftPlantingSiteId)) {
      throw DraftPlantingSiteNotFoundException(draftPlantingSiteId)
    }
  }

  fun readFacility(facilityId: FacilityId) {
    if (!user.canReadFacility(facilityId)) {
      throw FacilityNotFoundException(facilityId)
    }
  }

  fun readGlobalRoles() {
    if (!user.canReadGlobalRoles()) {
      throw AccessDeniedException("No permission to read global roles")
    }
  }

  fun readInternalTags() {
    if (!user.canReadInternalTags()) {
      throw AccessDeniedException("No permission to read internal tags")
    }
  }

  fun readNotification(notificationId: NotificationId) {
    if (!user.canReadNotification(notificationId)) {
      throw NotificationNotFoundException(notificationId)
    }
  }

  fun readObservation(observationId: ObservationId) {
    if (!user.canReadObservation(observationId)) {
      throw ObservationNotFoundException(observationId)
    }
  }

  fun readOrganization(organizationId: OrganizationId) {
    if (!user.canReadOrganization(organizationId)) {
      throw OrganizationNotFoundException(organizationId)
    }
  }

  fun readOrganizationDeliverables(organizationId: OrganizationId) {
    if (!user.canReadOrganizationDeliverables(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to read deliverables for organization $organizationId")
    }
  }

  fun readOrganizationUser(organizationId: OrganizationId, userId: UserId) {
    if (!user.canReadOrganizationUser(organizationId, userId)) {
      readOrganization(organizationId)
      throw UserNotFoundException(userId)
    }
  }

  fun readParticipant(participantId: ParticipantId) {
    if (!user.canReadParticipant(participantId)) {
      throw ParticipantNotFoundException(participantId)
    }
  }

  fun readPlanting(plantingId: PlantingId) {
    if (!user.canReadPlanting(plantingId)) {
      throw PlantingNotFoundException(plantingId)
    }
  }

  fun readPlantingSite(plantingSiteId: PlantingSiteId) {
    if (!user.canReadPlantingSite(plantingSiteId)) {
      throw PlantingSiteNotFoundException(plantingSiteId)
    }
  }

  fun readPlantingSubzone(plantingSubzoneId: PlantingSubzoneId) {
    if (!user.canReadPlantingSubzone(plantingSubzoneId)) {
      throw PlantingSubzoneNotFoundException(plantingSubzoneId)
    }
  }

  fun readPlantingZone(plantingZoneId: PlantingZoneId) {
    if (!user.canReadPlantingZone(plantingZoneId)) {
      throw PlantingZoneNotFoundException(plantingZoneId)
    }
  }

  fun readProject(projectId: ProjectId) {
    if (!user.canReadProject(projectId)) {
      throw ProjectNotFoundException(projectId)
    }
  }

  fun readProjectAcceleratorDetails(projectId: ProjectId) {
    if (!user.canReadProjectAcceleratorDetails(projectId)) {
      readProject(projectId)
      throw AccessDeniedException(
          "No permission to read accelerator details for project $projectId")
    }
  }

  fun readProjectDeliverables(projectId: ProjectId) {
    if (!user.canReadProjectDeliverables(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to read deliverables for project $projectId")
    }
  }

  fun readProjectScores(projectId: ProjectId) {
    if (!user.canReadProjectScores(projectId)) {
      readProject(projectId)
      throw throw AccessDeniedException("No permission to view scores for project $projectId")
    }
  }

  fun readProjectVotes(projectId: ProjectId) {
    if (!user.canReadProjectVotes(projectId)) {
      readProject(projectId)
      throw throw AccessDeniedException("No permission to view votes for project $projectId")
    }
  }

  fun readReport(reportId: ReportId) {
    if (!user.canReadReport(reportId)) {
      throw ReportNotFoundException(reportId)
    }
  }

  fun readSpecies(speciesId: SpeciesId) {
    if (!user.canReadSpecies(speciesId)) {
      throw SpeciesNotFoundException(speciesId)
    }
  }

  fun readSubLocation(subLocationId: SubLocationId) {
    if (!user.canReadSubLocation(subLocationId)) {
      throw SubLocationNotFoundException(subLocationId)
    }
  }

  fun readSubmission(submissionId: SubmissionId) {
    if (!user.canReadSubmission(submissionId)) {
      throw SubmissionNotFoundException(submissionId)
    }
  }

  fun readSubmissionDocument(documentId: SubmissionDocumentId) {
    if (!user.canReadSubmissionDocument(documentId)) {
      throw SubmissionDocumentNotFoundException(documentId)
    }
  }

  fun readTimeseries(deviceId: DeviceId) {
    if (!user.canReadTimeseries(deviceId)) {
      throw TimeseriesNotFoundException(deviceId)
    }
  }

  fun readUpload(uploadId: UploadId) {
    if (!user.canReadUpload(uploadId)) {
      throw UploadNotFoundException(uploadId)
    }
  }

  fun readUser(userId: UserId) {
    if (!user.canReadUser(userId)) {
      throw UserNotFoundException(userId)
    }
  }

  fun readViabilityTest(viabilityTestId: ViabilityTestId) {
    if (!user.canReadViabilityTest(viabilityTestId)) {
      throw ViabilityTestNotFoundException(viabilityTestId)
    }
  }

  fun readWithdrawal(withdrawalId: WithdrawalId) {
    if (!user.canReadWithdrawal(withdrawalId)) {
      throw WithdrawalNotFoundException(withdrawalId)
    }
  }

  fun regenerateAllDeviceManagerTokens() {
    if (!user.canRegenerateAllDeviceManagerTokens()) {
      throw AccessDeniedException("No permission to regenerate all device manager tokens")
    }
  }

  fun removeOrganizationUser(organizationId: OrganizationId, userId: UserId) {
    if (!user.canRemoveOrganizationUser(organizationId, userId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to remove user $userId from organization $organizationId")
    }
  }

  fun removeTerraformationContact(organizationId: OrganizationId) {
    if (!user.canRemoveTerraformationContact(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to remove Terraformation Contact from organization $organizationId")
    }
  }

  fun replaceObservationPlot(observationId: ObservationId) {
    if (!user.canReplaceObservationPlot(observationId)) {
      readObservation(observationId)
      throw AccessDeniedException("No permission to replace plot in observation $observationId")
    }
  }

  fun rescheduleObservation(observationId: ObservationId) {
    if (!user.canRescheduleObservation(observationId)) {
      readObservation(observationId)
      throw AccessDeniedException("No permission to reschedule observation $observationId")
    }
  }

  fun sendAlert(facilityId: FacilityId) {
    if (!user.canSendAlert(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to send alerts for facility $facilityId")
    }
  }

  fun setOrganizationUserRole(organizationId: OrganizationId, role: Role) {
    if (!user.canSetOrganizationUserRole(organizationId, role)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to grant role to users in organization $organizationId")
    }
  }

  fun setTerraformationContact(organizationId: OrganizationId) {
    if (!user.canSetTerraformationContact(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to grant Terraformation Contact to users in organization $organizationId")
    }
  }

  fun setTestClock() {
    if (!user.canSetTestClock()) {
      throw AccessDeniedException("No permission to set test clock")
    }
  }

  fun setWithdrawalUser(accessionId: AccessionId) {
    if (!user.canSetWithdrawalUser(accessionId)) {
      readAccession(accessionId)
      throw AccessDeniedException("No permission to set withdrawal user for accession $accessionId")
    }
  }

  fun scheduleObservation(plantingSiteId: PlantingSiteId) {
    if (!user.canScheduleObservation(plantingSiteId)) {
      readPlantingSite(plantingSiteId)
      throw AccessDeniedException(
          "No permission to schedule observation for planting site $plantingSiteId")
    }
  }

  fun triggerAutomation(automationId: AutomationId) {
    if (!user.canTriggerAutomation(automationId)) {
      readAutomation(automationId)
      throw AccessDeniedException("No permission to trigger automation $automationId")
    }
  }

  fun updateAccession(accessionId: AccessionId) {
    if (!user.canUpdateAccession(accessionId)) {
      readAccession(accessionId)
      throw AccessDeniedException("No permission to update accession $accessionId")
    }
  }

  fun updateAppVersions() {
    if (!user.canUpdateAppVersions()) {
      throw AccessDeniedException("No permission to update app versions")
    }
  }

  fun updateAutomation(automationId: AutomationId) {
    if (!user.canUpdateAutomation(automationId)) {
      readAutomation(automationId)
      throw AccessDeniedException("No permission to update automation $automationId")
    }
  }

  fun updateBatch(batchId: BatchId) {
    if (!user.canUpdateBatch(batchId)) {
      readBatch(batchId)
      throw AccessDeniedException("No permission to update seedling batch $batchId")
    }
  }

  fun updateCohort(cohortId: CohortId) {
    if (!user.canUpdateCohort(cohortId)) {
      readCohort(cohortId)
      throw AccessDeniedException("No permission to update cohort $cohortId")
    }
  }

  fun updateDefaultVoters() {
    if (!user.canUpdateDefaultVoters()) {
      throw AccessDeniedException("No permission to update default voters")
    }
  }

  fun updateDelivery(deliveryId: DeliveryId) {
    if (!user.canUpdateDelivery(deliveryId)) {
      readDelivery(deliveryId)
      throw AccessDeniedException("No permission to update delivery $deliveryId")
    }
  }

  fun updateDevice(deviceId: DeviceId) {
    if (!user.canUpdateDevice(deviceId)) {
      readDevice(deviceId)
      throw AccessDeniedException("No permission to update device $deviceId")
    }
  }

  fun updateDeviceManager(deviceManagerId: DeviceManagerId) {
    if (!user.canUpdateDeviceManager(deviceManagerId)) {
      readDeviceManager(deviceManagerId)
      throw AccessDeniedException("No permission to update device manager")
    }
  }

  fun updateDeviceTemplates() {
    if (!user.canUpdateDeviceTemplates()) {
      throw AccessDeniedException("No permission to update device templates")
    }
  }

  fun updateDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) {
    if (!user.canUpdateDraftPlantingSite(draftPlantingSiteId)) {
      readDraftPlantingSite(draftPlantingSiteId)
      throw AccessDeniedException(
          "No permission to update draft planting site $draftPlantingSiteId")
    }
  }

  fun updateFacility(facilityId: FacilityId) {
    if (!user.canUpdateFacility(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to update facility $facilityId")
    }
  }

  fun updateGlobalRoles() {
    if (!user.canUpdateGlobalRoles()) {
      throw AccessDeniedException("No permission to update global roles")
    }
  }

  fun updateNotification(notificationId: NotificationId) {
    if (!user.canUpdateNotification(notificationId)) {
      readNotification(notificationId)
      throw AccessDeniedException("No permission to update notification")
    }
  }

  fun updateNotifications(organizationId: OrganizationId?) {
    if (!user.canUpdateNotifications(organizationId)) {
      if (organizationId != null) {
        readOrganization(organizationId)
      }
      throw AccessDeniedException("No permission to update notifications")
    }
  }

  fun updateObservation(observationId: ObservationId) {
    if (!user.canUpdateObservation(observationId)) {
      readObservation(observationId)
      throw AccessDeniedException("No permission to update observation $observationId")
    }
  }

  fun updateOrganization(organizationId: OrganizationId) {
    if (!user.canUpdateOrganization(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to update organization $organizationId")
    }
  }

  fun updateParticipant(participantId: ParticipantId) {
    if (!user.canUpdateParticipant(participantId)) {
      readParticipant(participantId)
      throw AccessDeniedException("No permission to update participant $participantId")
    }
  }

  fun updatePlantingSite(plantingSiteId: PlantingSiteId) {
    if (!user.canUpdatePlantingSite(plantingSiteId)) {
      readPlantingSite(plantingSiteId)
      throw AccessDeniedException("No permission to update planting site $plantingSiteId")
    }
  }

  fun updatePlantingSubzone(plantingSubzoneId: PlantingSubzoneId) {
    if (!user.canUpdatePlantingSubzone(plantingSubzoneId)) {
      readPlantingSubzone(plantingSubzoneId)
      throw AccessDeniedException("No permission to update planting subzone $plantingSubzoneId")
    }
  }

  fun updatePlantingZone(plantingZoneId: PlantingZoneId) {
    if (!user.canUpdatePlantingZone(plantingZoneId)) {
      readPlantingZone(plantingZoneId)
      throw AccessDeniedException("No permission to update planting zone $plantingZoneId")
    }
  }

  fun updateProject(projectId: ProjectId) {
    if (!user.canUpdateProject(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to update project $projectId")
    }
  }

  fun updateProjectAcceleratorDetails(projectId: ProjectId) {
    if (!user.canUpdateProjectAcceleratorDetails(projectId)) {
      readProject(projectId)
      throw AccessDeniedException(
          "No permission to update accelerator details for project $projectId")
    }
  }

  fun updateProjectDocumentSettings(projectId: ProjectId) {
    if (!user.canUpdateProjectDocumentSettings(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to update project document settings $projectId")
    }
  }

  fun updateProjectScores(projectId: ProjectId) {
    if (!user.canUpdateProjectScores(projectId)) {
      readProject(projectId)
      throw throw AccessDeniedException("No permission to update scores for project $projectId")
    }
  }

  fun updateProjectVotes(projectId: ProjectId) {
    if (!user.canUpdateProjectVotes(projectId)) {
      readProject(projectId)
      throw throw AccessDeniedException("No permission to update votes for project $projectId")
    }
  }

  fun updateReport(reportId: ReportId) {
    if (!user.canUpdateReport(reportId)) {
      readReport(reportId)
      throw AccessDeniedException("No permission to update report $reportId")
    }
  }

  fun updateSpecies(speciesId: SpeciesId) {
    if (!user.canUpdateSpecies(speciesId)) {
      readSpecies(speciesId)
      throw AccessDeniedException("No permission to update species $speciesId")
    }
  }

  fun updateSpecificGlobalRoles(globalRoles: Set<GlobalRole>) {
    if (!user.canUpdateSpecificGlobalRoles(globalRoles)) {
      throw AccessDeniedException("No permission to update the provided global roles")
    }
  }

  fun updateSubLocation(subLocationId: SubLocationId) {
    if (!user.canUpdateSubLocation(subLocationId)) {
      readSubLocation(subLocationId)
      throw AccessDeniedException("No permission to update sub-location")
    }
  }

  fun updateSubmissionStatus(deliverableId: DeliverableId, projectId: ProjectId) {
    if (!user.canUpdateSubmissionStatus(deliverableId, projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to update submission status")
    }
  }

  fun updateTerraformationContact(organizationId: OrganizationId) {
    if (!user.canUpdateTerraformationContact(organizationId)) {
      readOrganization(organizationId)
      throw InvalidRoleUpdateException(Role.TerraformationContact)
    }
  }

  fun updateTimeseries(deviceId: DeviceId) {
    if (!user.canUpdateTimeseries(deviceId)) {
      readTimeseries(deviceId)
      throw AccessDeniedException("No permission to update timeseries on device $deviceId")
    }
  }

  fun updateUpload(uploadId: UploadId) {
    if (!user.canUpdateUpload(uploadId)) {
      readUpload(uploadId)
      throw AccessDeniedException("No permission to update upload")
    }
  }

  fun uploadPhoto(accessionId: AccessionId) {
    if (!user.canUploadPhoto(accessionId)) {
      readAccession(accessionId)
      throw AccessDeniedException("No permission to upload photo for accession $accessionId")
    }
  }
}
