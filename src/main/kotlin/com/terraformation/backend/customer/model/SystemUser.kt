package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.auth.currentUser
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
import com.terraformation.backend.db.SpeciesNameId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.UsersDao
import javax.annotation.ManagedBean

/**
 * Internal-use-only identity for automated tasks that aren't on behalf of a specific individual
 * user.
 *
 * This is needed because automated tasks can perform operations that need to be attributed to a
 * user, e.g., modifying accession data. We want to keep track of the fact that those operations
 * were initiated by the system itself rather than by a human user or an API client. In addition,
 * automated tasks need to call application code that has permission checks, which means there needs
 * to be a [currentUser] defined.
 *
 * This user is analogous to the "root" user on UNIX systems in that it always has permission to do
 * everything. However, it doesn't belong to any organizations or projects, and it is an error to
 * try to perform operations that depend on enumerating the current user's organization or project
 * memberships.
 *
 * There is no way for a client to authenticate as the system user; the application code has to
 * explicitly switch to this user identity, typically by calling this class's [run] method.
 */
@ManagedBean
class SystemUser(usersDao: UsersDao) : TerrawareUser {
  companion object {
    /**
     * The system user's username (email address) in the users table. This is used to look up the
     * system user's user ID.
     */
    private const val USERNAME = "system"
  }

  override val userId: UserId =
      usersDao.fetchOneByEmail(USERNAME)?.id
          ?: throw IllegalStateException("Unable to find user: $USERNAME")

  override val userType: UserType
    get() = UserType.System

  /*
   * The system user has no roles per se; it always has access to everything. Reject any attempts to
   * walk the current user's list of roles.
   *
   * If at some point we write code that needs to run as the system user and really needs a full
   * enumerated list of roles, it will be possible to change these methods to dump out the full list
   * of organizations/projects/etc. But it'll probably be better to solve the problem some other way
   * in that case, given that those lists will grow large over time.
   */

  override val organizationRoles: Map<OrganizationId, Role>
    get() {
      throw NotImplementedError("System user does not support enumerating roles")
    }
  override val projectRoles: Map<ProjectId, Role>
    get() {
      throw NotImplementedError("System user does not support enumerating roles")
    }
  override val facilityRoles: Map<FacilityId, Role>
    get() {
      throw NotImplementedError("System user does not support enumerating roles")
    }

  override fun <T> run(func: () -> T): T {
    return CurrentUserHolder.runAs(this, func)
  }

  override fun hasAnyAdminRole(): Boolean = true
  override fun getName(): String = USERNAME

  /*
   * All permission checks always succeed.
   */

  override fun canAddOrganizationUser(organizationId: OrganizationId): Boolean = true
  override fun canAddProjectUser(projectId: ProjectId): Boolean = true
  override fun canCreateAccession(facilityId: FacilityId): Boolean = true
  override fun canCreateApiKey(organizationId: OrganizationId): Boolean = true
  override fun canCreateAutomation(facilityId: FacilityId): Boolean = true
  override fun canCreateDevice(facilityId: FacilityId): Boolean = true
  override fun canCreateFacility(siteId: SiteId): Boolean = true
  override fun canCreateFeature(layerId: LayerId): Boolean = true
  override fun canCreateLayer(siteId: SiteId): Boolean = true
  override fun canCreateProject(organizationId: OrganizationId): Boolean = true
  override fun canCreateSite(projectId: ProjectId): Boolean = true
  override fun canCreateSpecies(organizationId: OrganizationId): Boolean = true
  override fun canCreateSpeciesName(organizationId: OrganizationId): Boolean = true
  override fun canCreateStorageLocation(facilityId: FacilityId): Boolean = true
  override fun canCreateTimeseries(deviceId: DeviceId): Boolean = true
  override fun canDeleteApiKey(organizationId: OrganizationId): Boolean = true
  override fun canDeleteAutomation(automationId: AutomationId): Boolean = true
  override fun canDeleteFeature(featureId: FeatureId): Boolean = true
  override fun canDeleteFeaturePhoto(photoId: PhotoId): Boolean = true
  override fun canDeleteLayer(layerId: LayerId): Boolean = true
  override fun canDeleteOrganization(organizationId: OrganizationId): Boolean = true
  override fun canDeleteSite(siteId: SiteId): Boolean = true
  override fun canDeleteSpecies(organizationId: OrganizationId): Boolean = true
  override fun canDeleteSpeciesName(speciesNameId: SpeciesNameId): Boolean = true
  override fun canDeleteStorageLocation(storageLocationId: StorageLocationId): Boolean = true
  override fun canListApiKeys(organizationId: OrganizationId): Boolean = true
  override fun canListAutomations(facilityId: FacilityId): Boolean = true
  override fun canListFacilities(siteId: SiteId): Boolean = true
  override fun canListOrganizationUsers(organizationId: OrganizationId): Boolean = true
  override fun canListProjects(organizationId: OrganizationId): Boolean = true
  override fun canListSites(projectId: ProjectId): Boolean = true
  override fun canReadAccession(accessionId: AccessionId): Boolean = true
  override fun canReadAutomation(automationId: AutomationId): Boolean = true
  override fun canReadDevice(deviceId: DeviceId): Boolean = true
  override fun canReadFacility(facilityId: FacilityId): Boolean = true
  override fun canReadFeature(featureId: FeatureId): Boolean = true
  override fun canReadFeaturePhoto(photoId: PhotoId): Boolean = true
  override fun canReadLayer(layerId: LayerId): Boolean = true
  override fun canReadOrganization(organizationId: OrganizationId): Boolean = true
  override fun canReadProject(projectId: ProjectId): Boolean = true
  override fun canReadSite(siteId: SiteId): Boolean = true
  override fun canReadSpecies(organizationId: OrganizationId): Boolean = true
  override fun canReadSpeciesName(speciesNameId: SpeciesNameId): Boolean = true
  override fun canReadStorageLocation(storageLocationId: StorageLocationId): Boolean = true
  override fun canReadTimeseries(deviceId: DeviceId): Boolean = true
  override fun canRemoveOrganizationUser(organizationId: OrganizationId): Boolean = true
  override fun canRemoveProjectUser(projectId: ProjectId): Boolean = true
  override fun canSendAlert(facilityId: FacilityId): Boolean = true
  override fun canSetOrganizationUserRole(organizationId: OrganizationId, role: Role): Boolean =
      true
  override fun canUpdateAccession(accessionId: AccessionId): Boolean = true
  override fun canUpdateAutomation(automationId: AutomationId): Boolean = true
  override fun canUpdateDevice(deviceId: DeviceId): Boolean = true
  override fun canUpdateFacility(facilityId: FacilityId): Boolean = true
  override fun canUpdateFeature(featureId: FeatureId): Boolean = true
  override fun canUpdateLayer(layerId: LayerId): Boolean = true
  override fun canUpdateOrganization(organizationId: OrganizationId): Boolean = true
  override fun canUpdateProject(projectId: ProjectId): Boolean = true
  override fun canUpdateSite(siteId: SiteId): Boolean = true
  override fun canUpdateSpecies(organizationId: OrganizationId): Boolean = true
  override fun canUpdateSpeciesName(speciesNameId: SpeciesNameId): Boolean = true
  override fun canUpdateStorageLocation(storageLocationId: StorageLocationId): Boolean = true
  override fun canUpdateTimeseries(deviceId: DeviceId): Boolean = true
}
