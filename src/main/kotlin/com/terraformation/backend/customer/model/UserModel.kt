package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.SuperAdminAuthority
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.AccessionsDao
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
 * This class attempts to abstract away the implementation details of the permission checking
 * business logic. It has a bunch of methods like [canCreateSite] to check for specific permissions.
 * In general, we want those methods to be as fine-grained as possible and to take fine-grained
 * context as parameters, even if the actual permissions are coarse-grained. The calling code
 * shouldn't have to make assumptions about how permissions are computed. For example, the ability
 * to create a site may be determined by the user's organization-level role, but [canCreateSite]
 * takes a project ID argument. This way, if we add fine-grained permissions later on, existing
 * calls to [canCreateSite] won't need to be modified.
 *
 * In addition to holding some basic details about the user, this object also serves as a
 * short-lived cache for information such as the user's roles in various contexts. For example, if
 * you access [siteRoles] you will get a map of the user's role at each site they have access to.
 * The first time you access that property, it will be fetched from the database, but it will be
 * cached afterwards.
 */
class UserModel(
    val userId: UserId,
    private val authId: String,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    private val userType: UserType,
    private val accessionsDao: AccessionsDao,
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
    val newAuthentication = PreAuthenticatedAuthenticationToken(this, "N/A", authorities)
    try {
      context.authentication = newAuthentication
      func()
    } finally {
      context.authentication = oldAuthentication
    }
  }

  fun canCreateAccession(facilityId: FacilityId): Boolean {
    // All users in a project can create accessions.
    return facilityId in facilityRoles
  }

  fun canReadAccession(accessionId: AccessionId, facilityId: FacilityId? = null): Boolean {
    val effectiveFacilityId =
        facilityId ?: accessionsDao.fetchOneById(accessionId)?.facilityId ?: return false

    // All users in a project can read all accessions in the project's facilities.
    return effectiveFacilityId in facilityRoles
  }

  fun canUpdateAccession(accessionId: AccessionId, facilityId: FacilityId? = null): Boolean {
    // All users in a project can write all accessions in the project's facilities, so this
    // is the same as the read permission check.
    return canReadAccession(accessionId, facilityId)
  }

  fun canCreateFacility(siteId: SiteId): Boolean {
    val role = siteRoles[siteId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  fun canListFacilities(siteId: SiteId): Boolean {
    // Any user who has access to a site can list its facilities.
    return siteRoles[siteId] != null
  }

  private fun canAccessLayer(siteId: SiteId): Boolean {
    // Any user who has access to a site can access all its field data
    return siteId in siteRoles
  }

  fun canCreateLayer(siteId: SiteId): Boolean {
    return canAccessLayer(siteId)
  }

  fun canReadLayer(siteId: SiteId): Boolean {
    return canAccessLayer(siteId)
  }

  fun canUpdateLayer(siteId: SiteId): Boolean {
    return canAccessLayer(siteId)
  }

  fun canDeleteLayer(siteId: SiteId): Boolean {
    return canAccessLayer(siteId)
  }

  fun canCreateSite(projectId: ProjectId): Boolean {
    val role = projectRoles[projectId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  @Suppress("UNUSED_PARAMETER")
  fun canListSites(projectId: ProjectId): Boolean {
    // Any user who has access to a project can list its sites.
    return projectRoles[projectId] != null
  }

  fun canReadSite(siteId: SiteId): Boolean {
    return siteRoles[siteId] != null
  }

  fun canCreateProject(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.ADMIN || role == Role.OWNER
  }

  fun canAddProjectUser(projectId: ProjectId): Boolean {
    val role = projectRoles[projectId]
    return role == Role.MANAGER || role == Role.ADMIN || role == Role.OWNER
  }

  fun canRemoveProjectUser(projectId: ProjectId): Boolean {
    return canAddProjectUser(projectId)
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

  fun canDeleteOrganization(organizationId: OrganizationId): Boolean {
    val role = organizationRoles[organizationId]
    return role == Role.OWNER
  }

  /**
   * Temporary helper to get the user's facility ID.
   *
   * TODO: Remove this once the client passes in facility IDs.
   */
  fun defaultFacilityId(): FacilityId {
    if (facilityRoles.isNotEmpty()) {
      return facilityRoles.keys.first()
    } else {
      throw IllegalStateException("User has no facilities")
    }
  }

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

  override fun getName(): String = authId
  override fun getUsername(): String = authId
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
