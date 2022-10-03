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
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.log.perClassLogger
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
  override fun hasAnyAdminRole(): Boolean =
      organizationRoles.values.any { it == Role.OWNER || it == Role.ADMIN }

  override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
    return if (userType == UserType.SuperAdmin) {
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
  override fun isAccountNonExpired(): Boolean = true
  override fun isAccountNonLocked(): Boolean = true
  override fun isCredentialsNonExpired(): Boolean = true
  override fun isEnabled(): Boolean = true

  override fun canAddOrganizationUser(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  // all users can count their unread notifications
  override fun canCountNotifications(): Boolean = true

  override fun canCreateAccession(facilityId: FacilityId): Boolean {
    // All users in a project can create accessions.
    return facilityId in facilityRoles
  }

  override fun canCreateApiKey(organizationId: OrganizationId): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER,
      Role.ADMIN -> true
      else -> false
    }
  }

  override fun canCreateAutomation(facilityId: FacilityId): Boolean {
    return canUpdateFacility(facilityId)
  }

  override fun canCreateBatch(facilityId: FacilityId): Boolean {
    // All users in an organization can create seedling batches.
    return facilityId in facilityRoles
  }

  override fun canCreateDevice(facilityId: FacilityId): Boolean {
    return canUpdateFacility(facilityId)
  }

  override fun canCreateDeviceManager(): Boolean {
    return userType == UserType.SuperAdmin
  }

  override fun canCreateFacility(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  override fun canCreateNotification(
      targetUserId: UserId,
      organizationId: OrganizationId
  ): Boolean {
    // for now, ensure user making the request and the target user for notification,
    // are both members of the organization in context
    return (organizationId in permissionStore.fetchOrganizationRoles(targetUserId)) &&
        (organizationId in organizationRoles)
  }

  override fun canCreateSpecies(organizationId: OrganizationId): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER,
      Role.ADMIN,
      Role.MANAGER -> true
      else -> false
    }
  }

  override fun canCreateStorageLocation(facilityId: FacilityId): Boolean {
    return when (facilityRoles[facilityId]) {
      Role.OWNER,
      Role.ADMIN -> true
      else -> false
    }
  }

  override fun canCreateTimeseries(deviceId: DeviceId): Boolean {
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return canUpdateFacility(facilityId)
  }

  override fun canDeleteAccession(accessionId: AccessionId): Boolean {
    return canUpdateAccession(accessionId)
  }

  override fun canDeleteAutomation(automationId: AutomationId): Boolean {
    return canUpdateAutomation(automationId)
  }

  override fun canDeleteOrganization(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.OWNER
  }

  override fun canDeleteSelf(): Boolean = true

  override fun canDeleteSpecies(speciesId: SpeciesId): Boolean = canUpdateSpecies(speciesId)

  override fun canDeleteStorageLocation(storageLocationId: StorageLocationId): Boolean =
      canUpdateStorageLocation(storageLocationId)

  override fun canDeleteUpload(uploadId: UploadId): Boolean = canReadUpload(uploadId)

  override fun canImportGlobalSpeciesData(): Boolean {
    return userType == UserType.SuperAdmin
  }

  override fun canListAutomations(facilityId: FacilityId): Boolean {
    return canReadFacility(facilityId)
  }

  override fun canListFacilities(organizationId: OrganizationId): Boolean {
    // Any user who has access to a site can list its facilities.
    return organizationId in organizationRoles
  }

  override fun canListNotifications(organizationId: OrganizationId?): Boolean {
    return if (organizationId == null) {
      // user can list global notifications relevant to user
      true
    } else {
      // user should belong to the organization otherwise
      organizationId in organizationRoles
    }
  }

  override fun canListOrganizationUsers(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.MANAGER || role == Role.ADMIN || role == Role.OWNER
  }

  override fun canReadAccession(accessionId: AccessionId): Boolean {
    val facilityId = parentStore.getFacilityId(accessionId) ?: return false

    // All users in a project can read all accessions in the project's facilities.
    return facilityId in facilityRoles
  }

  override fun canReadAutomation(automationId: AutomationId): Boolean {
    val facilityId = parentStore.getFacilityId(automationId) ?: return false
    return canReadFacility(facilityId)
  }

  override fun canReadBatch(batchId: BatchId): Boolean {
    val facilityId = parentStore.getFacilityId(batchId) ?: return false
    return facilityId in facilityRoles
  }

  override fun canReadDevice(deviceId: DeviceId): Boolean {
    // Any user with access to the facility can read a device.
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return facilityId in facilityRoles
  }

  override fun canReadDeviceManager(deviceManagerId: DeviceManagerId): Boolean {
    val facilityId = parentStore.getFacilityId(deviceManagerId)
    return if (facilityId != null) {
      canReadFacility(facilityId)
    } else {
      parentStore.exists(deviceManagerId)
    }
  }

  override fun canReadFacility(facilityId: FacilityId): Boolean {
    return facilityId in facilityRoles
  }

  override fun canReadNotification(notificationId: NotificationId): Boolean {
    return parentStore.getUserId(notificationId) == userId
  }

  override fun canReadOrganization(organizationId: OrganizationId): Boolean {
    return organizationId in organizationRoles
  }

  override fun canReadOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean {
    return if (userId == this.userId) {
      canReadOrganization(organizationId)
    } else {
      canListOrganizationUsers(organizationId) && parentStore.exists(organizationId, userId)
    }
  }

  override fun canReadSpecies(speciesId: SpeciesId): Boolean {
    // If this logic changes, make sure to also change code that bakes this rule into SQL queries
    // for efficiency. Example: SpeciesStore.fetchUncheckedSpeciesIds
    val organizationId = parentStore.getOrganizationId(speciesId) ?: return false
    return canReadOrganization(organizationId)
  }

  override fun canReadStorageLocation(storageLocationId: StorageLocationId): Boolean {
    val facilityId = parentStore.getFacilityId(storageLocationId) ?: return false
    return facilityId in facilityRoles
  }

  override fun canReadTimeseries(deviceId: DeviceId): Boolean = canReadDevice(deviceId)

  override fun canReadUpload(uploadId: UploadId): Boolean {
    return userId == parentStore.getUserId(uploadId)
  }

  override fun canReadViabilityTest(viabilityTestId: ViabilityTestId): Boolean {
    val accessionId = parentStore.getAccessionId(viabilityTestId) ?: return false
    return canReadAccession(accessionId)
  }

  override fun canRegenerateAllDeviceManagerTokens(): Boolean {
    return userType == UserType.SuperAdmin
  }

  override fun canRemoveOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean {
    return organizationId in organizationRoles &&
        (userId == this.userId || canAddOrganizationUser(organizationId))
  }

  override fun canSendAlert(facilityId: FacilityId): Boolean {
    return canUpdateFacility(facilityId)
  }

  override fun canSetOrganizationUserRole(organizationId: OrganizationId, role: Role): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER,
      Role.ADMIN -> true
      else -> false
    }
  }

  override fun canSetTestClock(): Boolean {
    return userType == UserType.SuperAdmin
  }

  override fun canSetWithdrawalUser(accessionId: AccessionId): Boolean {
    val organizationId = parentStore.getOrganizationId(accessionId) ?: return false
    return when (organizationRoles[organizationId]) {
      Role.OWNER,
      Role.ADMIN,
      Role.MANAGER -> true
      else -> false
    }
  }

  override fun canTriggerAutomation(automationId: AutomationId): Boolean {
    return canUpdateAutomation(automationId)
  }

  override fun canUpdateAccession(accessionId: AccessionId): Boolean {
    // All users in a project can write all accessions in the project's facilities, so this
    // is the same as the read permission check.
    return canReadAccession(accessionId)
  }

  override fun canUpdateAppVersions(): Boolean {
    return userType == UserType.SuperAdmin
  }

  override fun canUpdateAutomation(automationId: AutomationId): Boolean {
    val facilityId = parentStore.getFacilityId(automationId) ?: return false
    return canUpdateFacility(facilityId)
  }

  override fun canUpdateDevice(deviceId: DeviceId): Boolean {
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return canUpdateFacility(facilityId)
  }

  override fun canUpdateDeviceManager(deviceManagerId: DeviceManagerId): Boolean {
    val facilityId = parentStore.getFacilityId(deviceManagerId)
    return if (facilityId != null) {
      when (facilityRoles[facilityId]) {
        Role.OWNER,
        Role.ADMIN -> true
        else -> false
      }
    } else {
      hasAnyAdminRole()
    }
  }

  override fun canUpdateDeviceTemplates(): Boolean {
    return userType == UserType.SuperAdmin
  }

  override fun canUpdateFacility(facilityId: FacilityId): Boolean {
    return when (facilityRoles[facilityId]) {
      Role.ADMIN,
      Role.OWNER -> true
      else -> false
    }
  }

  override fun canUpdateNotification(notificationId: NotificationId): Boolean =
      canReadNotification(notificationId)

  override fun canUpdateNotifications(organizationId: OrganizationId?): Boolean =
      canListNotifications(organizationId)

  override fun canUpdateOrganization(organizationId: OrganizationId): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER,
      Role.ADMIN -> true
      else -> false
    }
  }

  override fun canUpdateSpecies(speciesId: SpeciesId): Boolean {
    val organizationId = parentStore.getOrganizationId(speciesId) ?: return false
    return canCreateSpecies(organizationId)
  }

  override fun canUpdateStorageLocation(storageLocationId: StorageLocationId): Boolean {
    val facilityId = parentStore.getFacilityId(storageLocationId) ?: return false
    return canCreateStorageLocation(facilityId)
  }

  override fun canUpdateTimeseries(deviceId: DeviceId): Boolean = canCreateTimeseries(deviceId)

  override fun canUpdateUpload(uploadId: UploadId): Boolean = canReadUpload(uploadId)

  // When adding new permissions, put them in alphabetical order.
}
