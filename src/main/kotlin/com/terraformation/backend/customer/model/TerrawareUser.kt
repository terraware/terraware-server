package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceManagerId
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
import java.security.Principal

/**
 * An entity on whose behalf the system can do work.
 *
 * The vast majority of the time, this will be a [IndividualUser], which represents an individual
 * user or an API client. However, it can also be the [SystemUser], which isn't associated with a
 * particular person or a particular organization.
 */
interface TerrawareUser : Principal {
  val userId: UserId
  val userType: UserType

  /** The user's role in each organization they belong to. */
  val organizationRoles: Map<OrganizationId, Role>

  /**
   * The user's role in each project they have access to. Currently, roles are assigned
   * per-organization, so this is really the user's role in the organization that owns each project.
   */
  val projectRoles: Map<ProjectId, Role>

  /**
   * The user's role in each facility they have access to. Currently, roles are assigned
   * per-organization, so this is really the user's role in the organization that owns the project
   * and site of each facility.
   */
  val facilityRoles: Map<FacilityId, Role>

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
  fun <T> run(func: () -> T): T

  /** Returns true if the user is an admin or owner of any organizations. */
  fun hasAnyAdminRole(): Boolean

  /*
   * Permission checks. Each of these returns true if the user has permission to perform the action.
   */

  fun canAddOrganizationUser(organizationId: OrganizationId): Boolean
  fun canAddProjectUser(projectId: ProjectId): Boolean
  fun canCreateAccession(facilityId: FacilityId): Boolean
  fun canCreateApiKey(organizationId: OrganizationId): Boolean
  fun canCreateAutomation(facilityId: FacilityId): Boolean
  fun canCreateDevice(facilityId: FacilityId): Boolean
  fun canCreateDeviceManager(): Boolean
  fun canCreateFacility(siteId: SiteId): Boolean
  fun canCreateProject(organizationId: OrganizationId): Boolean
  fun canCreateSite(projectId: ProjectId): Boolean
  fun canCreateSpecies(organizationId: OrganizationId): Boolean
  fun canCreateStorageLocation(facilityId: FacilityId): Boolean
  fun canCreateTimeseries(deviceId: DeviceId): Boolean
  fun canDeleteApiKey(organizationId: OrganizationId): Boolean
  fun canDeleteAutomation(automationId: AutomationId): Boolean
  fun canDeleteOrganization(organizationId: OrganizationId): Boolean
  fun canDeleteSite(siteId: SiteId): Boolean
  fun canDeleteSpecies(speciesId: SpeciesId): Boolean
  fun canDeleteStorageLocation(storageLocationId: StorageLocationId): Boolean
  fun canDeleteUpload(uploadId: UploadId): Boolean
  fun canImportGlobalSpeciesData(): Boolean
  fun canListApiKeys(organizationId: OrganizationId): Boolean
  fun canListAutomations(facilityId: FacilityId): Boolean
  fun canListFacilities(siteId: SiteId): Boolean
  fun canListOrganizationUsers(organizationId: OrganizationId): Boolean
  fun canListProjects(organizationId: OrganizationId): Boolean
  fun canListSites(projectId: ProjectId): Boolean
  fun canReadAccession(accessionId: AccessionId): Boolean
  fun canReadAutomation(automationId: AutomationId): Boolean
  fun canReadDevice(deviceId: DeviceId): Boolean
  fun canReadDeviceManager(deviceManagerId: DeviceManagerId): Boolean
  fun canReadFacility(facilityId: FacilityId): Boolean
  fun canReadOrganization(organizationId: OrganizationId): Boolean
  fun canReadProject(projectId: ProjectId): Boolean
  fun canReadSite(siteId: SiteId): Boolean
  fun canReadSpecies(speciesId: SpeciesId): Boolean
  fun canReadStorageLocation(storageLocationId: StorageLocationId): Boolean
  fun canReadTimeseries(deviceId: DeviceId): Boolean
  fun canReadUpload(uploadId: UploadId): Boolean
  fun canRemoveOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean
  fun canRemoveProjectUser(projectId: ProjectId, userId: UserId): Boolean
  fun canSendAlert(facilityId: FacilityId): Boolean
  fun canSetOrganizationUserRole(organizationId: OrganizationId, role: Role): Boolean
  fun canTriggerAutomation(automationId: AutomationId): Boolean
  fun canUpdateAccession(accessionId: AccessionId): Boolean
  fun canUpdateAutomation(automationId: AutomationId): Boolean
  fun canUpdateDevice(deviceId: DeviceId): Boolean
  fun canUpdateDeviceManager(deviceManagerId: DeviceManagerId): Boolean
  fun canUpdateDeviceTemplates(): Boolean
  fun canUpdateFacility(facilityId: FacilityId): Boolean
  fun canUpdateOrganization(organizationId: OrganizationId): Boolean
  fun canUpdateProject(projectId: ProjectId): Boolean
  fun canUpdateSite(siteId: SiteId): Boolean
  fun canUpdateSpecies(speciesId: SpeciesId): Boolean
  fun canUpdateStorageLocation(storageLocationId: StorageLocationId): Boolean
  fun canUpdateTimeseries(deviceId: DeviceId): Boolean
  fun canUpdateUpload(uploadId: UploadId): Boolean
  fun canReadNotification(notificationId: NotificationId): Boolean
  fun canListNotifications(organizationId: OrganizationId?): Boolean
  fun canCountNotifications(): Boolean
  fun canUpdateNotification(notificationId: NotificationId): Boolean
  fun canUpdateNotifications(organizationId: OrganizationId?): Boolean
  fun canCreateNotification(targetUserId: UserId, organizationId: OrganizationId): Boolean
}
