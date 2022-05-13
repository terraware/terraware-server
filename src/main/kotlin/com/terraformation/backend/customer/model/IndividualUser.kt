package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.auth.SuperAdminAuthority
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.NotificationStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.log.perClassLogger
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Details about the user who is making the current request and the permissions they have. This
 * always represents a regular (presumably human) user or an API client.
 *
 * To get the current user's details, call [currentUser]. See that function's docs for some caveats,
 * but this is usually what you'll want to do.
 *
 * For permission checking where lack of permission should be treated as an error, you will probably
 * want to use [requirePermissions] instead of interacting directly with this class. See that
 * function's docs for more details.
 *
 * This class attempts to abstract away the implementation details of the permission checking
 * business logic. It has a bunch of methods like [canCreateSite] to check for specific permissions.
 * In general, we want those methods to be as fine-grained as possible and to take fine-grained
 * context as parameters, even if the actual permissions are coarse-grained. The calling code
 * shouldn't have to make assumptions about how permissions are computed. For example, the ability
 * to create a site may be determined by the user's organization-level role, but [canCreateSite]
 * takes a project ID argument. This way, if we add fine-grained permissions later on, existing
 * calls to [canCreateSite] won't need to be modified.
 *
 * The permission-checking methods should never return true if the target object doesn't exist.
 * Callers can thus make use of the fact that a successful permission check means the target object
 * existed at the time the permission data was loaded.
 *
 * In addition to holding some basic details about the user, this object also serves as a
 * short-lived cache for information such as the user's roles in various contexts. For example, if
 * you access [siteRoles] you will get a map of the user's role at each site they have access to.
 * The first time you access that property, it will be fetched from the database, but it will be
 * cached afterwards.
 */
data class IndividualUser(
    override val userId: UserId,
    val authId: String?,
    val email: String,
    val emailNotificationsEnabled: Boolean,
    val firstName: String?,
    val lastName: String?,
    override val userType: UserType,
    private val parentStore: ParentStore,
    private val permissionStore: PermissionStore,
    private val notificationStore: NotificationStore,
) : TerrawareUser, UserDetails {
  override val organizationRoles: Map<OrganizationId, Role> by lazy {
    permissionStore.fetchOrganizationRoles(userId)
  }

  override val projectRoles: Map<ProjectId, Role> by lazy {
    permissionStore.fetchProjectRoles(userId)
  }

  /**
   * The user's role at each site they have access to. Currently, roles are assigned
   * per-organization, so this is really the user's role in the organization that owns the project
   * that owns each site.
   */
  private val siteRoles: Map<SiteId, Role> by lazy { permissionStore.fetchSiteRoles(userId) }

  override val facilityRoles: Map<FacilityId, Role> by lazy {
    permissionStore.fetchFacilityRoles(userId)
  }

  /**
   * The user's full name, if available. Currently this is just the first and last name if both are
   * set. Eventually this will need logic to deal with users in locales where names aren't rendered
   * the same way they are in English.
   */
  val fullName: String?
    get() = if (firstName != null && lastName != null) "$firstName $lastName" else null

  override fun <T> run(func: () -> T): T {
    return CurrentUserHolder.runAs(this, func, authorities)
  }

  override fun canCreateAccession(facilityId: FacilityId): Boolean {
    // All users in a project can create accessions.
    return facilityId in facilityRoles
  }

  override fun canReadAccession(accessionId: AccessionId): Boolean {
    val facilityId = parentStore.getFacilityId(accessionId) ?: return false

    // All users in a project can read all accessions in the project's facilities.
    return facilityId in facilityRoles
  }

  override fun canUpdateAccession(accessionId: AccessionId): Boolean {
    // All users in a project can write all accessions in the project's facilities, so this
    // is the same as the read permission check.
    return canReadAccession(accessionId)
  }

  private fun canAccessAutomations(facilityId: FacilityId): Boolean {
    // All users that have access to the facility have full permissions on automations.
    // TODO: Revisit automation permissions once we can set roles on API clients
    return facilityId in facilityRoles
  }

  override fun canCreateAutomation(facilityId: FacilityId): Boolean {
    return canAccessAutomations(facilityId)
  }

  override fun canListAutomations(facilityId: FacilityId): Boolean {
    return canAccessAutomations(facilityId)
  }

  private fun canAccessAutomation(automationId: AutomationId): Boolean {
    val facilityId = parentStore.getFacilityId(automationId) ?: return false
    return canAccessAutomations(facilityId)
  }

  override fun canReadAutomation(automationId: AutomationId): Boolean {
    return canAccessAutomation(automationId)
  }

  override fun canUpdateAutomation(automationId: AutomationId): Boolean {
    return canAccessAutomation(automationId)
  }

  override fun canDeleteAutomation(automationId: AutomationId): Boolean {
    return canAccessAutomation(automationId)
  }

  override fun canCreateFacility(siteId: SiteId): Boolean {
    val role = siteRoles[siteId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  override fun canListFacilities(siteId: SiteId): Boolean {
    // Any user who has access to a site can list its facilities.
    return siteRoles[siteId] != null
  }

  override fun canReadFacility(facilityId: FacilityId): Boolean {
    return facilityId in facilityRoles
  }

  override fun canUpdateFacility(facilityId: FacilityId): Boolean {
    return facilityId in facilityRoles
  }

  override fun canSendAlert(facilityId: FacilityId): Boolean {
    return facilityId in facilityRoles
  }

  override fun canCreateDevice(facilityId: FacilityId): Boolean {
    // Any user with access to the facility can create a new device.
    // TODO: Revisit this once we can set roles on API clients
    return facilityId in facilityRoles
  }

  override fun canReadDevice(deviceId: DeviceId): Boolean {
    // Any user with access to the facility can read a device.
    // TODO: Revisit this once we can set roles on API clients (settings may contain sensitive data)
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return facilityId in facilityRoles
  }

  override fun canUpdateDevice(deviceId: DeviceId): Boolean {
    // TODO: Revisit this once we can set roles on API clients
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return facilityId in facilityRoles
  }

  override fun canCreateSite(projectId: ProjectId): Boolean {
    val role = projectRoles[projectId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  override fun canListSites(projectId: ProjectId): Boolean {
    // Any user who has access to a project can list its sites.
    return projectRoles[projectId] != null
  }

  override fun canReadSite(siteId: SiteId): Boolean {
    return siteId in siteRoles
  }

  override fun canUpdateSite(siteId: SiteId): Boolean {
    return when (siteRoles[siteId]) {
      Role.ADMIN, Role.OWNER -> true
      else -> false
    }
  }

  override fun canDeleteSite(siteId: SiteId): Boolean {
    return when (siteRoles[siteId]) {
      Role.ADMIN, Role.OWNER -> true
      else -> false
    }
  }

  override fun canCreateProject(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  override fun canListProjects(organizationId: OrganizationId): Boolean {
    return organizationId in organizationRoles
  }

  override fun canReadProject(projectId: ProjectId): Boolean {
    return projectId in projectRoles
  }

  override fun canUpdateProject(projectId: ProjectId): Boolean {
    val role = projectRoles[projectId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  override fun canAddProjectUser(projectId: ProjectId): Boolean {
    val role = projectRoles[projectId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  override fun canRemoveProjectUser(projectId: ProjectId, userId: UserId): Boolean {
    return projectId in projectRoles && (userId == this.userId || canAddProjectUser(projectId))
  }

  override fun canListOrganizationUsers(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  override fun canAddOrganizationUser(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  override fun canRemoveOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean {
    return organizationId in organizationRoles &&
        (userId == this.userId || canAddOrganizationUser(organizationId))
  }

  override fun canSetOrganizationUserRole(
      organizationId: OrganizationId,
      @Suppress("UNUSED_PARAMETER") role: Role
  ): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER, Role.ADMIN -> true
      else -> false
    }
  }

  override fun canReadOrganization(organizationId: OrganizationId): Boolean {
    return organizationId in organizationRoles
  }

  override fun canUpdateOrganization(organizationId: OrganizationId): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER, Role.ADMIN -> true
      else -> false
    }
  }

  override fun canDeleteOrganization(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.OWNER
  }

  override fun canCreateApiKey(organizationId: OrganizationId): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER, Role.ADMIN -> true
      else -> false
    }
  }

  override fun canDeleteApiKey(organizationId: OrganizationId): Boolean =
      canCreateApiKey(organizationId)

  override fun canListApiKeys(organizationId: OrganizationId): Boolean =
      canCreateApiKey(organizationId)

  override fun canCreateSpecies(organizationId: OrganizationId): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER, Role.ADMIN -> true
      else -> false
    }
  }

  override fun canReadSpecies(speciesId: SpeciesId): Boolean {
    val organizationId = parentStore.getOrganizationId(speciesId) ?: return false
    return canReadOrganization(organizationId)
  }

  override fun canUpdateSpecies(speciesId: SpeciesId): Boolean {
    val organizationId = parentStore.getOrganizationId(speciesId) ?: return false
    return canCreateSpecies(organizationId)
  }

  override fun canDeleteSpecies(speciesId: SpeciesId): Boolean = canUpdateSpecies(speciesId)

  override fun canCreateTimeseries(deviceId: DeviceId): Boolean {
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return facilityId in facilityRoles
  }

  override fun canReadTimeseries(deviceId: DeviceId): Boolean = canCreateTimeseries(deviceId)

  override fun canUpdateTimeseries(deviceId: DeviceId): Boolean = canCreateTimeseries(deviceId)

  override fun canCreateStorageLocation(facilityId: FacilityId): Boolean {
    return when (facilityRoles[facilityId]) {
      Role.OWNER, Role.ADMIN -> true
      else -> false
    }
  }

  override fun canReadStorageLocation(storageLocationId: StorageLocationId): Boolean {
    val facilityId = parentStore.getFacilityId(storageLocationId) ?: return false
    return facilityId in facilityRoles
  }

  override fun canUpdateStorageLocation(storageLocationId: StorageLocationId): Boolean {
    val facilityId = parentStore.getFacilityId(storageLocationId) ?: return false
    return canCreateStorageLocation(facilityId)
  }

  override fun canDeleteStorageLocation(storageLocationId: StorageLocationId): Boolean =
      canUpdateStorageLocation(storageLocationId)

  override fun canImportGlobalSpeciesData(): Boolean {
    return userType == UserType.SuperAdmin
  }

  override fun canReadNotification(notificationId: NotificationId): Boolean {
    return parentStore.getUserId(notificationId) == userId
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

  // all users can count their unread notifications
  override fun canCountNotifications(): Boolean = true

  override fun canUpdateNotification(notificationId: NotificationId): Boolean =
      canReadNotification(notificationId)

  override fun canUpdateNotifications(organizationId: OrganizationId?): Boolean =
      canListNotifications(organizationId)

  override fun canCreateNotification(
      targetUserId: UserId,
      organizationId: OrganizationId
  ): Boolean {
    // for now, ensure user making the request and the target user for notification,
    // are both members of the organization in context
    return (organizationId in permissionStore.fetchOrganizationRoles(targetUserId)) &&
        (organizationId in organizationRoles)
  }

  override fun canReadUpload(uploadId: UploadId): Boolean {
    return userId == parentStore.getUserId(uploadId)
  }

  override fun canUpdateUpload(uploadId: UploadId): Boolean = canReadUpload(uploadId)

  override fun canDeleteUpload(uploadId: UploadId): Boolean = canReadUpload(uploadId)

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

  companion object {
    private val log = perClassLogger()

    @Suppress("unused")
    private fun dummyFunctionToImportSymbolsReferredToInComments() {
      currentUser()
    }
  }
}
