package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.SuperAdminAuthority
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.util.ResettableLazy
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.springframework.security.core.GrantedAuthority

/**
 * Details about the terraware user who is making the current request and the permissions they have.
 * This always represents a regular (presumably human) user, and is separate from [FunderUser].
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
    override val parentStore: ParentStore,
    override val permissionStore: PermissionStore,
) :
    OrdinaryUser(
        createdTime,
        userId,
        authId,
        email,
        emailNotificationsEnabled,
        firstName,
        lastName,
        countryCode,
        cookiesConsented,
        cookiesConsentedTime,
        locale,
        timeZone,
        UserType.Individual,
        parentStore,
        permissionStore) {

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

  override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
    return if (isSuperAdmin()) {
      mutableSetOf(SuperAdminAuthority)
    } else {
      mutableSetOf()
    }
  }

  // Capabilities

  override fun isAcceleratorAdmin(): Boolean {
    recordPermissionCheck(GlobalRolePermissionCheck(GlobalRole.AcceleratorAdmin))
    return setOf(GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin).any { it in globalRoles }
  }

  override fun isTFExpertOrHigher(): Boolean {
    recordPermissionCheck(GlobalRolePermissionCheck(GlobalRole.TFExpert))
    return setOf(GlobalRole.TFExpert, GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin).any {
      it in globalRoles
    }
  }

  override fun isReadOnlyOrHigher(): Boolean {
    recordPermissionCheck(GlobalRolePermissionCheck(GlobalRole.ReadOnly))
    return setOf(
            GlobalRole.ReadOnly,
            GlobalRole.TFExpert,
            GlobalRole.AcceleratorAdmin,
            GlobalRole.SuperAdmin)
        .any { it in globalRoles }
  }

  override fun isSuperAdmin(): Boolean {
    recordPermissionCheck(GlobalRolePermissionCheck(GlobalRole.SuperAdmin))
    return GlobalRole.SuperAdmin in globalRoles
  }

  override fun isOwner(organizationId: OrganizationId?) =
      organizationId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Owner, organizationId))
        organizationRoles[organizationId] == Role.Owner
      } ?: false

  override fun isAdminOrHigher(facilityId: FacilityId?) =
      facilityId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Admin, facilityId))
        when (facilityRoles[facilityId]) {
          Role.Admin,
          Role.Owner,
          Role.TerraformationContact -> true
          else -> false
        }
      } ?: false

  override fun isManagerOrHigher(organizationId: OrganizationId?) =
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

  override fun isManagerOrHigher(facilityId: FacilityId?) =
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

  override fun isManagerOrHigher(plantingSiteId: PlantingSiteId?) =
      plantingSiteId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Manager, plantingSiteId))
        isManagerOrHigher(parentStore.getOrganizationId(plantingSiteId))
      } ?: false

  override fun isMember(facilityId: FacilityId?) =
      facilityId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Contributor, facilityId))
        facilityId in facilityRoles
      } ?: false

  override fun isMember(organizationId: OrganizationId?) =
      organizationId?.let {
        recordPermissionCheck(RolePermissionCheck(Role.Contributor, organizationId))
        organizationId in organizationRoles
      } ?: false

  /** Returns true if one of the user's global roles allows them to read an organization. */
  override fun isGlobalReader(organizationId: OrganizationId) =
      GlobalRole.SuperAdmin in globalRoles ||
          (isReadOnlyOrHigher() &&
              (parentStore.hasInternalTag(organizationId, InternalTagIds.Accelerator) ||
                  parentStore.hasApplications(organizationId)))

  /** Returns true if one of the user's global roles allows them to write to an organization. */
  override fun isGlobalWriter(organizationId: OrganizationId) =
      GlobalRole.SuperAdmin in globalRoles ||
          (isTFExpertOrHigher() &&
              (parentStore.hasInternalTag(organizationId, InternalTagIds.Accelerator) ||
                  parentStore.hasApplications(organizationId)))
}
