package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.auth.currentUser
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
import com.terraformation.backend.db.default_schema.UserType
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
import com.terraformation.backend.log.perClassLogger
import java.security.Principal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * An entity on whose behalf the system can do work.
 *
 * The vast majority of the time, this will be a [IndividualUser] or [FunderUser], which represent a
 * human user. However, it can also be a [DeviceManagerUser] or the [SystemUser], which isn't
 * associated with a particular person or a particular organization.
 */
interface TerrawareUser : Principal, UserDetails {
  companion object {
    val log = perClassLogger()

    /**
     * Constructs a user's full name, if available. Currently this is just the first and last name
     * if both are set. Eventually this will need logic to deal with users in locales where names
     * aren't rendered the same way they are in English.
     *
     * It's possible for users to not have first or last names, e.g., if they were created by being
     * added to an organization and haven't gone through the registration flow yet; returns null in
     * that case. If the user has only a first name or only a last name, returns whichever name
     * exists.
     */
    fun makeFullName(firstName: String?, lastName: String?): String? =
        if (firstName != null && lastName != null) {
          "$firstName $lastName"
        } else {
          lastName ?: firstName
        }
  }

  val userId: UserId
  val userType: UserType
  val email: String

  val createdTime: Instant?
    get() = null

  val firstName: String?
    get() = null

  val lastName: String?
    get() = null

  val timeZone: ZoneId?
    get() = null

  val countryCode: String?
    get() = null

  val cookiesConsented: Boolean?
    get() = null

  val cookiesConsentedTime: Instant?
    get() = null

  val locale: Locale?
    get() = Locale.ENGLISH

  val emailNotificationsEnabled: Boolean
    get() = true

  /**
   * The user's Keycloak ID, if any. Null if this is an internal pseudo-user or if this user has
   * been invited to the system but has not yet registered with Keycloak.
   */
  val authId: String?

  override fun getName(): String = authId ?: throw IllegalStateException("User is unregistered")

  override fun getUsername(): String = authId ?: throw IllegalStateException("User is unregistered")

  val fullName: String?
    get() = makeFullName(firstName, lastName)

  /** The user's role in each organization they belong to. */
  val organizationRoles: Map<OrganizationId, Role>
    get() = emptyMap()

  /**
   * The user's role in each facility they have access to. Currently, roles are assigned
   * per-organization, so this is really the user's role in the organization that owns the project
   * and site of each facility.
   */
  val facilityRoles: Map<FacilityId, Role>
    get() = emptyMap()

  /** The user's funder entity. */
  val fundingEntityId: FundingEntityId?
    get() = null

  /** The user's global roles. These are not tied to organizations. */
  val globalRoles: Set<GlobalRole>
    get() = emptySet()

  override fun getAuthorities(): MutableCollection<out GrantedAuthority> = mutableSetOf()

  override fun getPassword(): String {
    log.warn("Something is trying to get the password of an OAuth2 user")
    return ""
  }

  override fun isAccountNonExpired(): Boolean = true

  override fun isAccountNonLocked(): Boolean = true

  override fun isCredentialsNonExpired(): Boolean = true

  override fun isEnabled(): Boolean = true

  /**
   * Runs some code as this user.
   *
   * This is useful in two scenarios. First, if the code isn't running on a request handler thread
   * (e.g., in a unit test or on a thread pool), any calls to [currentUser] will fail because there
   * won't be a current user.
   *
   * Second, less common, is masquerading: if there is already a current user, it will be replaced
   * with this user for the duration of the function, and then the current user will be restored
   * afterwards.
   */
  fun <T> run(func: () -> T): T = CurrentUserHolder.runAs(this, func, authorities)

  /** Returns true if the user is an admin or owner of any organizations. */
  fun hasAnyAdminRole(): Boolean = false

  /**
   * Returns the default permission value for this user. This is to support the system and device
   * manager user types, whose permissions are usually constant values with exceptions for specific
   * operations.
   */
  val defaultPermission: Boolean
    get() {
      throw NotImplementedError("Permission logic not implemented")
    }

  /** Returns true if the user has admin privileges in the organization. */
  fun isAdminOrHigher(organizationId: OrganizationId?): Boolean {
    return organizationId != null &&
        when (organizationRoles[organizationId]) {
          Role.Admin,
          Role.Owner,
          Role.TerraformationContact -> true
          else -> false
        }
  }

  /** Returns the organizations in which the user is an admin or owner. */
  fun adminOrganizations(): Set<OrganizationId> {
    return organizationRoles.keys.filter { isAdminOrHigher(it) }.toSet()
  }

  /**
   * Clears any cached information about what the user has permission to do.
   *
   * This is primarily for tests, but can also be called from any application code that makes
   * changes to the current user's permission-related data, e.g., joining a new organization.
   */
  fun clearCachedPermissions() {}

  fun <T> recordPermissionChecks(func: () -> T): T {
    return func()
  }

  /*
   * Permission checks. Each of these returns true if the user has permission to perform the action.
   */

  fun canAcceptCurrentDisclaimer(): Boolean = defaultPermission

  fun canAddAnyOrganizationUser(): Boolean = defaultPermission

  fun canAddCohortParticipant(cohortId: CohortId, participantId: ParticipantId): Boolean =
      defaultPermission

  fun canAddParticipantProject(participantId: ParticipantId, projectId: ProjectId): Boolean =
      defaultPermission

  fun canUpdateProjectInternalUsers(projectId: ProjectId): Boolean = defaultPermission

  fun canAddOrganizationUser(organizationId: OrganizationId): Boolean = defaultPermission

  fun canAddTerraformationContact(organizationId: OrganizationId): Boolean = defaultPermission

  fun canCountNotifications(): Boolean = defaultPermission

  fun canCreateAccession(facilityId: FacilityId): Boolean = defaultPermission

  fun canCreateApiKey(organizationId: OrganizationId): Boolean = defaultPermission

  fun canCreateApplication(projectId: ProjectId): Boolean = defaultPermission

  fun canCreateAutomation(facilityId: FacilityId): Boolean = defaultPermission

  fun canCreateBatch(facilityId: FacilityId): Boolean = defaultPermission

  fun canCreateCohort(): Boolean = defaultPermission

  fun canCreateCohortModule(): Boolean = defaultPermission

  fun canCreateDelivery(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canCreateDevice(facilityId: FacilityId): Boolean = defaultPermission

  fun canCreateDeviceManager(): Boolean = defaultPermission

  fun canCreateDocument(): Boolean = defaultPermission

  fun canCreateDraftPlantingSite(organizationId: OrganizationId): Boolean = defaultPermission

  fun canCreateEntityWithOwner(userId: UserId): Boolean = defaultPermission

  fun canCreateFacility(organizationId: OrganizationId): Boolean = defaultPermission

  fun canCreateFundingEntities(): Boolean = defaultPermission

  fun canCreateNotification(targetUserId: UserId): Boolean = defaultPermission

  fun canCreateObservation(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canCreateParticipant(): Boolean = defaultPermission

  fun canCreateParticipantProjectSpecies(projectId: ProjectId): Boolean = defaultPermission

  fun canCreatePlantingSite(organizationId: OrganizationId): Boolean = defaultPermission

  fun canCreateProject(organizationId: OrganizationId): Boolean = defaultPermission

  fun canCreateSavedVersion(documentId: DocumentId): Boolean = defaultPermission

  fun canCreateSeedFundReport(organizationId: OrganizationId): Boolean = defaultPermission

  fun canCreateSpecies(organizationId: OrganizationId): Boolean = defaultPermission

  fun canCreateSubLocation(facilityId: FacilityId): Boolean = defaultPermission

  fun canCreateSubmission(projectId: ProjectId): Boolean = defaultPermission

  fun canCreateTimeseries(deviceId: DeviceId): Boolean = defaultPermission

  fun canCreateVariableManifest(): Boolean = defaultPermission

  fun canCreateWithdrawalPhoto(withdrawalId: WithdrawalId): Boolean = defaultPermission

  fun canDeleteAccession(accessionId: AccessionId): Boolean = defaultPermission

  fun canDeleteAutomation(automationId: AutomationId): Boolean = defaultPermission

  fun canDeleteBatch(batchId: BatchId): Boolean = defaultPermission

  fun canDeleteCohort(cohortId: CohortId): Boolean = defaultPermission

  fun canDeleteCohortParticipant(cohortId: CohortId, participantId: ParticipantId): Boolean =
      defaultPermission

  fun canDeleteDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId): Boolean =
      defaultPermission

  fun canDeleteFunder(userId: UserId): Boolean = defaultPermission

  fun canDeleteFundingEntities(): Boolean = defaultPermission

  fun canDeleteOrganization(organizationId: OrganizationId): Boolean = defaultPermission

  fun canDeleteParticipant(participantId: ParticipantId): Boolean = defaultPermission

  fun canDeleteParticipantProject(participantId: ParticipantId, projectId: ProjectId): Boolean =
      defaultPermission

  fun canDeleteParticipantProjectSpecies(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ): Boolean = defaultPermission

  fun canDeletePlantingSite(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canDeleteProject(projectId: ProjectId): Boolean = defaultPermission

  fun canDeleteSeedFundReport(reportId: SeedFundReportId): Boolean = defaultPermission

  fun canDeleteSelf(): Boolean = defaultPermission

  fun canDeleteSpecies(speciesId: SpeciesId): Boolean = defaultPermission

  fun canDeleteSubLocation(subLocationId: SubLocationId): Boolean = defaultPermission

  fun canDeleteSupportIssue(): Boolean = defaultPermission

  fun canDeleteUpload(uploadId: UploadId): Boolean = defaultPermission

  fun canDeleteUsers(): Boolean = defaultPermission

  fun canImportGlobalSpeciesData(): Boolean = defaultPermission

  fun canListAutomations(facilityId: FacilityId): Boolean = defaultPermission

  fun canListFacilities(organizationId: OrganizationId): Boolean = defaultPermission

  fun canListFundingEntityUsers(entityId: FundingEntityId): Boolean = defaultPermission

  fun canListNotifications(organizationId: OrganizationId?): Boolean = defaultPermission

  fun canListOrganizationUsers(organizationId: OrganizationId): Boolean = defaultPermission

  fun canListSeedFundReports(organizationId: OrganizationId): Boolean = defaultPermission

  fun canManageDefaultProjectLeads(): Boolean = defaultPermission

  fun canManageDeliverables(): Boolean = defaultPermission

  fun canManageDisclaimers(): Boolean = defaultPermission

  fun canManageDocumentProducer(): Boolean = defaultPermission

  fun canManageInternalTags(): Boolean = defaultPermission

  fun canManageModuleEvents(): Boolean = defaultPermission

  fun canManageModuleEventStatuses(): Boolean = defaultPermission

  fun canManageModules(): Boolean = defaultPermission

  fun canManageNotifications(): Boolean = defaultPermission

  fun canManageObservation(observationId: ObservationId): Boolean = defaultPermission

  fun canManageProjectReportConfigs(): Boolean = defaultPermission

  fun canMovePlantingSiteToAnyOrg(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canNotifyUpcomingReports(): Boolean = defaultPermission

  fun canProxyGeoServerGetRequests(): Boolean = defaultPermission

  fun canPublishProjectProfileDetails(): Boolean = defaultPermission

  fun canPublishReports(): Boolean = defaultPermission

  fun canReadAccession(accessionId: AccessionId): Boolean = defaultPermission

  fun canReadAllAcceleratorDetails(): Boolean = defaultPermission

  fun canReadAllDeliverables(): Boolean = defaultPermission

  fun canReadApplication(applicationId: ApplicationId): Boolean = defaultPermission

  fun canReadAutomation(automationId: AutomationId): Boolean = defaultPermission

  fun canReadBatch(batchId: BatchId): Boolean = defaultPermission

  fun canReadCohort(cohortId: CohortId): Boolean = defaultPermission

  fun canReadCohortParticipants(cohortId: CohortId): Boolean = defaultPermission

  fun canReadCohorts(): Boolean = defaultPermission

  fun canReadCurrentDisclaimer(): Boolean = defaultPermission

  fun canReadDefaultVoters(): Boolean = defaultPermission

  fun canReadDelivery(deliveryId: DeliveryId): Boolean = defaultPermission

  fun canReadDevice(deviceId: DeviceId): Boolean = defaultPermission

  fun canReadDeviceManager(deviceManagerId: DeviceManagerId): Boolean = defaultPermission

  fun canReadDocument(documentId: DocumentId): Boolean = defaultPermission

  fun canReadDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId): Boolean =
      defaultPermission

  fun canReadFacility(facilityId: FacilityId): Boolean = defaultPermission

  fun canReadFundingEntities(): Boolean = defaultPermission

  fun canReadFundingEntity(entityId: FundingEntityId): Boolean = defaultPermission

  fun canReadGlobalRoles(): Boolean = defaultPermission

  fun canReadInternalOnlyVariables(): Boolean = defaultPermission

  fun canReadInternalTags(): Boolean = defaultPermission

  fun canReadInternalVariableWorkflowDetails(projectId: ProjectId): Boolean = defaultPermission

  fun canReadModule(moduleId: ModuleId): Boolean = defaultPermission

  fun canReadModuleDetails(moduleId: ModuleId): Boolean = defaultPermission

  fun canReadModuleEvent(eventId: EventId): Boolean = defaultPermission

  fun canReadModuleEventParticipants(): Boolean = defaultPermission

  fun canReadMonitoringPlot(monitoringPlotId: MonitoringPlotId): Boolean = defaultPermission

  fun canReadNotification(notificationId: NotificationId): Boolean = defaultPermission

  fun canReadObservation(observationId: ObservationId): Boolean = defaultPermission

  fun canReadOrganization(organizationId: OrganizationId): Boolean = defaultPermission

  fun canReadOrganizationDeliverables(organizationId: OrganizationId): Boolean = defaultPermission

  fun canReadOrganizationFeatures(organizationId: OrganizationId): Boolean = defaultPermission

  fun canReadOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean =
      defaultPermission

  fun canReadParticipant(participantId: ParticipantId): Boolean = defaultPermission

  fun canReadParticipantProjectSpecies(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ): Boolean = defaultPermission

  fun canReadPlanting(plantingId: PlantingId): Boolean = defaultPermission

  fun canReadPlantingSite(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canReadPlantingSubzone(plantingSubzoneId: PlantingSubzoneId): Boolean = defaultPermission

  fun canReadPlantingZone(plantingZoneId: PlantingZoneId): Boolean = defaultPermission

  fun canReadProject(projectId: ProjectId): Boolean = defaultPermission

  fun canReadProjectAcceleratorDetails(projectId: ProjectId): Boolean = defaultPermission

  fun canReadProjectFunderDetails(projectId: ProjectId): Boolean = defaultPermission

  fun canReadProjectDeliverables(projectId: ProjectId): Boolean = defaultPermission

  fun canReadProjectModules(projectId: ProjectId): Boolean = defaultPermission

  fun canReadProjectReportConfigs(): Boolean = defaultPermission

  fun canReadProjectScores(projectId: ProjectId): Boolean = defaultPermission

  fun canReadProjectVotes(projectId: ProjectId): Boolean = defaultPermission

  fun canReadPublishedProjects(): Boolean = defaultPermission

  fun canReadPublishedReports(projectId: ProjectId): Boolean = defaultPermission

  fun canReadReport(reportId: ReportId): Boolean = defaultPermission

  fun canReadReportInternalComments(): Boolean = defaultPermission

  fun canReadSeedFundReport(reportId: SeedFundReportId): Boolean = defaultPermission

  fun canReadSpecies(speciesId: SpeciesId): Boolean = defaultPermission

  fun canReadSubLocation(subLocationId: SubLocationId): Boolean = defaultPermission

  fun canReadSubmission(submissionId: SubmissionId): Boolean = defaultPermission

  fun canReadSubmissionDocument(documentId: SubmissionDocumentId): Boolean = defaultPermission

  fun canReadTimeseries(deviceId: DeviceId): Boolean = defaultPermission

  fun canReadUpload(uploadId: UploadId): Boolean = defaultPermission

  fun canReadUser(userId: UserId): Boolean = defaultPermission

  fun canReadUserInternalInterests(userId: UserId): Boolean = defaultPermission

  fun canReadViabilityTest(viabilityTestId: ViabilityTestId): Boolean = defaultPermission

  fun canReadWithdrawal(withdrawalId: WithdrawalId): Boolean = defaultPermission

  fun canRegenerateAllDeviceManagerTokens(): Boolean = defaultPermission

  fun canRemoveOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean =
      defaultPermission

  fun canRemoveTerraformationContact(organizationId: OrganizationId): Boolean = defaultPermission

  fun canReplaceObservationPlot(observationId: ObservationId): Boolean = defaultPermission

  fun canRescheduleObservation(observationId: ObservationId): Boolean = defaultPermission

  fun canReviewApplication(applicationId: ApplicationId): Boolean = defaultPermission

  fun canReviewReports(): Boolean = defaultPermission

  fun canScheduleAdHocObservation(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canScheduleObservation(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canSendAlert(facilityId: FacilityId): Boolean = defaultPermission

  fun canSetOrganizationUserRole(organizationId: OrganizationId, role: Role): Boolean =
      defaultPermission

  fun canSetTestClock(): Boolean = defaultPermission

  fun canSetWithdrawalUser(accessionId: AccessionId): Boolean = defaultPermission

  fun canTriggerAutomation(automationId: AutomationId): Boolean = defaultPermission

  fun canUpdateAccession(accessionId: AccessionId): Boolean = defaultPermission

  fun canUpdateAccessionProject(accessionId: AccessionId): Boolean = defaultPermission

  fun canUpdateApplicationBoundary(applicationId: ApplicationId): Boolean = defaultPermission

  fun canUpdateApplicationCountry(applicationId: ApplicationId): Boolean = defaultPermission

  fun canUpdateApplicationSubmissionStatus(applicationId: ApplicationId): Boolean =
      defaultPermission

  fun canUpdateAppVersions(): Boolean = defaultPermission

  fun canUpdateAutomation(automationId: AutomationId): Boolean = defaultPermission

  fun canUpdateBatch(batchId: BatchId): Boolean = defaultPermission

  fun canUpdateCohort(cohortId: CohortId): Boolean = defaultPermission

  fun canUpdateDefaultVoters(): Boolean = defaultPermission

  fun canUpdateDelivery(deliveryId: DeliveryId): Boolean = defaultPermission

  fun canUpdateDevice(deviceId: DeviceId): Boolean = defaultPermission

  fun canUpdateDeviceManager(deviceManagerId: DeviceManagerId): Boolean = defaultPermission

  fun canUpdateDeviceTemplates(): Boolean = defaultPermission

  fun canUpdateDocument(documentId: DocumentId): Boolean = defaultPermission

  fun canUpdateDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId): Boolean =
      defaultPermission

  fun canUpdateFacility(facilityId: FacilityId): Boolean = defaultPermission

  fun canUpdateFundingEntities(): Boolean = defaultPermission

  fun canUpdateFundingEntityProjects(): Boolean = defaultPermission

  fun canUpdateFundingEntityUsers(fundingEntityId: FundingEntityId): Boolean = defaultPermission

  fun canUpdateGlobalRoles(): Boolean = defaultPermission

  fun canUpdateInternalOnlyVariables(): Boolean = defaultPermission

  fun canUpdateInternalVariableWorkflowDetails(projectId: ProjectId): Boolean = defaultPermission

  fun canUpdateMonitoringPlot(monitoringPlotId: MonitoringPlotId): Boolean = defaultPermission

  fun canUpdateNotification(notificationId: NotificationId): Boolean = defaultPermission

  fun canUpdateNotifications(organizationId: OrganizationId?): Boolean = defaultPermission

  fun canUpdateObservation(observationId: ObservationId): Boolean = defaultPermission

  fun canUpdateOrganization(organizationId: OrganizationId): Boolean = defaultPermission

  fun canUpdateParticipant(participantId: ParticipantId): Boolean = defaultPermission

  fun canUpdateParticipantProjectSpecies(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ): Boolean = defaultPermission

  fun canUpdatePlantingSite(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canUpdatePlantingSiteProject(plantingSiteId: PlantingSiteId): Boolean = defaultPermission

  fun canUpdatePlantingSubzoneCompleted(plantingSubzoneId: PlantingSubzoneId): Boolean =
      defaultPermission

  fun canUpdatePlantingZone(plantingZoneId: PlantingZoneId): Boolean = defaultPermission

  fun canUpdateProject(projectId: ProjectId): Boolean = defaultPermission

  fun canUpdateProjectAcceleratorDetails(projectId: ProjectId): Boolean = defaultPermission

  fun canUpdateProjectDocumentSettings(projectId: ProjectId): Boolean = defaultPermission

  fun canUpdateProjectReports(projectId: ProjectId): Boolean = defaultPermission

  fun canUpdateProjectScores(projectId: ProjectId): Boolean = defaultPermission

  fun canUpdateProjectVotes(projectId: ProjectId): Boolean = defaultPermission

  fun canUpdateReport(reportId: ReportId): Boolean = defaultPermission

  fun canUpdateSeedFundReport(reportId: SeedFundReportId): Boolean = defaultPermission

  fun canUpdateSpecies(speciesId: SpeciesId): Boolean = defaultPermission

  fun canUpdateSpecificGlobalRoles(globalRoles: Set<GlobalRole>): Boolean = defaultPermission

  fun canUpdateSubLocation(subLocationId: SubLocationId): Boolean = defaultPermission

  fun canUpdateSubmissionStatus(deliverableId: DeliverableId, projectId: ProjectId): Boolean =
      defaultPermission

  fun canUpdateTimeseries(deviceId: DeviceId): Boolean = defaultPermission

  fun canUpdateUpload(uploadId: UploadId): Boolean = defaultPermission

  fun canUpdateUserInternalInterests(userId: UserId): Boolean = defaultPermission

  fun canUploadPhoto(accessionId: AccessionId): Boolean = defaultPermission

  // When adding new permissions, put them in alphabetical order in the above block.
}
