package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.SuperAdminAuthority
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.accelerator.ActivityId
import com.terraformation.backend.db.accelerator.ApplicationId
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.ModuleId
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
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.ResettableLazy
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.springframework.security.core.GrantedAuthority

/**
 * Details about the user who is making the current request and the permissions they have. This
 * always represents a regular (presumably human) user; device managers are represented by
 * [DeviceManagerUser].
 *
 * To get the current user's details, call [currentUser]. See that function's docs for some caveats,
 * but this is usually what you'll want to do.
 *
 * For permission checking where lack of permission should be treated as an error, you will probably
 * want to use [requirePermissions] instead of interacting directly with this class. See that
 * function's docs for more details.
 *
 * This class attempts to abstract away the implementation details of the permission checking
 * business logic. It has a bunch of methods like [canCreateAccession] to check for specific
 * permissions. In general, we want those methods to be as fine-grained as possible and to take
 * fine-grained context as parameters, even if the actual permissions are coarse-grained. The
 * calling code shouldn't have to make assumptions about how permissions are computed. For example,
 * the ability to create a facility may be determined by the user's organization-level role, but
 * [canCreateAccession] takes a facility ID argument. This way, if we add fine-grained permissions
 * later on, existing calls to [canCreateAccession] won't need to be modified.
 *
 * The permission-checking methods should never return true if the target object doesn't exist.
 * Callers can thus make use of the fact that a successful permission check means the target object
 * existed at the time the permission data was loaded.
 *
 * In addition to holding some basic details about the user, this object also serves as a
 * short-lived cache for information such as the user's roles in various contexts. For example, if
 * you access [facilityRoles] you will get a map of the user's role at each facility they have
 * access to. The first time you access that property, it will be fetched from the database, but it
 * will be cached afterward.
 */
data class IndividualUser(
    override val createdTime: Instant,
    override val userId: UserId,
    override val authId: String?,
    override val email: String,
    override val emailNotificationsEnabled: Boolean,
    override val firstName: String?,
    override val lastName: String?,
    override val countryCode: String?,
    override val cookiesConsented: Boolean?,
    override val cookiesConsentedTime: Instant?,
    override val locale: Locale?,
    override val timeZone: ZoneId?,
    override val userType: UserType,
    private val parentStore: ParentStore,
    private val permissionStore: PermissionStore,
) : TerrawareUser {
  companion object {
    val log = perClassLogger()
  }

  override val description: String
    get() = "user $email"

  private val _organizationRoles = ResettableLazy { permissionStore.fetchOrganizationRoles(userId) }
  override val organizationRoles: Map<OrganizationId, Role> by _organizationRoles

  private val _facilityRoles = ResettableLazy { permissionStore.fetchFacilityRoles(userId) }
  override val facilityRoles: Map<FacilityId, Role> by _facilityRoles

  private val _globalRoles = ResettableLazy { permissionStore.fetchGlobalRoles(userId) }
  override val globalRoles: Set<GlobalRole> by _globalRoles

  override fun clearCachedPermissions() {
    _organizationRoles.reset()
    _facilityRoles.reset()
    _globalRoles.reset()
  }

  /** Returns true if the user is an admin, owner or Terraformation Contact of any organizations. */
  override fun hasAnyAdminRole() =
      organizationRoles.values.any {
        it == Role.Owner || it == Role.Admin || it == Role.TerraformationContact
      }

  override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
    return if (isSuperAdmin()) {
      mutableSetOf(SuperAdminAuthority)
    } else {
      mutableSetOf()
    }
  }

  // Permissions
  override fun canAcceptCurrentDisclaimer() = false

  override fun canAddAnyOrganizationUser() = isSuperAdmin()

  override fun canAddCohortProject(cohortId: CohortId, projectId: ProjectId) = isAcceleratorAdmin()

  override fun canAddOrganizationUser(organizationId: OrganizationId) =
      isSuperAdmin() || isAdminOrHigher(organizationId)

  override fun canAddTerraformationContact(organizationId: OrganizationId) = isTFExpertOrHigher()

  // all users can count their unread notifications
  override fun canCountNotifications() = true

  override fun canCreateAccession(facilityId: FacilityId) = isMember(facilityId)

  override fun canCreateActivity(projectId: ProjectId) =
      isTFExpertOrHigher() || isAdminOrHigher(parentStore.getOrganizationId(projectId))

  override fun canCreateApiKey(organizationId: OrganizationId) = isAdminOrHigher(organizationId)

  override fun canCreateApplication(projectId: ProjectId) =
      isAdminOrHigher(parentStore.getOrganizationId(projectId))

  override fun canCreateAutomation(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canCreateBatch(facilityId: FacilityId) = isMember(facilityId)

  override fun canCreateCohort() = isAcceleratorAdmin()

  override fun canCreateCohortModule() = isAcceleratorAdmin()

  override fun canCreateDelivery(plantingSiteId: PlantingSiteId) = isManagerOrHigher(plantingSiteId)

  override fun canCreateDevice(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canCreateDeviceManager() = isSuperAdmin()

  override fun canCreateDocument() = isTFExpertOrHigher()

  override fun canCreateDraftPlantingSite(organizationId: OrganizationId) =
      isAdminOrHigher(organizationId)

  override fun canCreateEntityWithOwner(userId: UserId): Boolean {
    return userId == this.userId || isSuperAdmin()
  }

  override fun canCreateFacility(organizationId: OrganizationId) = isAdminOrHigher(organizationId)

  override fun canCreateFundingEntities() = isAcceleratorAdmin()

  override fun canCreateNotification(targetUserId: UserId): Boolean = false

  override fun canCreateObservation(plantingSiteId: PlantingSiteId) =
      isSuperAdmin() || isManagerOrHigher(plantingSiteId)

  override fun canCreateParticipantProjectSpecies(projectId: ProjectId) =
      isTFExpertOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(projectId))

  override fun canCreatePlantingSite(organizationId: OrganizationId) =
      isAdminOrHigher(organizationId)

  override fun canCreateProject(organizationId: OrganizationId) = isAdminOrHigher(organizationId)

  // Reports are normally created by the system, but can be created manually by super-admins.
  override fun canCreateSeedFundReport(organizationId: OrganizationId) = isSuperAdmin()

  override fun canCreateSavedVersion(documentId: DocumentId) = isTFExpertOrHigher()

  override fun canCreateSpecies(organizationId: OrganizationId) = isManagerOrHigher(organizationId)

  override fun canCreateSubLocation(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canCreateSubmission(projectId: ProjectId) =
      isTFExpertOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(projectId))

  override fun canCreateTimeseries(deviceId: DeviceId) =
      isAdminOrHigher(parentStore.getFacilityId(deviceId))

  override fun canCreateVariableManifest() = isAcceleratorAdmin()

  override fun canCreateWithdrawalPhoto(withdrawalId: WithdrawalId) =
      isMember(parentStore.getFacilityId(withdrawalId))

  override fun canDeleteAccession(accessionId: AccessionId) =
      isManagerOrHigher(parentStore.getFacilityId(accessionId))

  override fun canDeleteActivity(activityId: ActivityId) =
      isTFExpertOrHigher() || isAdminOrHigher(parentStore.getOrganizationId(activityId))

  override fun canDeleteAutomation(automationId: AutomationId) =
      isAdminOrHigher(parentStore.getFacilityId(automationId))

  override fun canDeleteBatch(batchId: BatchId) = isMember(parentStore.getFacilityId(batchId))

  override fun canDeleteCohort(cohortId: CohortId) = isAcceleratorAdmin()

  override fun canDeleteCohortProject(cohortId: CohortId, projectId: ProjectId) =
      isAcceleratorAdmin()

  override fun canDeleteDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) =
      userId == parentStore.getUserId(draftPlantingSiteId) &&
          isAdminOrHigher(parentStore.getOrganizationId(draftPlantingSiteId))

  override fun canDeleteFunder(userId: UserId) = isAcceleratorAdmin()

  override fun canDeleteFundingEntities() = isAcceleratorAdmin()

  override fun canDeleteOrganization(organizationId: OrganizationId) = isOwner(organizationId)

  override fun canDeleteParticipantProjectSpecies(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ) =
      isTFExpertOrHigher() ||
          isManagerOrHigher(parentStore.getOrganizationId(participantProjectSpeciesId))

  override fun canDeletePlantingSite(plantingSiteId: PlantingSiteId) =
      isAdminOrHigher(parentStore.getOrganizationId(plantingSiteId))

  override fun canDeleteProject(projectId: ProjectId) =
      isAdminOrHigher(parentStore.getOrganizationId(projectId))

  override fun canDeleteSeedFundReport(reportId: SeedFundReportId): Boolean =
      isAdminOrHigher(parentStore.getOrganizationId(reportId))

  override fun canDeleteSelf() = true

  override fun canDeleteSpecies(speciesId: SpeciesId) =
      isManagerOrHigher(parentStore.getOrganizationId(speciesId))

  override fun canDeleteSubLocation(subLocationId: SubLocationId) =
      isAdminOrHigher(parentStore.getFacilityId(subLocationId))

  override fun canDeleteSupportIssue(): Boolean = false

  override fun canDeleteUpload(uploadId: UploadId) = canReadUpload(uploadId)

  override fun canDeleteUsers(): Boolean = isSuperAdmin()

  override fun canImportGlobalSpeciesData() = isSuperAdmin()

  override fun canListActivities(projectId: ProjectId) =
      isReadOnlyOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(projectId))

  override fun canListAutomations(facilityId: FacilityId) = isMember(facilityId)

  override fun canListFacilities(organizationId: OrganizationId) =
      isGlobalReader(organizationId) || isMember(organizationId)

  override fun canListFundingEntityUsers(entityId: FundingEntityId) = isReadOnlyOrHigher()

  override fun canListNotifications(organizationId: OrganizationId?): Boolean {
    return if (organizationId == null) {
      // user can list global notifications relevant to user
      true
    } else {
      // user should belong to the organization otherwise
      isMember(organizationId)
    }
  }

  override fun canListOrganizationUsers(organizationId: OrganizationId) =
      isMember(organizationId) || isGlobalReader(organizationId)

  override fun canListPublishedActivities(projectId: ProjectId) =
      isReadOnlyOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(projectId))

  override fun canListSeedFundReports(organizationId: OrganizationId) =
      isAdminOrHigher(organizationId)

  override fun canManageActivity(activityId: ActivityId) = isTFExpertOrHigher()

  override fun canManageDeliverables() = isAcceleratorAdmin()

  override fun canManageDisclaimers() = isSuperAdmin()

  override fun canManageDocumentProducer() = isTFExpertOrHigher()

  override fun canManageInternalTags() = isSuperAdmin()

  override fun canManageModuleEvents() = isAcceleratorAdmin()

  override fun canManageModuleEventStatuses() = false

  override fun canManageModules() = isAcceleratorAdmin()

  override fun canManageNotifications() = false

  override fun canManageObservation(observationId: ObservationId) =
      isSuperAdmin() &&
          parentStore.getPlantingSiteId(observationId)?.let { canUpdatePlantingSite(it) } == true

  override fun canManageProjectReportConfigs() = isAcceleratorAdmin()

  override fun canMovePlantingSiteToAnyOrg(plantingSiteId: PlantingSiteId) =
      canUpdatePlantingSite(plantingSiteId) && isSuperAdmin()

  override fun canNotifyUpcomingReports(): Boolean = false

  override fun canProxyGeoServerGetRequests(): Boolean = isReadOnlyOrHigher()

  override fun canPublishProjectProfileDetails() = isAcceleratorAdmin()

  override fun canPublishReports() = isAcceleratorAdmin()

  override fun canReadAccession(accessionId: AccessionId) =
      canReadAcceleratorProject(parentStore.getProjectId(accessionId)) ||
          isMember(parentStore.getFacilityId(accessionId))

  override fun canReadActivity(activityId: ActivityId): Boolean {
    return isReadOnlyOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(activityId))
  }

  override fun canReadAllAcceleratorDetails() = isReadOnlyOrHigher()

  override fun canReadAllDeliverables() = isReadOnlyOrHigher()

  override fun canReadApplication(applicationId: ApplicationId) =
      isReadOnlyOrHigher() || isAdminOrHigher(parentStore.getOrganizationId(applicationId))

  override fun canReadAutomation(automationId: AutomationId) =
      isMember(parentStore.getFacilityId(automationId))

  override fun canReadBatch(batchId: BatchId) =
      canReadAcceleratorProject(parentStore.getProjectId(batchId)) ||
          isMember(parentStore.getFacilityId(batchId))

  override fun canReadCohort(cohortId: CohortId) =
      isReadOnlyOrHigher() || parentStore.exists(cohortId, userId)

  override fun canReadCohortProjects(cohortId: CohortId): Boolean = isReadOnlyOrHigher()

  override fun canReadCohorts(): Boolean = isReadOnlyOrHigher()

  override fun canReadCurrentDisclaimer(): Boolean = isSuperAdmin()

  override fun canReadDefaultVoters(): Boolean = isReadOnlyOrHigher()

  override fun canReadDelivery(deliveryId: DeliveryId) =
      canReadAcceleratorProject(parentStore.getProjectId(deliveryId)) ||
          isMember(parentStore.getOrganizationId(deliveryId))

  override fun canReadDevice(deviceId: DeviceId) = isMember(parentStore.getFacilityId(deviceId))

  override fun canReadDeviceManager(deviceManagerId: DeviceManagerId): Boolean {
    val facilityId = parentStore.getFacilityId(deviceManagerId)
    return if (facilityId != null) {
      isMember(facilityId)
    } else {
      parentStore.exists(deviceManagerId)
    }
  }

  override fun canReadDocument(documentId: DocumentId) = isReadOnlyOrHigher()

  override fun canReadDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) =
      canReadAcceleratorProject(parentStore.getProjectId(draftPlantingSiteId)) ||
          isManagerOrHigher(parentStore.getOrganizationId(draftPlantingSiteId))

  override fun canReadFacility(facilityId: FacilityId) =
      isMember(facilityId) || isGlobalReader(parentStore.getOrganizationId(facilityId))

  override fun canReadFundingEntity(entityId: FundingEntityId) = canReadFundingEntities()

  override fun canReadFundingEntities() = isReadOnlyOrHigher()

  override fun canReadGlobalRoles() = isTFExpertOrHigher()

  override fun canReadInternalOnlyVariables(): Boolean = isReadOnlyOrHigher()

  override fun canReadInternalVariableWorkflowDetails(projectId: ProjectId) = isReadOnlyOrHigher()

  override fun canReadModule(moduleId: ModuleId): Boolean {
    return parentStore.exists(moduleId, userId) || isReadOnlyOrHigher()
  }

  override fun canReadModuleDetails(moduleId: ModuleId): Boolean = isReadOnlyOrHigher()

  override fun canReadModuleEvent(eventId: EventId): Boolean {
    return parentStore.exists(eventId, userId) || isReadOnlyOrHigher()
  }

  override fun canReadModuleEventParticipants(): Boolean {
    return isReadOnlyOrHigher()
  }

  override fun canReadMonitoringPlot(monitoringPlotId: MonitoringPlotId) =
      canReadAcceleratorProject(parentStore.getProjectId(monitoringPlotId)) ||
          isMember(parentStore.getOrganizationId(monitoringPlotId))

  override fun canReadNotification(notificationId: NotificationId) =
      parentStore.getUserId(notificationId) == userId

  override fun canReadInternalTags() = isReadOnlyOrHigher()

  override fun canReadObservation(observationId: ObservationId) =
      canReadAcceleratorProject(parentStore.getProjectId(observationId)) ||
          isMember(parentStore.getOrganizationId(observationId))

  override fun canReadOrganization(organizationId: OrganizationId) =
      isMember(organizationId) || isGlobalReader(organizationId)

  override fun canReadOrganizationDeliverables(organizationId: OrganizationId): Boolean =
      isReadOnlyOrHigher() || isManagerOrHigher(organizationId)

  override fun canReadOrganizationFeatures(organizationId: OrganizationId): Boolean =
      isManagerOrHigher(organizationId)

  override fun canReadOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean {
    return if (userId == this.userId) {
      isMember(organizationId)
    } else {
      canListOrganizationUsers(organizationId) && parentStore.exists(organizationId, userId)
    }
  }

  override fun canReadParticipantProjectSpecies(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ) = isReadOnlyOrHigher() || isMember(parentStore.getOrganizationId(participantProjectSpeciesId))

  override fun canReadPlanting(plantingId: PlantingId): Boolean =
      canReadAcceleratorProject(parentStore.getProjectId(plantingId)) ||
          isMember(parentStore.getOrganizationId(plantingId))

  override fun canReadPlantingSite(plantingSiteId: PlantingSiteId) =
      canReadAcceleratorProject(parentStore.getProjectId(plantingSiteId)) ||
          isMember(parentStore.getOrganizationId(plantingSiteId))

  override fun canReadProject(projectId: ProjectId): Boolean {
    val organizationId = parentStore.getOrganizationId(projectId) ?: return false
    return isMember(organizationId) ||
        canReadAcceleratorProject(projectId) ||
        isGlobalReader(organizationId)
  }

  override fun canReadProjectAcceleratorDetails(projectId: ProjectId): Boolean =
      isReadOnlyOrHigher()

  override fun canReadProjectFunderDetails(projectId: ProjectId): Boolean = isReadOnlyOrHigher()

  override fun canReadProjectDeliverables(projectId: ProjectId): Boolean =
      isReadOnlyOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(projectId))

  override fun canReadProjectModules(projectId: ProjectId): Boolean {
    val organizationId = parentStore.getOrganizationId(projectId) ?: return false
    return isMember(organizationId) ||
        canReadAcceleratorProject(projectId) ||
        isGlobalReader(organizationId)
  }

  override fun canReadProjectReportConfigs() = isReadOnlyOrHigher()

  override fun canReadProjectReports(projectId: ProjectId) =
      isReadOnlyOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(projectId))

  override fun canReadProjectScores(projectId: ProjectId) = isReadOnlyOrHigher()

  override fun canReadProjectVotes(projectId: ProjectId) = isReadOnlyOrHigher()

  override fun canReadPublishedActivity(activityId: ActivityId): Boolean {
    return isReadOnlyOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(activityId))
  }

  override fun canReadPublishedProjects() = isReadOnlyOrHigher()

  override fun canReadPublishedReport(reportId: ReportId) = isReadOnlyOrHigher()

  override fun canReadPublishedReports(projectId: ProjectId) = isReadOnlyOrHigher()

  override fun canReadReport(reportId: ReportId) =
      isReadOnlyOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(reportId))

  override fun canReadReportInternalComments() = isReadOnlyOrHigher()

  override fun canReadSeedFundReport(reportId: SeedFundReportId) =
      isAdminOrHigher(parentStore.getOrganizationId(reportId))

  // If this logic changes, make sure to also change code that bakes this rule into SQL queries
  // for efficiency. Example: SpeciesStore.fetchUncheckedSpeciesIds
  override fun canReadSpecies(speciesId: SpeciesId): Boolean {
    val organizationId = parentStore.getOrganizationId(speciesId) ?: return false
    return isMember(organizationId) || isGlobalReader(organizationId)
  }

  override fun canReadStratum(stratumId: StratumId) =
      canReadAcceleratorProject(parentStore.getProjectId(stratumId)) ||
          isMember(parentStore.getOrganizationId(stratumId))

  override fun canReadSubLocation(subLocationId: SubLocationId): Boolean {
    val facilityId = parentStore.getFacilityId(subLocationId) ?: return false
    return isMember(facilityId) || isGlobalReader(parentStore.getOrganizationId(facilityId))
  }

  override fun canReadSubmission(submissionId: SubmissionId) =
      isReadOnlyOrHigher() || isMember(parentStore.getOrganizationId(submissionId))

  override fun canReadSubmissionDocument(documentId: SubmissionDocumentId) = isReadOnlyOrHigher()

  override fun canReadSubstratum(substratumId: SubstratumId) =
      canReadAcceleratorProject(parentStore.getProjectId(substratumId)) ||
          isMember(parentStore.getOrganizationId(substratumId))

  override fun canReadTimeseries(deviceId: DeviceId) = isMember(parentStore.getFacilityId(deviceId))

  override fun canReadUpload(uploadId: UploadId) = userId == parentStore.getUserId(uploadId)

  override fun canReadUser(userId: UserId) = this.userId == userId || isReadOnlyOrHigher()

  override fun canReadUserInternalInterests(userId: UserId) = isTFExpertOrHigher()

  override fun canReadViabilityTest(viabilityTestId: ViabilityTestId) =
      canReadAcceleratorProject(parentStore.getProjectId(viabilityTestId)) ||
          isMember(parentStore.getFacilityId(viabilityTestId))

  override fun canReadWithdrawal(withdrawalId: WithdrawalId): Boolean {
    val facilityId = parentStore.getFacilityId(withdrawalId) ?: return false
    return isMember(facilityId) || isGlobalReader(parentStore.getOrganizationId(facilityId))
  }

  override fun canRegenerateAllDeviceManagerTokens() = isSuperAdmin()

  override fun canRemoveOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean {
    return isSuperAdmin() ||
        (isMember(organizationId) && (userId == this.userId || isAdminOrHigher(organizationId)))
  }

  override fun canRemoveTerraformationContact(organizationId: OrganizationId) = isTFExpertOrHigher()

  override fun canReplaceObservationPlot(observationId: ObservationId) =
      isAdminOrHigher(parentStore.getOrganizationId(observationId))

  override fun canRescheduleObservation(observationId: ObservationId) =
      isSuperAdmin() || isAdminOrHigher(parentStore.getOrganizationId(observationId))

  override fun canReviewApplication(applicationId: ApplicationId) = isTFExpertOrHigher()

  override fun canReviewReports(): Boolean = isTFExpertOrHigher()

  override fun canScheduleAdHocObservation(plantingSiteId: PlantingSiteId) =
      isSuperAdmin() || isMember(parentStore.getOrganizationId(plantingSiteId))

  override fun canScheduleObservation(plantingSiteId: PlantingSiteId) =
      isSuperAdmin() || isAdminOrHigher(parentStore.getOrganizationId(plantingSiteId))

  override fun canSendAlert(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canSetOrganizationUserRole(organizationId: OrganizationId, role: Role) =
      isSuperAdmin() || isAdminOrHigher(organizationId)

  override fun canSetTestClock() = isSuperAdmin()

  override fun canSetWithdrawalUser(accessionId: AccessionId) =
      isManagerOrHigher(parentStore.getOrganizationId(accessionId))

  override fun canTriggerAutomation(automationId: AutomationId) =
      isAdminOrHigher(parentStore.getFacilityId(automationId))

  override fun canUpdateAccession(accessionId: AccessionId) =
      isManagerOrHigher(parentStore.getFacilityId(accessionId))

  override fun canUpdateAccessionProject(accessionId: AccessionId) =
      isMember(parentStore.getFacilityId(accessionId))

  override fun canUpdateActivity(activityId: ActivityId) =
      isTFExpertOrHigher() || isAdminOrHigher(parentStore.getOrganizationId(activityId))

  override fun canUpdateApplicationBoundary(applicationId: ApplicationId) =
      isTFExpertOrHigher() || isAdminOrHigher(parentStore.getOrganizationId(applicationId))

  override fun canUpdateApplicationCountry(applicationId: ApplicationId) =
      isTFExpertOrHigher() || isAdminOrHigher(parentStore.getOrganizationId(applicationId))

  override fun canUpdateApplicationSubmissionStatus(applicationId: ApplicationId) =
      isAdminOrHigher(parentStore.getOrganizationId(applicationId))

  override fun canUpdateAppVersions() = isSuperAdmin()

  override fun canUpdateAutomation(automationId: AutomationId) =
      isAdminOrHigher(parentStore.getFacilityId(automationId))

  // All users in the organization have read/write access to batches.
  override fun canUpdateBatch(batchId: BatchId) = isMember(parentStore.getFacilityId(batchId))

  override fun canUpdateCohort(cohortId: CohortId) = isAcceleratorAdmin()

  override fun canUpdateDefaultVoters(): Boolean = isAcceleratorAdmin()

  override fun canUpdateDelivery(deliveryId: DeliveryId) =
      isMember(parentStore.getOrganizationId(deliveryId))

  override fun canUpdateDevice(deviceId: DeviceId) =
      isAdminOrHigher(parentStore.getFacilityId(deviceId))

  override fun canUpdateDeviceManager(deviceManagerId: DeviceManagerId): Boolean {
    val facilityId = parentStore.getFacilityId(deviceManagerId)
    return if (facilityId != null) {
      isAdminOrHigher(facilityId)
    } else {
      hasAnyAdminRole()
    }
  }

  override fun canUpdateDeviceTemplates() = isSuperAdmin()

  override fun canUpdateDocument(documentId: DocumentId) = isTFExpertOrHigher()

  override fun canUpdateDraftPlantingSite(draftPlantingSiteId: DraftPlantingSiteId) =
      userId == parentStore.getUserId(draftPlantingSiteId) &&
          isAdminOrHigher(parentStore.getOrganizationId(draftPlantingSiteId))

  override fun canUpdateFacility(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canUpdateFundingEntities() = isAcceleratorAdmin()

  override fun canUpdateFundingEntityProjects() = isAcceleratorAdmin()

  override fun canUpdateFundingEntityUsers(fundingEntityId: FundingEntityId) =
      isAcceleratorAdmin() || parentStore.exists(fundingEntityId, userId)

  override fun canUpdateGlobalRoles(): Boolean = isSuperAdmin()

  override fun canUpdateSpecificGlobalRoles(globalRoles: Set<GlobalRole>): Boolean =
      globalRoles.all {
        when (it) {
          GlobalRole.AcceleratorAdmin -> isAcceleratorAdmin()
          GlobalRole.ReadOnly -> isAcceleratorAdmin()
          GlobalRole.SuperAdmin -> isSuperAdmin()
          GlobalRole.TFExpert -> isAcceleratorAdmin()
        }
      }

  override fun canUpdateInternalOnlyVariables(): Boolean = isTFExpertOrHigher()

  override fun canUpdateInternalVariableWorkflowDetails(projectId: ProjectId) = isTFExpertOrHigher()

  override fun canUpdateMonitoringPlot(monitoringPlotId: MonitoringPlotId): Boolean =
      isAdminOrHigher(parentStore.getOrganizationId(monitoringPlotId))

  override fun canUpdateNotification(notificationId: NotificationId) =
      canReadNotification(notificationId)

  override fun canUpdateNotifications(organizationId: OrganizationId?) =
      canListNotifications(organizationId)

  override fun canUpdateObservation(observationId: ObservationId) =
      isMember(parentStore.getOrganizationId(observationId))

  override fun canUpdateObservationQuantities(observationId: ObservationId) =
      isManagerOrHigher(parentStore.getOrganizationId(observationId))

  override fun canUpdateOrganization(organizationId: OrganizationId) =
      isAdminOrHigher(organizationId)

  override fun canUpdateParticipantProjectSpecies(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ) =
      isTFExpertOrHigher() ||
          isManagerOrHigher(parentStore.getOrganizationId(participantProjectSpeciesId))

  override fun canUpdatePlantingSite(plantingSiteId: PlantingSiteId) =
      isAdminOrHigher(parentStore.getOrganizationId(plantingSiteId))

  override fun canUpdatePlantingSiteProject(plantingSiteId: PlantingSiteId) =
      isMember(parentStore.getOrganizationId(plantingSiteId))

  override fun canUpdateProject(projectId: ProjectId): Boolean {
    val organizationId = parentStore.getOrganizationId(projectId) ?: return false
    return isAdminOrHigher(organizationId) || isGlobalWriter(organizationId)
  }

  override fun canUpdateProjectAcceleratorDetails(projectId: ProjectId): Boolean =
      isTFExpertOrHigher()

  override fun canUpdateProjectDocumentSettings(projectId: ProjectId) = isAcceleratorAdmin()

  override fun canUpdateProjectInternalUsers(projectId: ProjectId) = isTFExpertOrHigher()

  override fun canUpdateProjectReports(projectId: ProjectId): Boolean =
      isTFExpertOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(projectId))

  override fun canUpdateProjectScores(projectId: ProjectId): Boolean = isTFExpertOrHigher()

  override fun canUpdateProjectVotes(projectId: ProjectId): Boolean = isTFExpertOrHigher()

  override fun canUpdateReport(reportId: ReportId): Boolean =
      isTFExpertOrHigher() || isManagerOrHigher(parentStore.getOrganizationId(reportId))

  override fun canUpdateSeedFundReport(reportId: SeedFundReportId) =
      isAdminOrHigher(parentStore.getOrganizationId(reportId))

  override fun canUpdateSpecies(speciesId: SpeciesId) =
      isManagerOrHigher(parentStore.getOrganizationId(speciesId))

  override fun canUpdateStratum(stratumId: StratumId) =
      isAdminOrHigher(parentStore.getOrganizationId(stratumId))

  override fun canUpdateSubLocation(subLocationId: SubLocationId) =
      isAdminOrHigher(parentStore.getFacilityId(subLocationId))

  override fun canUpdateSubmissionStatus(deliverableId: DeliverableId, projectId: ProjectId) =
      isTFExpertOrHigher()

  override fun canUpdateSubstratumCompleted(substratumId: SubstratumId) =
      isMember(parentStore.getOrganizationId(substratumId))

  override fun canUpdateT0(monitoringPlotId: MonitoringPlotId) =
      isManagerOrHigher(parentStore.getOrganizationId(monitoringPlotId))

  override fun canUpdateT0(stratumId: StratumId) =
      isManagerOrHigher(parentStore.getOrganizationId(stratumId))

  override fun canUpdateTimeseries(deviceId: DeviceId) =
      isAdminOrHigher(parentStore.getFacilityId(deviceId))

  override fun canUpdateUpload(uploadId: UploadId) = canReadUpload(uploadId)

  override fun canUpdateUserInternalInterests(userId: UserId) = isAcceleratorAdmin()

  override fun canUploadPhoto(accessionId: AccessionId) =
      isMember(parentStore.getFacilityId(accessionId))

  private fun isSuperAdmin(): Boolean {
    recordPermissionCheck(GlobalRolePermissionCheck(GlobalRole.SuperAdmin))
    return GlobalRole.SuperAdmin in globalRoles
  }

  private fun isAcceleratorAdmin(): Boolean {
    recordPermissionCheck(GlobalRolePermissionCheck(GlobalRole.AcceleratorAdmin))
    return setOf(GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin).any { it in globalRoles }
  }

  private fun isTFExpertOrHigher(): Boolean {
    recordPermissionCheck(GlobalRolePermissionCheck(GlobalRole.TFExpert))
    return setOf(GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin).any {
      it in globalRoles
    }
  }

  private fun isReadOnlyOrHigher(): Boolean {
    recordPermissionCheck(GlobalRolePermissionCheck(GlobalRole.ReadOnly))
    return setOf(
            GlobalRole.ReadOnly,
            GlobalRole.TFExpert,
            GlobalRole.AcceleratorAdmin,
            GlobalRole.SuperAdmin,
        )
        .any { it in globalRoles }
  }

  private fun canReadAcceleratorProject(projectId: ProjectId?): Boolean {
    return isReadOnlyOrHigher() && parentStore.isProjectInAccelerator(projectId)
  }

  private fun isOwner(organizationId: OrganizationId?) =
      organizationId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Owner, organizationId))
        organizationRoles[organizationId] == Role.Owner
      } ?: false

  override fun isAdminOrHigher(organizationId: OrganizationId?): Boolean {
    return organizationId?.let {
      recordPermissionCheck(RolePermissionCheck(Role.Admin, organizationId))
      when (organizationRoles[organizationId]) {
        Role.Admin,
        Role.Owner,
        Role.TerraformationContact -> true
        else -> false
      }
    } ?: false
  }

  private fun isAdminOrHigher(facilityId: FacilityId?) =
      facilityId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Admin, facilityId))
        when (facilityRoles[facilityId]) {
          Role.Admin,
          Role.Owner,
          Role.TerraformationContact -> true
          else -> false
        }
      } ?: false

  private fun isManagerOrHigher(organizationId: OrganizationId?) =
      organizationId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Manager, organizationId))
        when (organizationRoles[organizationId]) {
          Role.Admin,
          Role.Manager,
          Role.Owner,
          Role.TerraformationContact -> true
          else -> false
        }
      } ?: false

  private fun isManagerOrHigher(facilityId: FacilityId?) =
      facilityId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Manager, facilityId))
        when (facilityRoles[facilityId]) {
          Role.Admin,
          Role.Manager,
          Role.Owner,
          Role.TerraformationContact -> true
          else -> false
        }
      } ?: false

  private fun isManagerOrHigher(plantingSiteId: PlantingSiteId?) =
      plantingSiteId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Manager, plantingSiteId))
        isManagerOrHigher(parentStore.getOrganizationId(plantingSiteId))
      } ?: false

  private fun isMember(facilityId: FacilityId?) =
      facilityId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Contributor, facilityId))
        facilityId in facilityRoles
      } ?: false

  private fun isMember(organizationId: OrganizationId?) =
      organizationId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Contributor, organizationId))
        organizationId in organizationRoles
      } ?: false

  /** Returns true if one of the user's global roles allows them to read an organization. */
  private fun isGlobalReader(organizationId: OrganizationId?) =
      GlobalRole.SuperAdmin in globalRoles ||
          (isReadOnlyOrHigher() &&
              (parentStore.hasInternalTag(organizationId, InternalTagIds.Accelerator) ||
                  parentStore.hasApplications(organizationId)))

  /** Returns true if one of the user's global roles allows them to write to an organization. */
  private fun isGlobalWriter(organizationId: OrganizationId) =
      GlobalRole.SuperAdmin in globalRoles ||
          (isTFExpertOrHigher() &&
              (parentStore.hasInternalTag(organizationId, InternalTagIds.Accelerator) ||
                  parentStore.hasApplications(organizationId)))

  /** History of permission checks performed in the current request or job. */
  val permissionChecks: MutableList<PermissionCheck> = mutableListOf()

  private var isRecordingChecks: Boolean = false

  private fun recordPermissionCheck(check: PermissionCheck) {
    if (isRecordingChecks) {
      var checkIsAlreadyImplied = false

      check.populateCallStack()

      permissionChecks
          .filter { check.isGuardedBy(it) }
          .forEach { previousCheck ->
            if (check.isStricterThan(previousCheck)) {
              log.warn(
                  "Permission check $check guarded by $previousCheck" +
                      "\nPrevious:" +
                      "\n${previousCheck.prettyPrintStack()}" +
                      "\nCurrent:" +
                      "\n${check.prettyPrintStack()}"
              )
            } else if (check.isImpliedBy(previousCheck)) {
              checkIsAlreadyImplied = true
            }
          }

      // If a check is already implied by another check that guards it, don't record it; if, later,
      // there is a stricter check, we don't want to erroneously flag it as guarded by this
      // less-strict one.
      if (!checkIsAlreadyImplied) {
        permissionChecks.add(check)
      }
    }
  }

  override fun <T> recordPermissionChecks(func: () -> T): T {
    val oldHardPermission = isRecordingChecks
    isRecordingChecks = true

    return try {
      func()
    } finally {
      isRecordingChecks = oldHardPermission
    }
  }

  // When adding new permissions, put them in alphabetical order.
}
