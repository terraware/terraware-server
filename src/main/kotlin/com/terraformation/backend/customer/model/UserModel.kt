package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.auth.SuperAdminAuthority
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.log.perClassLogger
import java.security.Principal
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken

/**
 * Details about the user who is making the current request and the permissions they have.
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
data class UserModel(
    val userId: UserId,
    val authId: String?,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val userType: UserType,
    private val parentStore: ParentStore,
    private val permissionStore: PermissionStore,
) : UserDetails, Principal {
  /** The user's role in each organization they belong to. */
  val organizationRoles: Map<OrganizationId, Role> by lazy {
    permissionStore.fetchOrganizationRoles(userId)
  }

  /**
   * The user's role in each project they have access to. Currently, roles are assigned
   * per-organization, so this is really the user's role in the organization that owns each project.
   */
  val projectRoles: Map<ProjectId, Role> by lazy { permissionStore.fetchProjectRoles(userId) }

  /**
   * The user's role at each site they have access to. Currently, roles are assigned
   * per-organization, so this is really the user's role in the organization that owns the project
   * that owns each site.
   */
  private val siteRoles: Map<SiteId, Role> by lazy { permissionStore.fetchSiteRoles(userId) }

  /**
   * The user's role in each facility they have access to. Currently, roles are assigned
   * per-organization, so this is really the user's role in the organization that owns the project
   * and site of each facility.
   */
  val facilityRoles: Map<FacilityId, Role> by lazy { permissionStore.fetchFacilityRoles(userId) }

  /**
   * The user's full name, if available. Currently this is just the first and last name if both are
   * set. Eventually this will need logic to deal with users in locales where names aren't rendered
   * the same way they are in English.
   */
  val fullName: String?
    get() = if (firstName != null && lastName != null) "$firstName $lastName" else null

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
  fun run(func: () -> Unit) {
    val context = SecurityContextHolder.getContext()
    val oldAuthentication = context.authentication
    val oldUserModel = CurrentUserHolder.getCurrentUser()
    val newAuthentication = PreAuthenticatedAuthenticationToken(this, "N/A", authorities)
    try {
      context.authentication = newAuthentication
      CurrentUserHolder.setCurrentUser(this)
      func()
    } finally {
      try {
        context.authentication = oldAuthentication
      } finally {
        CurrentUserHolder.setCurrentUser(oldUserModel)
      }
    }
  }

  fun canCreateAccession(facilityId: FacilityId): Boolean {
    // All users in a project can create accessions.
    return facilityId in facilityRoles
  }

  fun canReadAccession(accessionId: AccessionId): Boolean {
    val facilityId = parentStore.getFacilityId(accessionId) ?: return false

    // All users in a project can read all accessions in the project's facilities.
    return facilityId in facilityRoles
  }

  fun canUpdateAccession(accessionId: AccessionId): Boolean {
    // All users in a project can write all accessions in the project's facilities, so this
    // is the same as the read permission check.
    return canReadAccession(accessionId)
  }

  private fun canAccessAutomations(facilityId: FacilityId): Boolean {
    // All users that have access to the facility have full permissions on automations.
    // TODO: Revisit automation permissions once we can set roles on API clients
    return facilityId in facilityRoles
  }

  fun canCreateAutomation(facilityId: FacilityId): Boolean {
    return canAccessAutomations(facilityId)
  }

  fun canListAutomations(facilityId: FacilityId): Boolean {
    return canAccessAutomations(facilityId)
  }

  private fun canAccessAutomation(automationId: AutomationId): Boolean {
    val facilityId = parentStore.getFacilityId(automationId) ?: return false
    return canAccessAutomations(facilityId)
  }

  fun canReadAutomation(automationId: AutomationId): Boolean {
    return canAccessAutomation(automationId)
  }

  fun canUpdateAutomation(automationId: AutomationId): Boolean {
    return canAccessAutomation(automationId)
  }

  fun canDeleteAutomation(automationId: AutomationId): Boolean {
    return canAccessAutomation(automationId)
  }

  fun canCreateFacility(siteId: SiteId): Boolean {
    val role = siteRoles[siteId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  fun canListFacilities(siteId: SiteId): Boolean {
    // Any user who has access to a site can list its facilities.
    return siteRoles[siteId] != null
  }

  fun canReadFacility(facilityId: FacilityId): Boolean {
    return facilityId in facilityRoles
  }

  fun canUpdateFacility(facilityId: FacilityId): Boolean {
    return facilityId in facilityRoles
  }

  fun canSendAlert(facilityId: FacilityId): Boolean {
    return facilityId in facilityRoles
  }

  fun canCreateDevice(facilityId: FacilityId): Boolean {
    // Any user with access to the facility can create a new device.
    // TODO: Revisit this once we can set roles on API clients
    return facilityId in facilityRoles
  }

  fun canReadDevice(deviceId: DeviceId): Boolean {
    // Any user with access to the facility can read a device.
    // TODO: Revisit this once we can set roles on API clients (settings may contain sensitive data)
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return facilityId in facilityRoles
  }

  fun canUpdateDevice(deviceId: DeviceId): Boolean {
    // TODO: Revisit this once we can set roles on API clients
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return facilityId in facilityRoles
  }

  private fun canAccessLayer(layerId: LayerId): Boolean {
    // Any user who has access to a site can access all its layers
    val siteId = parentStore.getSiteId(layerId) ?: return false
    return siteId in siteRoles
  }

  fun canCreateLayer(siteId: SiteId): Boolean {
    return siteId in siteRoles
  }

  fun canReadLayer(layerId: LayerId): Boolean {
    return canAccessLayer(layerId)
  }

  fun canUpdateLayer(layerId: LayerId): Boolean {
    return canAccessLayer(layerId)
  }

  fun canDeleteLayer(layerId: LayerId): Boolean {
    return canAccessLayer(layerId)
  }

  fun canCreateFeature(layerId: LayerId): Boolean {
    return canUpdateLayer(layerId)
  }

  fun canReadFeature(featureId: FeatureId): Boolean {
    val layerId = parentStore.getLayerId(featureId) ?: return false
    return canReadLayer(layerId)
  }

  fun canUpdateFeature(featureId: FeatureId): Boolean {
    val layerId = parentStore.getLayerId(featureId) ?: return false
    return canUpdateLayer(layerId)
  }

  fun canDeleteFeature(featureId: FeatureId): Boolean {
    return canUpdateFeature(featureId)
  }

  fun canReadFeaturePhoto(photoId: PhotoId): Boolean {
    val featureId = parentStore.getFeatureId(photoId) ?: return false
    return canReadFeature(featureId)
  }

  fun canDeleteFeaturePhoto(photoId: PhotoId): Boolean {
    val featureId = parentStore.getFeatureId(photoId) ?: return false
    return canUpdateFeature(featureId)
  }

  fun canCreateSite(projectId: ProjectId): Boolean {
    val role = projectRoles[projectId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  fun canListSites(projectId: ProjectId): Boolean {
    // Any user who has access to a project can list its sites.
    return projectRoles[projectId] != null
  }

  fun canReadSite(siteId: SiteId): Boolean {
    return siteId in siteRoles
  }

  fun canUpdateSite(siteId: SiteId): Boolean {
    val role = siteRoles[siteId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  fun canCreateProject(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  fun canListProjects(organizationId: OrganizationId): Boolean {
    return organizationId in organizationRoles
  }

  fun canReadProject(projectId: ProjectId): Boolean {
    return projectId in projectRoles
  }

  fun canUpdateProject(projectId: ProjectId): Boolean {
    val role = projectRoles[projectId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  fun canAddProjectUser(projectId: ProjectId): Boolean {
    val role = projectRoles[projectId]
    return role == Role.MANAGER || role == Role.ADMIN || role == Role.OWNER
  }

  fun canRemoveProjectUser(projectId: ProjectId): Boolean {
    return canAddProjectUser(projectId)
  }

  fun canListOrganizationUsers(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.MANAGER || role == Role.ADMIN || role == Role.OWNER
  }

  fun canAddOrganizationUser(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  fun canRemoveOrganizationUser(organizationId: OrganizationId): Boolean {
    return canAddOrganizationUser(organizationId)
  }

  fun canSetOrganizationUserRole(
      organizationId: OrganizationId,
      @Suppress("UNUSED_PARAMETER") role: Role
  ): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER, Role.ADMIN -> true
      else -> false
    }
  }

  fun canReadOrganization(organizationId: OrganizationId): Boolean {
    return organizationId in organizationRoles
  }

  fun canUpdateOrganization(organizationId: OrganizationId): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER, Role.ADMIN -> true
      else -> false
    }
  }

  fun canDeleteOrganization(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.OWNER
  }

  fun canCreateApiKey(organizationId: OrganizationId): Boolean {
    return when (organizationRoles[organizationId]) {
      Role.OWNER, Role.ADMIN -> true
      else -> false
    }
  }

  fun canDeleteApiKey(organizationId: OrganizationId): Boolean = canCreateApiKey(organizationId)

  fun canListApiKeys(organizationId: OrganizationId): Boolean = canCreateApiKey(organizationId)

  fun canCreateSpecies(): Boolean {
    return organizationRoles.values.any {
      it == Role.OWNER || it == Role.ADMIN || it == Role.MANAGER
    }
  }

  fun canDeleteSpecies(@Suppress("UNUSED_PARAMETER") speciesId: SpeciesId): Boolean =
      canCreateSpecies()

  fun canUpdateSpecies(@Suppress("UNUSED_PARAMETER") speciesId: SpeciesId): Boolean =
      canCreateSpecies()

  fun canCreateTimeseries(deviceId: DeviceId): Boolean {
    val facilityId = parentStore.getFacilityId(deviceId) ?: return false
    return facilityId in facilityRoles
  }

  fun canReadTimeseries(deviceId: DeviceId): Boolean = canCreateTimeseries(deviceId)

  fun canUpdateTimeseries(deviceId: DeviceId): Boolean = canCreateTimeseries(deviceId)

  /** Returns true if the user is an admin or owner of any organizations. */
  fun hasAnyAdminRole(): Boolean =
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
