package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.auth.SuperAdminAuthority
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.log.perClassLogger
import java.time.ZoneId
import java.util.Locale
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

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
 * will be cached afterwards.
 */
data class IndividualUser(
    override val userId: UserId,
    override val authId: String?,
    val email: String,
    val emailNotificationsEnabled: Boolean,
    val firstName: String?,
    val lastName: String?,
    val countryCode: String?,
    override val locale: Locale?,
    override val timeZone: ZoneId?,
    override val userType: UserType,
    private val parentStore: ParentStore,
    private val permissionStore: PermissionStore,
) : TerrawareUser, UserDetails {
  companion object {
    private val log = perClassLogger()

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

  override val organizationRoles: Map<OrganizationId, Role> by lazy {
    permissionStore.fetchOrganizationRoles(userId)
  }

  override val facilityRoles: Map<FacilityId, Role> by lazy {
    permissionStore.fetchFacilityRoles(userId)
  }

  val fullName: String?
    get() = makeFullName(firstName, lastName)

  override fun <T> run(func: () -> T): T {
    return CurrentUserHolder.runAs(this, func, authorities)
  }

  /** Returns true if the user is an admin or owner of any organizations. */
  override fun hasAnyAdminRole() =
      organizationRoles.values.any { it == Role.Owner || it == Role.Admin }

  override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
    return if (isSuperAdmin()) {
      mutableSetOf(SuperAdminAuthority)
    } else {
      mutableSetOf()
    }
  }

  override fun getPassword(): String {
    log.warn("Something is trying to get the password of an OAuth2 user")
    return ""
  }

  override fun getName(): String = authId ?: throw IllegalStateException("User is unregistered")
  override fun getUsername(): String = authId ?: throw IllegalStateException("User is unregistered")
  override fun isAccountNonExpired() = true
  override fun isAccountNonLocked() = true
  override fun isCredentialsNonExpired() = true
  override fun isEnabled() = true

  override fun canAddOrganizationUser(organizationId: OrganizationId) =
      isAdminOrHigher(organizationId)

  override fun canAddTerraformationContact(organizationId: OrganizationId) = isSuperAdmin()

  // all users can count their unread notifications
  override fun canCountNotifications() = true

  override fun canCreateAccession(facilityId: FacilityId) = isMember(facilityId)

  override fun canCreateApiKey(organizationId: OrganizationId) = isAdminOrHigher(organizationId)

  override fun canCreateAutomation(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canCreateBatch(facilityId: FacilityId) = isMember(facilityId)

  override fun canCreateDelivery(plantingSiteId: PlantingSiteId) = isManagerOrHigher(plantingSiteId)

  override fun canCreateDevice(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canCreateDeviceManager() = isSuperAdmin()

  override fun canCreateFacility(organizationId: OrganizationId) = isAdminOrHigher(organizationId)

  override fun canCreateNotification(
      targetUserId: UserId,
      organizationId: OrganizationId
  ): Boolean {
    // for now, ensure user making the request and the target user for notification,
    // are both members of the organization in context
    return isMember(organizationId) &&
        organizationId in permissionStore.fetchOrganizationRoles(targetUserId)
  }

  override fun canCreateObservation(plantingSiteId: PlantingSiteId) =
      isSuperAdmin() || isManagerOrHigher(plantingSiteId)

  override fun canCreatePlantingSite(organizationId: OrganizationId) =
      isAdminOrHigher(organizationId)

  override fun canCreateProject(organizationId: OrganizationId) = isAdminOrHigher(organizationId)

  // Reports are normally created by the system, but can be created manually by super-admins.
  override fun canCreateReport(organizationId: OrganizationId) = isSuperAdmin()

  override fun canCreateSpecies(organizationId: OrganizationId) = isManagerOrHigher(organizationId)

  override fun canCreateStorageLocation(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canCreateTimeseries(deviceId: DeviceId) =
      isAdminOrHigher(parentStore.getFacilityId(deviceId))

  override fun canCreateWithdrawalPhoto(withdrawalId: WithdrawalId) =
      isMember(parentStore.getFacilityId(withdrawalId))

  override fun canDeleteAccession(accessionId: AccessionId) =
      isManagerOrHigher(parentStore.getFacilityId(accessionId))

  override fun canDeleteAutomation(automationId: AutomationId) =
      isAdminOrHigher(parentStore.getFacilityId(automationId))

  override fun canDeleteBatch(batchId: BatchId) = isMember(parentStore.getFacilityId(batchId))

  override fun canDeleteOrganization(organizationId: OrganizationId) = isOwner(organizationId)

  override fun canDeleteProject(projectId: ProjectId) =
      isAdminOrHigher(parentStore.getOrganizationId(projectId))

  override fun canDeleteReport(reportId: ReportId): Boolean =
      isAdminOrHigher(parentStore.getOrganizationId(reportId))

  override fun canDeleteSelf() = true

  override fun canDeleteSpecies(speciesId: SpeciesId) =
      isManagerOrHigher(parentStore.getOrganizationId(speciesId))

  override fun canDeleteStorageLocation(storageLocationId: StorageLocationId) =
      isAdminOrHigher(parentStore.getFacilityId(storageLocationId))

  override fun canDeleteUpload(uploadId: UploadId) = canReadUpload(uploadId)

  override fun canImportGlobalSpeciesData() = isSuperAdmin()

  override fun canListAutomations(facilityId: FacilityId) = isMember(facilityId)

  override fun canListFacilities(organizationId: OrganizationId) = isMember(organizationId)

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
      isManagerOrHigher(organizationId)

  override fun canListReports(organizationId: OrganizationId) = isAdminOrHigher(organizationId)

  override fun canManageInternalTags() = isSuperAdmin()

  override fun canManageNotifications() = false

  override fun canManageObservation(observationId: ObservationId) = isSuperAdmin()

  override fun canMovePlantingSiteToAnyOrg(plantingSiteId: PlantingSiteId) =
      canUpdatePlantingSite(plantingSiteId) && isSuperAdmin()

  override fun canReadAccession(accessionId: AccessionId) =
      isMember(parentStore.getFacilityId(accessionId))

  override fun canReadAutomation(automationId: AutomationId) =
      isMember(parentStore.getFacilityId(automationId))

  override fun canReadBatch(batchId: BatchId) = isMember(parentStore.getFacilityId(batchId))

  override fun canReadDelivery(deliveryId: DeliveryId) =
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

  override fun canReadFacility(facilityId: FacilityId) = isMember(facilityId)

  override fun canReadNotification(notificationId: NotificationId) =
      parentStore.getUserId(notificationId) == userId

  override fun canReadObservation(observationId: ObservationId) =
      isMember(parentStore.getOrganizationId(observationId))

  override fun canReadOrganization(organizationId: OrganizationId) = isMember(organizationId)

  override fun canReadOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean {
    return if (userId == this.userId) {
      canReadOrganization(organizationId)
    } else {
      canListOrganizationUsers(organizationId) && parentStore.exists(organizationId, userId)
    }
  }

  override fun canReadPlanting(plantingId: PlantingId): Boolean =
      isMember(parentStore.getOrganizationId(plantingId))

  override fun canReadPlantingSite(plantingSiteId: PlantingSiteId) =
      isMember(parentStore.getOrganizationId(plantingSiteId))

  override fun canReadPlantingSubzone(plantingSubzoneId: PlantingSubzoneId) =
      isMember(parentStore.getOrganizationId(plantingSubzoneId))

  override fun canReadPlantingZone(plantingZoneId: PlantingZoneId) =
      isMember(parentStore.getOrganizationId(plantingZoneId))

  override fun canReadProject(projectId: ProjectId) =
      isMember(parentStore.getOrganizationId(projectId))

  override fun canReadReport(reportId: ReportId) =
      isAdminOrHigher(parentStore.getOrganizationId(reportId))

  // If this logic changes, make sure to also change code that bakes this rule into SQL queries
  // for efficiency. Example: SpeciesStore.fetchUncheckedSpeciesIds
  override fun canReadSpecies(speciesId: SpeciesId) =
      isMember(parentStore.getOrganizationId(speciesId))

  override fun canReadStorageLocation(storageLocationId: StorageLocationId) =
      isMember(parentStore.getFacilityId(storageLocationId))

  override fun canReadTimeseries(deviceId: DeviceId) = isMember(parentStore.getFacilityId(deviceId))

  override fun canReadUpload(uploadId: UploadId) = userId == parentStore.getUserId(uploadId)

  override fun canReadViabilityTest(viabilityTestId: ViabilityTestId) =
      isMember(parentStore.getFacilityId(viabilityTestId))

  override fun canReadWithdrawal(withdrawalId: WithdrawalId) =
      isMember(parentStore.getFacilityId(withdrawalId))

  override fun canRegenerateAllDeviceManagerTokens() = isSuperAdmin()

  override fun canRemoveOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean {
    return isMember(organizationId) && (userId == this.userId || isAdminOrHigher(organizationId))
  }

  override fun canRemoveTerraformationContact(organizationId: OrganizationId) = isSuperAdmin()

  override fun canSendAlert(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canSetOrganizationUserRole(organizationId: OrganizationId, role: Role) =
      isAdminOrHigher(organizationId)

  override fun canSetTerraformationContact(organizationId: OrganizationId) = isSuperAdmin()

  override fun canSetTestClock() = isSuperAdmin()

  override fun canSetWithdrawalUser(accessionId: AccessionId) =
      isManagerOrHigher(parentStore.getOrganizationId(accessionId))

  override fun canTriggerAutomation(automationId: AutomationId) =
      isAdminOrHigher(parentStore.getFacilityId(automationId))

  override fun canUpdateAccession(accessionId: AccessionId) =
      isManagerOrHigher(parentStore.getFacilityId(accessionId))

  override fun canUpdateAppVersions() = isSuperAdmin()

  override fun canUpdateAutomation(automationId: AutomationId) =
      isAdminOrHigher(parentStore.getFacilityId(automationId))

  // All users in the organization have read/write access to batches.
  override fun canUpdateBatch(batchId: BatchId) = isMember(parentStore.getFacilityId(batchId))

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

  override fun canUpdateFacility(facilityId: FacilityId) = isAdminOrHigher(facilityId)

  override fun canUpdateNotification(notificationId: NotificationId) =
      canReadNotification(notificationId)

  override fun canUpdateNotifications(organizationId: OrganizationId?) =
      canListNotifications(organizationId)

  override fun canUpdateObservation(observationId: ObservationId) =
      isMember(parentStore.getOrganizationId(observationId))

  override fun canUpdateOrganization(organizationId: OrganizationId) =
      isAdminOrHigher(organizationId)

  override fun canUpdatePlantingSite(plantingSiteId: PlantingSiteId) =
      isAdminOrHigher(parentStore.getOrganizationId(plantingSiteId))

  override fun canUpdatePlantingSubzone(plantingSubzoneId: PlantingSubzoneId) =
      isAdminOrHigher(parentStore.getOrganizationId(plantingSubzoneId))

  override fun canUpdatePlantingZone(plantingZoneId: PlantingZoneId) =
      isAdminOrHigher(parentStore.getOrganizationId(plantingZoneId))

  override fun canUpdateProject(projectId: ProjectId) =
      isAdminOrHigher(parentStore.getOrganizationId(projectId))

  override fun canUpdateReport(reportId: ReportId) =
      isAdminOrHigher(parentStore.getOrganizationId(reportId))

  override fun canUpdateSpecies(speciesId: SpeciesId) =
      isManagerOrHigher(parentStore.getOrganizationId(speciesId))

  override fun canUpdateStorageLocation(storageLocationId: StorageLocationId) =
      isAdminOrHigher(parentStore.getFacilityId(storageLocationId))

  override fun canUpdateTerraformationContact(organizationId: OrganizationId) = isSuperAdmin()

  override fun canUpdateTimeseries(deviceId: DeviceId) =
      isAdminOrHigher(parentStore.getFacilityId(deviceId))

  override fun canUpdateUpload(uploadId: UploadId) = canReadUpload(uploadId)

  override fun canUploadPhoto(accessionId: AccessionId) = canReadAccession(accessionId)

  private fun isSuperAdmin() = userType == UserType.SuperAdmin

  private fun isOwner(organizationId: OrganizationId?) =
      organizationId != null && organizationRoles[organizationId] == Role.Owner

  private fun isAdminOrHigher(organizationId: OrganizationId?) =
      organizationId != null &&
          when (organizationRoles[organizationId]) {
            Role.Admin,
            Role.Owner,
            Role.TerraformationContact -> true
            else -> false
          }

  private fun isAdminOrHigher(facilityId: FacilityId?) =
      facilityId != null &&
          when (facilityRoles[facilityId]) {
            Role.Admin,
            Role.Owner,
            Role.TerraformationContact -> true
            else -> false
          }

  private fun isManagerOrHigher(organizationId: OrganizationId?) =
      organizationId != null &&
          when (organizationRoles[organizationId]) {
            Role.Admin,
            Role.Manager,
            Role.Owner,
            Role.TerraformationContact -> true
            else -> false
          }

  private fun isManagerOrHigher(facilityId: FacilityId?) =
      facilityId != null &&
          when (facilityRoles[facilityId]) {
            Role.Admin,
            Role.Manager,
            Role.Owner,
            Role.TerraformationContact -> true
            else -> false
          }

  private fun isManagerOrHigher(plantingSiteId: PlantingSiteId?) =
      plantingSiteId != null && isManagerOrHigher(parentStore.getOrganizationId(plantingSiteId))

  private fun isMember(facilityId: FacilityId?) = facilityId != null && facilityId in facilityRoles

  private fun isMember(organizationId: OrganizationId?) =
      organizationId != null && organizationId in organizationRoles

  // When adding new permissions, put them in alphabetical order.
}
