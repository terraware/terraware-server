package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.currentUser
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
import java.security.Principal

/**
 * An entity on whose behalf the system can do work.
 *
 * The vast majority of the time, this will be a [IndividualUser], which represents an individual
 * user or a device manager. However, it can also be the [SystemUser], which isn't associated with a
 * particular person or a particular organization.
 */
interface TerrawareUser : Principal {
  val userId: UserId
  val userType: UserType

  /**
   * The user's Keycloak ID, if any. Null if this is an internal pseudo-user or if this user has
   * been invited to the system but has not yet registered with Keycloak.
   */
  val authId: String?

  /** The user's role in each organization they belong to. */
  val organizationRoles: Map<OrganizationId, Role>

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
  fun canCountNotifications(): Boolean
  fun canCreateAccession(facilityId: FacilityId): Boolean
  fun canCreateApiKey(organizationId: OrganizationId): Boolean
  fun canCreateAutomation(facilityId: FacilityId): Boolean
  fun canCreateBatch(facilityId: FacilityId): Boolean
  fun canCreateDevice(facilityId: FacilityId): Boolean
  fun canCreateDeviceManager(): Boolean
  fun canCreateFacility(organizationId: OrganizationId): Boolean
  fun canCreateNotification(targetUserId: UserId, organizationId: OrganizationId): Boolean
  fun canCreateSpecies(organizationId: OrganizationId): Boolean
  fun canCreateStorageLocation(facilityId: FacilityId): Boolean
  fun canCreateTimeseries(deviceId: DeviceId): Boolean
  fun canDeleteAccession(accessionId: AccessionId): Boolean
  fun canDeleteAutomation(automationId: AutomationId): Boolean
  fun canDeleteOrganization(organizationId: OrganizationId): Boolean
  fun canDeleteSelf(): Boolean
  fun canDeleteSpecies(speciesId: SpeciesId): Boolean
  fun canDeleteStorageLocation(storageLocationId: StorageLocationId): Boolean
  fun canDeleteUpload(uploadId: UploadId): Boolean
  fun canImportGlobalSpeciesData(): Boolean
  fun canListAutomations(facilityId: FacilityId): Boolean
  fun canListFacilities(organizationId: OrganizationId): Boolean
  fun canListNotifications(organizationId: OrganizationId?): Boolean
  fun canListOrganizationUsers(organizationId: OrganizationId): Boolean
  fun canReadAccession(accessionId: AccessionId): Boolean
  fun canReadAutomation(automationId: AutomationId): Boolean
  fun canReadBatch(batchId: BatchId): Boolean
  fun canReadDevice(deviceId: DeviceId): Boolean
  fun canReadDeviceManager(deviceManagerId: DeviceManagerId): Boolean
  fun canReadFacility(facilityId: FacilityId): Boolean
  fun canReadNotification(notificationId: NotificationId): Boolean
  fun canReadOrganization(organizationId: OrganizationId): Boolean
  fun canReadOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean
  fun canReadSpecies(speciesId: SpeciesId): Boolean
  fun canReadStorageLocation(storageLocationId: StorageLocationId): Boolean
  fun canReadTimeseries(deviceId: DeviceId): Boolean
  fun canReadUpload(uploadId: UploadId): Boolean
  fun canReadViabilityTest(viabilityTestId: ViabilityTestId): Boolean
  fun canRegenerateAllDeviceManagerTokens(): Boolean
  fun canRemoveOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean
  fun canSendAlert(facilityId: FacilityId): Boolean
  fun canSetOrganizationUserRole(organizationId: OrganizationId, role: Role): Boolean
  fun canSetTestClock(): Boolean
  fun canSetWithdrawalUser(accessionId: AccessionId): Boolean
  fun canTriggerAutomation(automationId: AutomationId): Boolean
  fun canUpdateAccession(accessionId: AccessionId): Boolean
  fun canUpdateAppVersions(): Boolean
  fun canUpdateAutomation(automationId: AutomationId): Boolean
  fun canUpdateDevice(deviceId: DeviceId): Boolean
  fun canUpdateDeviceManager(deviceManagerId: DeviceManagerId): Boolean
  fun canUpdateDeviceTemplates(): Boolean
  fun canUpdateFacility(facilityId: FacilityId): Boolean
  fun canUpdateNotification(notificationId: NotificationId): Boolean
  fun canUpdateNotifications(organizationId: OrganizationId?): Boolean
  fun canUpdateOrganization(organizationId: OrganizationId): Boolean
  fun canUpdateSpecies(speciesId: SpeciesId): Boolean
  fun canUpdateStorageLocation(storageLocationId: StorageLocationId): Boolean
  fun canUpdateTimeseries(deviceId: DeviceId): Boolean
  fun canUpdateUpload(uploadId: UploadId): Boolean

  // When adding new permissions, put them in alphabetical order in the above block.
}
