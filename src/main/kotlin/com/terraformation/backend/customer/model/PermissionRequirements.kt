package com.terraformation.backend.customer.model

import com.terraformation.backend.accelerator.db.ApplicationNotFoundException
import com.terraformation.backend.accelerator.db.CohortNotFoundException
import com.terraformation.backend.accelerator.db.ModuleNotFoundException
import com.terraformation.backend.accelerator.db.ParticipantNotFoundException
import com.terraformation.backend.accelerator.db.ParticipantProjectSpeciesNotFoundException
import com.terraformation.backend.accelerator.db.SubmissionDocumentNotFoundException
import com.terraformation.backend.accelerator.db.SubmissionNotFoundException
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
import com.terraformation.backend.db.TimeseriesNotFoundException
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.ViabilityTestNotFoundException
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
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.docprod.DocumentId
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
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.documentproducer.db.DocumentNotFoundException
import com.terraformation.backend.funder.db.FundingEntityNotFoundException
import com.terraformation.backend.nursery.db.BatchNotFoundException
import com.terraformation.backend.nursery.db.WithdrawalNotFoundException
import com.terraformation.backend.tracking.db.DeliveryNotFoundException
import com.terraformation.backend.tracking.db.DraftPlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.PlantingNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSubzoneNotFoundException
import com.terraformation.backend.tracking.db.PlantingZoneNotFoundException
import com.terraformation.backend.tracking.db.PlotNotFoundException
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
  fun acceptCurrentDisclaimer() {
    user.recordPermissionChecks {
      if (!user.canAcceptCurrentDisclaimer()) {
        throw AccessDeniedException("No permission to accept current disclaimer")
      }
    }
  }

  fun addCohortParticipant(cohortId: CohortId, participantId: ParticipantId) {
    user.recordPermissionChecks {
      if (!user.canAddCohortParticipant(cohortId, participantId)) {
        readCohort(cohortId)
        readParticipant(participantId)
        throw AccessDeniedException(
            "No permission to add participant $participantId to cohort $cohortId")
      }
    }
  }

  fun addOrganizationUser(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canAddOrganizationUser(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException("No permission to add users to organization $organizationId")
      }
    }
  }

  fun addParticipantProject(participantId: ParticipantId, projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canAddParticipantProject(participantId, projectId)) {
        readParticipant(participantId)
        readProject(projectId)
        throw AccessDeniedException("No permission to add project to participant $participantId")
      }
    }
  }

  fun addProjectInternalUser(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canAddProjectInternalUser(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to add internal user to project $projectId")
      }
    }
  }

  fun addTerraformationContact(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canAddTerraformationContact(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to add terraformation contact to organization $organizationId")
      }
    }
  }

  fun countNotifications() {
    user.recordPermissionChecks {
      if (!user.canCountNotifications()) {
        throw AccessDeniedException("No permission to count notifications")
      }
    }
  }

  fun createAccession(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canCreateAccession(facilityId)) {
        readFacility(facilityId)
        throw AccessDeniedException("No permission to create accessions in facility $facilityId")
      }
    }
  }

  fun createApiKey(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canCreateApiKey(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to create API keys in organization $organizationId")
      }
    }
  }

  fun createApplication(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canCreateApplication(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to create application for project $projectId")
      }
    }
  }

  fun createAutomation(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canCreateAutomation(facilityId)) {
        readFacility(facilityId)
        throw AccessDeniedException("No permission to create automations in facility $facilityId")
      }
    }
  }

  fun createBatch(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canCreateBatch(facilityId)) {
        readFacility(facilityId)
        throw AccessDeniedException("No permission to create seedling batch")
      }
    }
  }

  fun createCohort() {
    user.recordPermissionChecks {
      if (!user.canCreateCohort()) {
        throw AccessDeniedException("No permission to create cohort")
      }
    }
  }

  fun createCohortModule() {
    user.recordPermissionChecks {
      if (!user.canCreateCohortModule()) {
        throw AccessDeniedException("No permission to create cohort module")
      }
    }
  }

  fun createDelivery(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canCreateDelivery(plantingSiteId)) {
        readPlantingSite(plantingSiteId)
        throw AccessDeniedException(
            "No permission to create delivery at planting site $plantingSiteId")
      }
    }
  }

  fun createDevice(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canCreateDevice(facilityId)) {
        readFacility(facilityId)
        throw AccessDeniedException("No permission to create device in facility $facilityId")
      }
    }
  }

  fun createDeviceManager() {
    user.recordPermissionChecks {
      if (!user.canCreateDeviceManager()) {
        throw AccessDeniedException("No permission to create device manager")
      }
    }
  }

  fun createDocument() {
    user.recordPermissionChecks {
      if (!user.canCreateDocument()) {
        throw AccessDeniedException("No permission to create documents")
      }
    }
  }

  fun createDraftPlantingSite(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canCreateDraftPlantingSite(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to create draft planting site in organization $organizationId")
      }
    }
  }

  fun createEntityWithOwner(userId: UserId) {
    user.recordPermissionChecks {
      if (!user.canCreateEntityWithOwner(userId)) {
        readUser(userId)
        throw AccessDeniedException("No permission to create entity with owner $userId")
      }
    }
  }

  fun createFacility(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canCreateFacility(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to create facilities in organization $organizationId")
      }
    }
  }

  fun createFundingEntities() {
    user.recordPermissionChecks {
      if (!user.canCreateFundingEntities()) {
        throw AccessDeniedException("No permission to create funding entities")
      }
    }
  }

  fun createNotification(userId: UserId) {
    if (!user.canCreateNotification(userId)) {
      throw AccessDeniedException("No permission to create notification")
    }
  }

  fun createObservation(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canCreateObservation(plantingSiteId)) {
        readPlantingSite(plantingSiteId)
        throw AccessDeniedException("No permission to create observation")
      }
    }
  }

  fun createParticipant() {
    user.recordPermissionChecks {
      if (!user.canCreateParticipant()) {
        throw AccessDeniedException("No permission to create participant")
      }
    }
  }

  fun createParticipantProjectSpecies(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canCreateParticipantProjectSpecies(projectId)) {
        throw AccessDeniedException("No permission to create participant project species")
      }
    }
  }

  fun createPlantingSite(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canCreatePlantingSite(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to create planting sites in organization $organizationId")
      }
    }
  }

  fun createProject(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canCreateProject(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to create projects in organization $organizationId")
      }
    }
  }

  fun createSavedVersion(documentId: DocumentId) {
    user.recordPermissionChecks {
      if (!user.canCreateSavedVersion(documentId)) {
        throw AccessDeniedException("No permission to create saved versions")
      }
    }
  }

  fun createSeedFundReport(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canCreateSeedFundReport(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to create seed fund reports in organization $organizationId")
      }
    }
  }

  fun createSpecies(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canCreateSpecies(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException("No permission to create species")
      }
    }
  }

  fun createSubLocation(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canCreateSubLocation(facilityId)) {
        readFacility(facilityId)
        throw AccessDeniedException("No permission to create sub-location at facility $facilityId")
      }
    }
  }

  fun createSubmission(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canCreateSubmission(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to create submission for project $projectId")
      }
    }
  }

  fun createTimeseries(deviceId: DeviceId) {
    user.recordPermissionChecks {
      if (!user.canCreateTimeseries(deviceId)) {
        readDevice(deviceId)
        throw AccessDeniedException("No permission to create timeseries on device $deviceId")
      }
    }
  }

  fun createVariableManifest() {
    user.recordPermissionChecks {
      if (!user.canCreateVariableManifest()) {
        throw AccessDeniedException("No permission to create Variable Manifests")
      }
    }
  }

  fun createWithdrawalPhoto(withdrawalId: WithdrawalId) {
    user.recordPermissionChecks {
      if (!user.canCreateWithdrawalPhoto(withdrawalId)) {
        readWithdrawal(withdrawalId)
        throw AccessDeniedException("No permission to create photo on withdrawal $withdrawalId")
      }
    }
  }

  fun deleteAccession(accessionId: AccessionId) {
    user.recordPermissionChecks {
      if (!user.canDeleteAccession(accessionId)) {
        readAccession(accessionId)
        throw AccessDeniedException("No permission to delete accession $accessionId")
      }
    }
  }

  fun deleteAutomation(automationId: AutomationId) {
    user.recordPermissionChecks {
      if (!user.canDeleteAutomation(automationId)) {
        readAutomation(automationId)
        throw AccessDeniedException("No permission to delete automation $automationId")
      }
    }
  }

  fun deleteBatch(batchId: BatchId) {
    user.recordPermissionChecks {
      if (!user.canDeleteBatch(batchId)) {
        readBatch(batchId)
        throw AccessDeniedException("No permission to delete seedling batch $batchId")
      }
    }
  }

  fun deleteCohort(cohortId: CohortId) {
    user.recordPermissionChecks {
      if (!user.canDeleteCohort(cohortId)) {
        readCohort(cohortId)
        throw AccessDeniedException("No permission to delete cohort $cohortId")
      }
    }
  }

  fun deleteCohortParticipant(cohortId: CohortId, participantId: ParticipantId) {
    user.recordPermissionChecks {
      if (!user.canDeleteCohortParticipant(cohortId, participantId)) {
        readCohort(cohortId)
        readParticipant(participantId)
        throw AccessDeniedException("No permission to delete cohort $cohortId")
      }
    }
  }

  fun deleteDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canDeleteDraftPlantingSite(draftPlantingSiteId)) {
        readDraftPlantingSite(draftPlantingSiteId)
        throw AccessDeniedException(
            "No permission to delete draft planting site $draftPlantingSiteId")
      }
    }
  }

  fun deleteFunder(userId: UserId) {
    user.recordPermissionChecks {
      if (!user.canDeleteFunder(userId)) {
        throw AccessDeniedException("No permission to delete funder $userId")
      }
    }
  }

  fun deleteFundingEntities() {
    user.recordPermissionChecks {
      if (!user.canDeleteFundingEntities()) {
        throw AccessDeniedException("No permission to delete funding entities")
      }
    }
  }

  fun deleteOrganization(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canDeleteOrganization(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException("No permission to delete organization $organizationId")
      }
    }
  }

  fun deleteParticipant(participantId: ParticipantId) {
    user.recordPermissionChecks {
      if (!user.canDeleteParticipant(participantId)) {
        readParticipant(participantId)
        throw AccessDeniedException("No permission to delete participant $participantId")
      }
    }
  }

  fun deleteParticipantProject(participantId: ParticipantId, projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canDeleteParticipantProject(participantId, projectId)) {
        readParticipant(participantId)
        readProject(projectId)
        throw AccessDeniedException(
            "No permission to delete project from participant $participantId")
      }
    }
  }

  fun deleteParticipantProjectSpecies(participantProjectSpeciesId: ParticipantProjectSpeciesId) {
    user.recordPermissionChecks {
      if (!user.canDeleteParticipantProjectSpecies(participantProjectSpeciesId)) {
        readParticipantProjectSpecies(participantProjectSpeciesId)
        throw AccessDeniedException(
            "No permission to delete participant project species $participantProjectSpeciesId")
      }
    }
  }

  fun deletePlantingSite(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canDeletePlantingSite(plantingSiteId)) {
        readPlantingSite(plantingSiteId)
        throw AccessDeniedException("No permission to delete planting site $plantingSiteId")
      }
    }
  }

  fun deleteProject(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canDeleteProject(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to delete project $projectId")
      }
    }
  }

  fun deleteSeedFundReport(reportId: SeedFundReportId) {
    user.recordPermissionChecks {
      if (!user.canDeleteSeedFundReport(reportId)) {
        readSeedFundReport(reportId)
        throw AccessDeniedException("No permission to delete seed fund report $reportId")
      }
    }
  }

  fun deleteSelf() {
    user.recordPermissionChecks {
      if (!user.canDeleteSelf()) {
        throw AccessDeniedException("No permission to delete self")
      }
    }
  }

  fun deleteSpecies(speciesId: SpeciesId) {
    user.recordPermissionChecks {
      if (!user.canDeleteSpecies(speciesId)) {
        readSpecies(speciesId)
        throw AccessDeniedException("No permission to delete species $speciesId")
      }
    }
  }

  fun deleteSubLocation(subLocationId: SubLocationId) {
    user.recordPermissionChecks {
      if (!user.canDeleteSubLocation(subLocationId)) {
        readSubLocation(subLocationId)
        throw AccessDeniedException("No permission to delete sub-location")
      }
    }
  }

  fun deleteSupportIssue() {
    user.recordPermissionChecks {
      if (!user.canDeleteSupportIssue()) {
        throw AccessDeniedException("No permission to delete a support issue")
      }
    }
  }

  fun deleteUpload(uploadId: UploadId) {
    user.recordPermissionChecks {
      if (!user.canDeleteUpload(uploadId)) {
        readUpload(uploadId)
        throw AccessDeniedException("No permission to delete upload")
      }
    }
  }

  fun deleteUsers() {
    user.recordPermissionChecks {
      if (!user.canDeleteUsers()) {
        throw AccessDeniedException("No permission to delete users")
      }
    }
  }

  fun importGlobalSpeciesData() {
    user.recordPermissionChecks {
      if (!user.canImportGlobalSpeciesData()) {
        throw AccessDeniedException("No permission to import global species data")
      }
    }
  }

  fun listAutomations(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canListAutomations(facilityId)) {
        readFacility(facilityId)
        throw AccessDeniedException("No permission to list automations in facility $facilityId")
      }
    }
  }

  fun listFundingEntityUsers(entityId: FundingEntityId) {
    user.recordPermissionChecks {
      if (!user.canListFundingEntityUsers(entityId)) {
        readFundingEntity(entityId)
        throw AccessDeniedException("No permission to list funders in funding entity $entityId")
      }
    }
  }

  fun listNotifications(organizationId: OrganizationId?) {
    user.recordPermissionChecks {
      if (!user.canListNotifications(organizationId)) {
        if (organizationId != null) {
          readOrganization(organizationId)
        }
        throw AccessDeniedException("No permission to list notifications")
      }
    }
  }

  fun listOrganizationUsers(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canListOrganizationUsers(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException("No permission to list users in organization $organizationId")
      }
    }
  }

  fun listSeedFundReports(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canListSeedFundReports(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to list seed fund reports in organization $organizationId")
      }
    }
  }

  fun manageDefaultProjectLeads() {
    user.recordPermissionChecks {
      if (!user.canManageDefaultProjectLeads()) {
        throw AccessDeniedException("No permission to manage default project leads")
      }
    }
  }

  fun manageDeliverables() {
    user.recordPermissionChecks {
      if (!user.canManageDeliverables()) {
        throw AccessDeniedException("No permission to manage deliverables")
      }
    }
  }

  fun manageDisclaimers() {
    user.recordPermissionChecks {
      if (!user.canManageDisclaimers()) {
        throw AccessDeniedException("No permission to manage disclaimers")
      }
    }
  }

  fun manageInternalTags() {
    user.recordPermissionChecks {
      if (!user.canManageInternalTags()) {
        throw AccessDeniedException("No permission to manage internal tags")
      }
    }
  }

  fun manageModuleEvents() {
    user.recordPermissionChecks {
      if (!user.canManageModuleEvents()) {
        throw AccessDeniedException("No permission to manage module events")
      }
    }
  }

  fun manageModuleEventStatuses() {
    user.recordPermissionChecks {
      if (!user.canManageModuleEventStatuses()) {
        throw AccessDeniedException("No permission to manage module event statuses")
      }
    }
  }

  fun manageModules() {
    user.recordPermissionChecks {
      if (!user.canManageModules()) {
        throw AccessDeniedException("No permission to manage modules")
      }
    }
  }

  fun manageNotifications() {
    user.recordPermissionChecks {
      if (!user.canManageNotifications()) {
        throw AccessDeniedException("No permissions to manage notifications")
      }
    }
  }

  fun manageObservation(observationId: ObservationId) {
    user.recordPermissionChecks {
      if (!user.canManageObservation(observationId)) {
        readObservation(observationId)
        throw AccessDeniedException("No permission to manage observation $observationId")
      }
    }
  }

  fun manageProjectReportConfigs() {
    user.recordPermissionChecks {
      if (!user.canManageProjectReportConfigs()) {
        throw AccessDeniedException("No permission to manage project report configurations")
      }
    }
  }

  fun movePlantingSiteToAnyOrg(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canMovePlantingSiteToAnyOrg(plantingSiteId)) {
        readPlantingSite(plantingSiteId)
        throw AccessDeniedException("No permission to move planting site $plantingSiteId")
      }
    }
  }

  fun notifyUpcomingReports() {
    user.recordPermissionChecks {
      if (!user.canNotifyUpcomingReports()) {
        throw AccessDeniedException("No permission to notify upcoming reports.")
      }
    }
  }

  fun proxyGeoServerGetRequests() {
    user.recordPermissionChecks {
      if (!user.canProxyGeoServerGetRequests()) {
        throw AccessDeniedException("No permission to proxy GET requests to GeoServer")
      }
    }
  }

  fun publishProjectProfileDetails() {
    user.recordPermissionChecks {
      if (!user.canPublishProjectProfileDetails()) {
        throw AccessDeniedException("No permission to publish project profile details.")
      }
    }
  }

  fun publishReports() {
    user.recordPermissionChecks {
      if (!user.canPublishReports()) {
        throw AccessDeniedException("No permission to publish reports.")
      }
    }
  }

  fun readAccession(accessionId: AccessionId) {
    user.recordPermissionChecks {
      if (!user.canReadAccession(accessionId)) {
        throw AccessionNotFoundException(accessionId)
      }
    }
  }

  fun readAllAcceleratorDetails() {
    user.recordPermissionChecks {
      if (!user.canReadAllAcceleratorDetails()) {
        throw AccessDeniedException("No permission to read accelerator details")
      }
    }
  }

  fun readAllDeliverables() {
    user.recordPermissionChecks {
      if (!user.canReadAllDeliverables()) {
        throw AccessDeniedException("No permission to read all deliverables")
      }
    }
  }

  fun readApplication(applicationId: ApplicationId) {
    user.recordPermissionChecks {
      if (!user.canReadApplication(applicationId)) {
        throw ApplicationNotFoundException(applicationId)
      }
    }
  }

  fun readAutomation(automationId: AutomationId) {
    user.recordPermissionChecks {
      if (!user.canReadAutomation(automationId)) {
        throw AutomationNotFoundException(automationId)
      }
    }
  }

  fun readBatch(batchId: BatchId) {
    user.recordPermissionChecks {
      if (!user.canReadBatch(batchId)) {
        throw BatchNotFoundException(batchId)
      }
    }
  }

  fun readCohort(cohortId: CohortId) {
    user.recordPermissionChecks {
      if (!user.canReadCohort(cohortId)) {
        throw CohortNotFoundException(cohortId)
      }
    }
  }

  fun readCohortParticipants(cohortId: CohortId) {
    user.recordPermissionChecks {
      if (!user.canReadCohortParticipants(cohortId)) {
        readCohort(cohortId)
        throw AccessDeniedException("No permission to read participants for cohort $cohortId")
      }
    }
  }

  fun readCohorts() {
    user.recordPermissionChecks {
      if (!user.canReadCohorts()) {
        throw AccessDeniedException("No permission to read cohorts")
      }
    }
  }

  fun readCurrentDisclaimer() {
    user.recordPermissionChecks {
      if (!user.canReadCurrentDisclaimer()) {
        throw AccessDeniedException("No permission to read current disclaimer")
      }
    }
  }

  fun readDefaultVoters() {
    user.recordPermissionChecks {
      if (!user.canReadDefaultVoters()) {
        throw AccessDeniedException("No permission to read default voters")
      }
    }
  }

  fun readDelivery(deliveryId: DeliveryId) {
    user.recordPermissionChecks {
      if (!user.canReadDelivery(deliveryId)) {
        throw DeliveryNotFoundException(deliveryId)
      }
    }
  }

  fun readDevice(deviceId: DeviceId) {
    user.recordPermissionChecks {
      if (!user.canReadDevice(deviceId)) {
        throw DeviceNotFoundException(deviceId)
      }
    }
  }

  fun readDeviceManager(deviceManagerId: DeviceManagerId) {
    user.recordPermissionChecks {
      if (!user.canReadDeviceManager(deviceManagerId)) {
        throw DeviceManagerNotFoundException(deviceManagerId)
      }
    }
  }

  fun readDocument(documentId: DocumentId) {
    user.recordPermissionChecks {
      if (!user.canReadDocument(documentId)) {
        throw DocumentNotFoundException(documentId)
      }
    }
  }

  fun readDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canReadDraftPlantingSite(draftPlantingSiteId)) {
        throw DraftPlantingSiteNotFoundException(draftPlantingSiteId)
      }
    }
  }

  fun readFacility(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canReadFacility(facilityId)) {
        throw FacilityNotFoundException(facilityId)
      }
    }
  }

  fun readFundingEntities() {
    user.recordPermissionChecks {
      if (!user.canReadFundingEntities()) {
        throw AccessDeniedException("No permission to read funding entities")
      }
    }
  }

  fun readFundingEntity(entityId: FundingEntityId) {
    user.recordPermissionChecks {
      if (!user.canReadFundingEntity(entityId)) {
        throw FundingEntityNotFoundException(entityId)
      }
    }
  }

  fun readGlobalRoles() {
    user.recordPermissionChecks {
      if (!user.canReadGlobalRoles()) {
        throw AccessDeniedException("No permission to read global roles")
      }
    }
  }

  fun readInternalOnlyVariables() {
    user.recordPermissionChecks {
      if (!user.canReadInternalOnlyVariables()) {
        throw AccessDeniedException("No permission to read internal variables")
      }
    }
  }

  fun readInternalTags() {
    user.recordPermissionChecks {
      if (!user.canReadInternalTags()) {
        throw AccessDeniedException("No permission to read internal tags")
      }
    }
  }

  fun readModule(moduleId: ModuleId) {
    user.recordPermissionChecks {
      if (!user.canReadModule(moduleId)) {
        throw ModuleNotFoundException(moduleId)
      }
    }
  }

  fun readModuleDetails(moduleId: ModuleId) {
    user.recordPermissionChecks {
      if (!user.canReadModuleDetails(moduleId)) {
        readModule(moduleId)
        throw AccessDeniedException("No permission to read module details")
      }
    }
  }

  fun readModuleEvent(eventId: EventId) {
    user.recordPermissionChecks {
      if (!user.canReadModuleEvent(eventId)) {
        throw EventNotFoundException(eventId)
      }
    }
  }

  fun readModuleEventParticipants() {
    user.recordPermissionChecks {
      if (!user.canReadModuleEventParticipants()) {
        throw AccessDeniedException("No permission to view event participants")
      }
    }
  }

  fun readMonitoringPlot(monitoringPlotId: MonitoringPlotId) {
    user.recordPermissionChecks {
      if (!user.canReadMonitoringPlot(monitoringPlotId)) {
        throw PlotNotFoundException(monitoringPlotId)
      }
    }
  }

  fun readNotification(notificationId: NotificationId) {
    user.recordPermissionChecks {
      if (!user.canReadNotification(notificationId)) {
        throw NotificationNotFoundException(notificationId)
      }
    }
  }

  fun readObservation(observationId: ObservationId) {
    user.recordPermissionChecks {
      if (!user.canReadObservation(observationId)) {
        throw ObservationNotFoundException(observationId)
      }
    }
  }

  fun readOrganization(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canReadOrganization(organizationId)) {
        throw OrganizationNotFoundException(organizationId)
      }
    }
  }

  fun readOrganizationDeliverables(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canReadOrganizationDeliverables(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to read deliverables for organization $organizationId")
      }
    }
  }

  fun readOrganizationFeatures(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canReadOrganizationFeatures(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to read features for organization $organizationId")
      }
    }
  }

  fun readOrganizationUser(organizationId: OrganizationId, userId: UserId) {
    user.recordPermissionChecks {
      if (!user.canReadOrganizationUser(organizationId, userId)) {
        readOrganization(organizationId)
        throw UserNotFoundException(userId)
      }
    }
  }

  fun readParticipant(participantId: ParticipantId) {
    user.recordPermissionChecks {
      if (!user.canReadParticipant(participantId)) {
        throw ParticipantNotFoundException(participantId)
      }
    }
  }

  fun readParticipantProjectSpecies(participantProjectSpeciesId: ParticipantProjectSpeciesId) {
    user.recordPermissionChecks {
      if (!user.canReadParticipantProjectSpecies(participantProjectSpeciesId)) {
        throw ParticipantProjectSpeciesNotFoundException(participantProjectSpeciesId)
      }
    }
  }

  fun readPlanting(plantingId: PlantingId) {
    user.recordPermissionChecks {
      if (!user.canReadPlanting(plantingId)) {
        throw PlantingNotFoundException(plantingId)
      }
    }
  }

  fun readPlantingSite(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canReadPlantingSite(plantingSiteId)) {
        throw PlantingSiteNotFoundException(plantingSiteId)
      }
    }
  }

  fun readPlantingSubzone(plantingSubzoneId: PlantingSubzoneId) {
    user.recordPermissionChecks {
      if (!user.canReadPlantingSubzone(plantingSubzoneId)) {
        throw PlantingSubzoneNotFoundException(plantingSubzoneId)
      }
    }
  }

  fun readPlantingZone(plantingZoneId: PlantingZoneId) {
    user.recordPermissionChecks {
      if (!user.canReadPlantingZone(plantingZoneId)) {
        throw PlantingZoneNotFoundException(plantingZoneId)
      }
    }
  }

  fun readProject(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadProject(projectId)) {
        throw ProjectNotFoundException(projectId)
      }
    }
  }

  fun readProjectAcceleratorDetails(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadProjectAcceleratorDetails(projectId)) {
        readProject(projectId)
        throw AccessDeniedException(
            "No permission to read accelerator details for project $projectId")
      }
    }
  }

  fun readProjectFunderDetails(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadProjectFunderDetails(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to read funder details for project $projectId")
      }
    }
  }

  fun readProjectDeliverables(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadProjectDeliverables(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to read deliverables for project $projectId")
      }
    }
  }

  fun readProjectModules(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadProjectModules(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to read project modules")
      }
    }
  }

  fun readProjectReportConfigs() {
    user.recordPermissionChecks {
      if (!user.canReadProjectReportConfigs()) {
        throw AccessDeniedException("No permission to read project report configurations")
      }
    }
  }

  fun readProjectScores(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadProjectScores(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to view scores for project $projectId")
      }
    }
  }

  fun readInternalVariableWorkflowDetails(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadInternalVariableWorkflowDetails(projectId)) {
        readProject(projectId)
        throw AccessDeniedException(
            "No permission to read variable workflow details for project $projectId")
      }
    }
  }

  fun readProjectVotes(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadProjectVotes(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to view votes for project $projectId")
      }
    }
  }

  fun readPublishedProjects() {
    user.recordPermissionChecks {
      if (!user.canReadPublishedProjects()) {
        throw AccessDeniedException("No permission to read published projects")
      }
    }
  }

  fun readPublishedReports(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canReadPublishedReports(projectId)) {
        throw ProjectNotFoundException(projectId)
      }
    }
  }

  fun readReport(reportId: ReportId) {
    user.recordPermissionChecks {
      if (!user.canReadReport(reportId)) {
        throw ReportNotFoundException(reportId)
      }
    }
  }

  fun readSeedFundReport(reportId: SeedFundReportId) {
    user.recordPermissionChecks {
      if (!user.canReadSeedFundReport(reportId)) {
        throw SeedFundReportNotFoundException(reportId)
      }
    }
  }

  fun readSpecies(speciesId: SpeciesId) {
    user.recordPermissionChecks {
      if (!user.canReadSpecies(speciesId)) {
        throw SpeciesNotFoundException(speciesId)
      }
    }
  }

  fun readSubLocation(subLocationId: SubLocationId) {
    user.recordPermissionChecks {
      if (!user.canReadSubLocation(subLocationId)) {
        throw SubLocationNotFoundException(subLocationId)
      }
    }
  }

  fun readSubmission(submissionId: SubmissionId) {
    user.recordPermissionChecks {
      if (!user.canReadSubmission(submissionId)) {
        throw SubmissionNotFoundException(submissionId)
      }
    }
  }

  fun readSubmissionDocument(documentId: SubmissionDocumentId) {
    user.recordPermissionChecks {
      if (!user.canReadSubmissionDocument(documentId)) {
        throw SubmissionDocumentNotFoundException(documentId)
      }
    }
  }

  fun readTimeseries(deviceId: DeviceId) {
    user.recordPermissionChecks {
      if (!user.canReadTimeseries(deviceId)) {
        throw TimeseriesNotFoundException(deviceId)
      }
    }
  }

  fun readUpload(uploadId: UploadId) {
    user.recordPermissionChecks {
      if (!user.canReadUpload(uploadId)) {
        throw UploadNotFoundException(uploadId)
      }
    }
  }

  fun readUser(userId: UserId) {
    user.recordPermissionChecks {
      if (!user.canReadUser(userId)) {
        throw UserNotFoundException(userId)
      }
    }
  }

  fun readUserDeliverableInternalInterests(userId: UserId) {
    user.recordPermissionChecks {
      if (!user.canReadUserInternalInterests(userId)) {
        readUser(userId)
        throw AccessDeniedException("No permission to read internal interests for user $userId")
      }
    }
  }

  fun readViabilityTest(viabilityTestId: ViabilityTestId) {
    user.recordPermissionChecks {
      if (!user.canReadViabilityTest(viabilityTestId)) {
        throw ViabilityTestNotFoundException(viabilityTestId)
      }
    }
  }

  fun readWithdrawal(withdrawalId: WithdrawalId) {
    user.recordPermissionChecks {
      if (!user.canReadWithdrawal(withdrawalId)) {
        throw WithdrawalNotFoundException(withdrawalId)
      }
    }
  }

  fun regenerateAllDeviceManagerTokens() {
    user.recordPermissionChecks {
      if (!user.canRegenerateAllDeviceManagerTokens()) {
        throw AccessDeniedException("No permission to regenerate all device manager tokens")
      }
    }
  }

  fun removeOrganizationUser(organizationId: OrganizationId, userId: UserId) {
    user.recordPermissionChecks {
      if (!user.canRemoveOrganizationUser(organizationId, userId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to remove user $userId from organization $organizationId")
      }
    }
  }

  fun removeTerraformationContact(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canRemoveTerraformationContact(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to remove Terraformation Contact from organization $organizationId")
      }
    }
  }

  fun replaceObservationPlot(observationId: ObservationId) {
    user.recordPermissionChecks {
      if (!user.canReplaceObservationPlot(observationId)) {
        readObservation(observationId)
        throw AccessDeniedException("No permission to replace plot in observation $observationId")
      }
    }
  }

  fun rescheduleObservation(observationId: ObservationId) {
    user.recordPermissionChecks {
      if (!user.canRescheduleObservation(observationId)) {
        readObservation(observationId)
        throw AccessDeniedException("No permission to reschedule observation $observationId")
      }
    }
  }

  fun reviewApplication(applicationId: ApplicationId) {
    user.recordPermissionChecks {
      if (!user.canReviewApplication(applicationId)) {
        readApplication(applicationId)
        throw AccessDeniedException("No permission to review application $applicationId")
      }
    }
  }

  fun reviewReports() {
    user.recordPermissionChecks {
      if (!user.canReviewReports()) {
        throw AccessDeniedException("No permission to review reports")
      }
    }
  }

  fun scheduleAdHocObservation(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canScheduleAdHocObservation(plantingSiteId)) {
        readPlantingSite(plantingSiteId)
        throw AccessDeniedException(
            "No permission to schedule ad-hoc observation for planting site $plantingSiteId")
      }
    }
  }

  fun scheduleObservation(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canScheduleObservation(plantingSiteId)) {
        readPlantingSite(plantingSiteId)
        throw AccessDeniedException(
            "No permission to schedule observation for planting site $plantingSiteId")
      }
    }
  }

  fun sendAlert(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canSendAlert(facilityId)) {
        readFacility(facilityId)
        throw AccessDeniedException("No permission to send alerts for facility $facilityId")
      }
    }
  }

  fun setOrganizationUserRole(organizationId: OrganizationId, role: Role) {
    user.recordPermissionChecks {
      if (!user.canSetOrganizationUserRole(organizationId, role)) {
        readOrganization(organizationId)
        throw AccessDeniedException(
            "No permission to grant role to users in organization $organizationId")
      }
    }
  }

  fun setTestClock() {
    user.recordPermissionChecks {
      if (!user.canSetTestClock()) {
        throw AccessDeniedException("No permission to set test clock")
      }
    }
  }

  fun setWithdrawalUser(accessionId: AccessionId) {
    user.recordPermissionChecks {
      if (!user.canSetWithdrawalUser(accessionId)) {
        readAccession(accessionId)
        throw AccessDeniedException(
            "No permission to set withdrawal user for accession $accessionId")
      }
    }
  }

  fun triggerAutomation(automationId: AutomationId) {
    user.recordPermissionChecks {
      if (!user.canTriggerAutomation(automationId)) {
        readAutomation(automationId)
        throw AccessDeniedException("No permission to trigger automation $automationId")
      }
    }
  }

  fun updateAccession(accessionId: AccessionId) {
    user.recordPermissionChecks {
      if (!user.canUpdateAccession(accessionId)) {
        readAccession(accessionId)
        throw AccessDeniedException("No permission to update accession $accessionId")
      }
    }
  }

  fun updateAccessionProject(accessionId: AccessionId) {
    user.recordPermissionChecks {
      if (!user.canUpdateAccessionProject(accessionId)) {
        readAccession(accessionId)
        throw AccessDeniedException("No permission to assign project to accession $accessionId")
      }
    }
  }

  fun updateApplicationBoundary(applicationId: ApplicationId) {
    user.recordPermissionChecks {
      if (!user.canUpdateApplicationBoundary(applicationId)) {
        readApplication(applicationId)
        throw AccessDeniedException(
            "No permission to update boundary for application $applicationId")
      }
    }
  }

  fun updateApplicationCountry(applicationId: ApplicationId) {
    user.recordPermissionChecks {
      if (!user.canUpdateApplicationCountry(applicationId)) {
        readApplication(applicationId)
        throw AccessDeniedException(
            "No permission to update country for application $applicationId")
      }
    }
  }

  fun updateApplicationSubmissionStatus(applicationId: ApplicationId) {
    user.recordPermissionChecks {
      if (!user.canUpdateApplicationSubmissionStatus(applicationId)) {
        readApplication(applicationId)
        throw AccessDeniedException(
            "No permission to update submission status for application $applicationId")
      }
    }
  }

  fun updateAppVersions() {
    user.recordPermissionChecks {
      if (!user.canUpdateAppVersions()) {
        throw AccessDeniedException("No permission to update app versions")
      }
    }
  }

  fun updateAutomation(automationId: AutomationId) {
    user.recordPermissionChecks {
      if (!user.canUpdateAutomation(automationId)) {
        readAutomation(automationId)
        throw AccessDeniedException("No permission to update automation $automationId")
      }
    }
  }

  fun updateBatch(batchId: BatchId) {
    user.recordPermissionChecks {
      if (!user.canUpdateBatch(batchId)) {
        readBatch(batchId)
        throw AccessDeniedException("No permission to update seedling batch $batchId")
      }
    }
  }

  fun updateCohort(cohortId: CohortId) {
    user.recordPermissionChecks {
      if (!user.canUpdateCohort(cohortId)) {
        readCohort(cohortId)
        throw AccessDeniedException("No permission to update cohort $cohortId")
      }
    }
  }

  fun updateDefaultVoters() {
    user.recordPermissionChecks {
      if (!user.canUpdateDefaultVoters()) {
        throw AccessDeniedException("No permission to update default voters")
      }
    }
  }

  fun updateDelivery(deliveryId: DeliveryId) {
    user.recordPermissionChecks {
      if (!user.canUpdateDelivery(deliveryId)) {
        readDelivery(deliveryId)
        throw AccessDeniedException("No permission to update delivery $deliveryId")
      }
    }
  }

  fun updateDevice(deviceId: DeviceId) {
    user.recordPermissionChecks {
      if (!user.canUpdateDevice(deviceId)) {
        readDevice(deviceId)
        throw AccessDeniedException("No permission to update device $deviceId")
      }
    }
  }

  fun updateDeviceManager(deviceManagerId: DeviceManagerId) {
    user.recordPermissionChecks {
      if (!user.canUpdateDeviceManager(deviceManagerId)) {
        readDeviceManager(deviceManagerId)
        throw AccessDeniedException("No permission to update device manager")
      }
    }
  }

  fun updateDeviceTemplates() {
    user.recordPermissionChecks {
      if (!user.canUpdateDeviceTemplates()) {
        throw AccessDeniedException("No permission to update device templates")
      }
    }
  }

  fun updateDocument(documentId: DocumentId) {
    user.recordPermissionChecks {
      if (!user.canUpdateDocument(documentId)) {
        throw AccessDeniedException("No permission to update document")
      }
    }
  }

  fun updateDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canUpdateDraftPlantingSite(draftPlantingSiteId)) {
        readDraftPlantingSite(draftPlantingSiteId)
        throw AccessDeniedException(
            "No permission to update draft planting site $draftPlantingSiteId")
      }
    }
  }

  fun updateFacility(facilityId: FacilityId) {
    user.recordPermissionChecks {
      if (!user.canUpdateFacility(facilityId)) {
        readFacility(facilityId)
        throw AccessDeniedException("No permission to update facility $facilityId")
      }
    }
  }

  fun updateFundingEntities() {
    user.recordPermissionChecks {
      if (!user.canUpdateFundingEntities()) {
        throw AccessDeniedException("No permission to update funding entities")
      }
    }
  }

  fun updateFundingEntityProjects() {
    user.recordPermissionChecks {
      if (!user.canUpdateFundingEntityProjects()) {
        throw AccessDeniedException("No permission to update funding entity projects")
      }
    }
  }

  fun updateFundingEntityUsers(fundingEntityId: FundingEntityId) {
    user.recordPermissionChecks {
      if (!user.canUpdateFundingEntityUsers(fundingEntityId)) {
        throw AccessDeniedException("No permission to update funding entity users")
      }
    }
  }

  fun updateGlobalRoles() {
    user.recordPermissionChecks {
      if (!user.canUpdateGlobalRoles()) {
        throw AccessDeniedException("No permission to update global roles")
      }
    }
  }

  fun updateMonitoringPlot(monitoringPlotId: MonitoringPlotId) {
    user.recordPermissionChecks {
      if (!user.canUpdateMonitoringPlot(monitoringPlotId)) {
        readMonitoringPlot(monitoringPlotId)
        throw AccessDeniedException("No permission to update monitoring plot $monitoringPlotId")
      }
    }
  }

  fun updateNotification(notificationId: NotificationId) {
    user.recordPermissionChecks {
      if (!user.canUpdateNotification(notificationId)) {
        readNotification(notificationId)
        throw AccessDeniedException("No permission to update notification")
      }
    }
  }

  fun updateNotifications(organizationId: OrganizationId?) {
    user.recordPermissionChecks {
      if (!user.canUpdateNotifications(organizationId)) {
        if (organizationId != null) {
          readOrganization(organizationId)
        }
        throw AccessDeniedException("No permission to update notifications")
      }
    }
  }

  fun updateObservation(observationId: ObservationId) {
    user.recordPermissionChecks {
      if (!user.canUpdateObservation(observationId)) {
        readObservation(observationId)
        throw AccessDeniedException("No permission to update observation $observationId")
      }
    }
  }

  fun updateOrganization(organizationId: OrganizationId) {
    user.recordPermissionChecks {
      if (!user.canUpdateOrganization(organizationId)) {
        readOrganization(organizationId)
        throw AccessDeniedException("No permission to update organization $organizationId")
      }
    }
  }

  fun updateParticipant(participantId: ParticipantId) {
    user.recordPermissionChecks {
      if (!user.canUpdateParticipant(participantId)) {
        readParticipant(participantId)
        throw AccessDeniedException("No permission to update participant $participantId")
      }
    }
  }

  fun updateParticipantProjectSpecies(participantProjectSpeciesId: ParticipantProjectSpeciesId) {
    user.recordPermissionChecks {
      if (!user.canUpdateParticipantProjectSpecies(participantProjectSpeciesId)) {
        readParticipantProjectSpecies(participantProjectSpeciesId)
        throw AccessDeniedException(
            "No permission to update participant project species $participantProjectSpeciesId")
      }
    }
  }

  fun updatePlantingSite(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canUpdatePlantingSite(plantingSiteId)) {
        readPlantingSite(plantingSiteId)
        throw AccessDeniedException("No permission to update planting site $plantingSiteId")
      }
    }
  }

  fun updatePlantingSiteProject(plantingSiteId: PlantingSiteId) {
    user.recordPermissionChecks {
      if (!user.canUpdatePlantingSiteProject(plantingSiteId)) {
        readPlantingSite(plantingSiteId)
        throw AccessDeniedException(
            "No permission to assign project to planting site $plantingSiteId")
      }
    }
  }

  fun updatePlantingSubzoneCompleted(plantingSubzoneId: PlantingSubzoneId) {
    user.recordPermissionChecks {
      if (!user.canUpdatePlantingSubzoneCompleted(plantingSubzoneId)) {
        readPlantingSubzone(plantingSubzoneId)
        throw AccessDeniedException("No permission to update planting subzone $plantingSubzoneId")
      }
    }
  }

  fun updatePlantingZone(plantingZoneId: PlantingZoneId) {
    user.recordPermissionChecks {
      if (!user.canUpdatePlantingZone(plantingZoneId)) {
        readPlantingZone(plantingZoneId)
        throw AccessDeniedException("No permission to update planting zone $plantingZoneId")
      }
    }
  }

  fun updateProject(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canUpdateProject(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to update project $projectId")
      }
    }
  }

  fun updateProjectAcceleratorDetails(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canUpdateProjectAcceleratorDetails(projectId)) {
        readProject(projectId)
        throw AccessDeniedException(
            "No permission to update accelerator details for project $projectId")
      }
    }
  }

  fun updateProjectDocumentSettings(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canUpdateProjectDocumentSettings(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to update project document settings $projectId")
      }
    }
  }

  fun updateProjectReports(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canUpdateProjectReports(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to update reports for project $projectId")
      }
    }
  }

  fun updateProjectScores(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canUpdateProjectScores(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to update scores for project $projectId")
      }
    }
  }

  fun updateInternalOnlyVariables() {
    user.recordPermissionChecks {
      if (!user.canUpdateInternalOnlyVariables()) {
        throw AccessDeniedException("No permission to update internal variables")
      }
    }
  }

  fun updateInternalVariableWorkflowDetails(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canUpdateInternalVariableWorkflowDetails(projectId)) {
        readProject(projectId)
        throw AccessDeniedException(
            "No permission to update variable workflow details for project $projectId")
      }
    }
  }

  fun updateProjectVotes(projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canUpdateProjectVotes(projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to update votes for project $projectId")
      }
    }
  }

  fun updateReport(reportId: ReportId) {
    user.recordPermissionChecks {
      if (!user.canUpdateReport(reportId)) {
        readReport(reportId)
        throw AccessDeniedException("No permission to update report $reportId")
      }
    }
  }

  fun updateSeedFundReport(reportId: SeedFundReportId) {
    user.recordPermissionChecks {
      if (!user.canUpdateSeedFundReport(reportId)) {
        readSeedFundReport(reportId)
        throw AccessDeniedException("No permission to update seed fund report $reportId")
      }
    }
  }

  fun updateSpecies(speciesId: SpeciesId) {
    user.recordPermissionChecks {
      if (!user.canUpdateSpecies(speciesId)) {
        readSpecies(speciesId)
        throw AccessDeniedException("No permission to update species $speciesId")
      }
    }
  }

  fun updateSpecificGlobalRoles(globalRoles: Set<GlobalRole>) {
    user.recordPermissionChecks {
      if (!user.canUpdateSpecificGlobalRoles(globalRoles)) {
        throw AccessDeniedException("No permission to update the provided global roles")
      }
    }
  }

  fun updateSubLocation(subLocationId: SubLocationId) {
    user.recordPermissionChecks {
      if (!user.canUpdateSubLocation(subLocationId)) {
        readSubLocation(subLocationId)
        throw AccessDeniedException("No permission to update sub-location")
      }
    }
  }

  fun updateSubmissionStatus(deliverableId: DeliverableId, projectId: ProjectId) {
    user.recordPermissionChecks {
      if (!user.canUpdateSubmissionStatus(deliverableId, projectId)) {
        readProject(projectId)
        throw AccessDeniedException("No permission to update submission status")
      }
    }
  }

  fun updateTimeseries(deviceId: DeviceId) {
    user.recordPermissionChecks {
      if (!user.canUpdateTimeseries(deviceId)) {
        readTimeseries(deviceId)
        throw AccessDeniedException("No permission to update timeseries on device $deviceId")
      }
    }
  }

  fun updateUpload(uploadId: UploadId) {
    user.recordPermissionChecks {
      if (!user.canUpdateUpload(uploadId)) {
        readUpload(uploadId)
        throw AccessDeniedException("No permission to update upload")
      }
    }
  }

  fun updateUserInternalInterests(userId: UserId) {
    user.recordPermissionChecks {
      if (!user.canUpdateUserInternalInterests(userId)) {
        readUser(userId)
        throw AccessDeniedException("No permission to update internal interests for user $userId")
      }
    }
  }

  fun uploadPhoto(accessionId: AccessionId) {
    user.recordPermissionChecks {
      if (!user.canUploadPhoto(accessionId)) {
        readAccession(accessionId)
        throw AccessDeniedException("No permission to upload photo for accession $accessionId")
      }
    }
  }
}
