package com.terraformation.backend.customer.model

import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.DeviceManagerNotFoundException
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.NotificationNotFoundException
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.StorageLocationNotFoundException
import com.terraformation.backend.db.TimeseriesNotFoundException
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.ViabilityTestNotFoundException
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.nursery.db.BatchNotFoundException
import org.springframework.security.access.AccessDeniedException

/**
 * Declarative permission checks. The methods here check whether the user has permission to perform
 * various actions. If the user doesn't have permission, they throw exceptions. A method may throw
 * more than one kind of exception.
 *
 * You will usually not reference this class directly, but rather call its methods from a lambda
 * function you pass to [requirePermissions]. For example:
 * ```
 * requirePermissions { createDevice(facilityId) }
 * ```
 *
 * This class should not do any data access of its own; it should call the various `canX` methods on
 * [TerrawareUser] to determine whether the user has permission to do something.
 *
 * ## Exception behavior
 *
 * To ensure consistent behavior of the permission checks, the methods here should throw exceptions
 * using the following set of rules.
 *
 * - Always throw the most specific exception class that describes the failure. For example, the
 * rules will say to throw [EntityNotFoundException] but you'd actually want to throw, e.g.,
 * [AccessionNotFoundException] to give the caller more information about what failed.
 *
 * - Exception messages may be returned to the client and should never include any information that
 * you wouldn't want end users to see.
 *
 * - Exception messages should, if possible, include the identifier of the object the user didn't
 * have permission to operate on.
 *
 * - For read actions, if the object doesn't exist, throw [EntityNotFoundException]. Note that the
 * permission checking methods in [TerrawareUser] are required to return false if the target object
 * doesn't exist, so in most cases, it should suffice to just check for read permission.
 *
 * - For read actions, if the user doesn't have permission to see the object at all, throw
 * [EntityNotFoundException]. We want inaccessible data to act as if it doesn't exist at all.
 *
 * - For fine-grained read actions, if the user has permission to see the object as a whole but
 * doesn't have permission to see the specific part of the object, throw [AccessDeniedException].
 *
 * - For creation actions, if the user doesn't have permission to see the parent object (if any),
 * throw [EntityNotFoundException].
 *
 * - For write actions (including delete), if the user has permission to read the object but doesn't
 * have permission to perform the write operation, throw [AccessDeniedException].
 *
 * - For write actions, if the user doesn't have permission to read the object or to perform the
 * write operation, throw [EntityNotFoundException].
 *
 * ## Naming convention
 *
 * The methods in this class are named with an eye toward the call sites looking like declarations
 * of the required permissions, rather than looking like imperative operations. They describe what
 * the caller is asking permission to do. That is, if you call [createFacility], you're expressing
 * that you intend to create a facility, but [createFacility] doesn't actually create anything.
 * Although that isn't consistent with method naming conventions in the application as a whole,
 * you'll always be calling this code from inside a [requirePermissions] block, so the context
 * should make it clear what's going on.
 */
class PermissionRequirements(private val user: TerrawareUser) {
  fun addOrganizationUser(organizationId: OrganizationId) {
    if (!user.canAddOrganizationUser(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to add users to organization $organizationId")
    }
  }

  fun countNotifications() {
    if (!user.canCountNotifications()) {
      throw AccessDeniedException("No permission to count notifications")
    }
  }

  fun createAccession(facilityId: FacilityId) {
    if (!user.canCreateAccession(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create accessions in facility $facilityId")
    }
  }

  fun createApiKey(organizationId: OrganizationId) {
    if (!user.canCreateApiKey(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create API keys in organization $organizationId")
    }
  }

  fun createAutomation(facilityId: FacilityId) {
    if (!user.canCreateAutomation(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create automations in facility $facilityId")
    }
  }

  fun createBatch(facilityId: FacilityId) {
    if (!user.canCreateBatch(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create seedling batch")
    }
  }

  fun createDevice(facilityId: FacilityId) {
    if (!user.canCreateDevice(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create device in facility $facilityId")
    }
  }

  fun createDeviceManager() {
    if (!user.canCreateDeviceManager()) {
      throw AccessDeniedException("No permission to create device manager")
    }
  }

  fun createFacility(organizationId: OrganizationId) {
    if (!user.canCreateFacility(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create facilities in organization $organizationId")
    }
  }

  fun createNotification(userId: UserId, organizationId: OrganizationId) {
    readOrganization(organizationId)
    if (!user.canCreateNotification(userId, organizationId)) {
      throw AccessDeniedException("No permission to create notification")
    }
  }

  fun createSpecies(organizationId: OrganizationId) {
    if (!user.canCreateSpecies(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to create species")
    }
  }

  fun createStorageLocation(facilityId: FacilityId) {
    if (!user.canCreateStorageLocation(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException(
          "No permission to create storage location at facility $facilityId")
    }
  }

  fun createTimeseries(deviceId: DeviceId) {
    if (!user.canCreateTimeseries(deviceId)) {
      readDevice(deviceId)
      throw AccessDeniedException("No permission to create timeseries on device $deviceId")
    }
  }

  fun deleteAccession(accessionId: AccessionId) {
    if (!user.canDeleteAccession(accessionId)) {
      readAccession(accessionId)
      throw AccessDeniedException("No permission to delete accession $accessionId")
    }
  }

  fun deleteAutomation(automationId: AutomationId) {
    if (!user.canDeleteAutomation(automationId)) {
      readAutomation(automationId)
      throw AccessDeniedException("No permission to delete automation $automationId")
    }
  }

  fun deleteBatch(batchId: BatchId) {
    if (!user.canDeleteBatch(batchId)) {
      readBatch(batchId)
      throw AccessDeniedException("No permission to delete seedling batch $batchId")
    }
  }

  fun deleteOrganization(organizationId: OrganizationId) {
    if (!user.canDeleteOrganization(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to delete organization $organizationId")
    }
  }

  fun deleteSelf() {
    if (!user.canDeleteSelf()) {
      throw AccessDeniedException("No permission to delete self")
    }
  }

  fun deleteSpecies(speciesId: SpeciesId) {
    if (!user.canDeleteSpecies(speciesId)) {
      readSpecies(speciesId)
      throw AccessDeniedException("No permission to delete species $speciesId")
    }
  }

  fun deleteStorageLocation(storageLocationId: StorageLocationId) {
    if (!user.canDeleteStorageLocation(storageLocationId)) {
      readStorageLocation(storageLocationId)
      throw AccessDeniedException("No permission to delete storage location")
    }
  }

  fun deleteUpload(uploadId: UploadId) {
    if (!user.canDeleteUpload(uploadId)) {
      readUpload(uploadId)
      throw AccessDeniedException("No permission to delete upload")
    }
  }

  fun importGlobalSpeciesData() {
    if (!user.canImportGlobalSpeciesData()) {
      throw AccessDeniedException("No permission to import global species data")
    }
  }

  fun listAutomations(facilityId: FacilityId) {
    if (!user.canListAutomations(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to list automations in facility $facilityId")
    }
  }

  fun listNotifications(organizationId: OrganizationId?) {
    if (!user.canListNotifications(organizationId)) {
      if (organizationId != null) {
        readOrganization(organizationId)
      }
      throw AccessDeniedException("No permission to list notifications")
    }
  }

  fun listOrganizationUsers(organizationId: OrganizationId) {
    if (!user.canListOrganizationUsers(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to list users in organization $organizationId")
    }
  }

  fun readAccession(accessionId: AccessionId) {
    if (!user.canReadAccession(accessionId)) {
      throw AccessionNotFoundException(accessionId)
    }
  }

  fun readAutomation(automationId: AutomationId) {
    if (!user.canReadAutomation(automationId)) {
      throw AutomationNotFoundException(automationId)
    }
  }

  fun readBatch(batchId: BatchId) {
    if (!user.canReadBatch(batchId)) {
      throw BatchNotFoundException(batchId)
    }
  }

  fun readDevice(deviceId: DeviceId) {
    if (!user.canReadDevice(deviceId)) {
      throw DeviceNotFoundException(deviceId)
    }
  }

  fun readDeviceManager(deviceManagerId: DeviceManagerId) {
    if (!user.canReadDeviceManager(deviceManagerId)) {
      throw DeviceManagerNotFoundException(deviceManagerId)
    }
  }

  fun readFacility(facilityId: FacilityId) {
    if (!user.canReadFacility(facilityId)) {
      throw FacilityNotFoundException(facilityId)
    }
  }

  fun readNotification(notificationId: NotificationId) {
    if (!user.canReadNotification(notificationId)) {
      throw NotificationNotFoundException(notificationId)
    }
  }

  fun readOrganization(organizationId: OrganizationId) {
    if (!user.canReadOrganization(organizationId)) {
      throw OrganizationNotFoundException(organizationId)
    }
  }

  fun readOrganizationUser(organizationId: OrganizationId, userId: UserId) {
    if (!user.canReadOrganizationUser(organizationId, userId)) {
      readOrganization(organizationId)
      throw UserNotFoundException(userId)
    }
  }

  fun readSpecies(speciesId: SpeciesId) {
    if (!user.canReadSpecies(speciesId)) {
      throw SpeciesNotFoundException(speciesId)
    }
  }

  fun readStorageLocation(storageLocationId: StorageLocationId) {
    if (!user.canReadStorageLocation(storageLocationId)) {
      throw StorageLocationNotFoundException(storageLocationId)
    }
  }

  fun readTimeseries(deviceId: DeviceId) {
    if (!user.canReadTimeseries(deviceId)) {
      throw TimeseriesNotFoundException(deviceId)
    }
  }

  fun readUpload(uploadId: UploadId) {
    if (!user.canReadUpload(uploadId)) {
      throw UploadNotFoundException(uploadId)
    }
  }

  fun readViabilityTest(viabilityTestId: ViabilityTestId) {
    if (!user.canReadViabilityTest(viabilityTestId)) {
      throw ViabilityTestNotFoundException(viabilityTestId)
    }
  }

  fun regenerateAllDeviceManagerTokens() {
    if (!user.canRegenerateAllDeviceManagerTokens()) {
      throw AccessDeniedException("No permission to regenerate all device manager tokens")
    }
  }

  fun removeOrganizationUser(organizationId: OrganizationId, userId: UserId) {
    if (!user.canRemoveOrganizationUser(organizationId, userId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to remove user $userId from organization $organizationId")
    }
  }

  fun sendAlert(facilityId: FacilityId) {
    if (!user.canSendAlert(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to send alerts for facility $facilityId")
    }
  }

  fun setOrganizationUserRole(organizationId: OrganizationId, role: Role) {
    if (!user.canSetOrganizationUserRole(organizationId, role)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to grant role to users in organization $organizationId")
    }
  }

  fun setTestClock() {
    if (!user.canSetTestClock()) {
      throw AccessDeniedException("No permission to set test clock")
    }
  }

  fun setWithdrawalUser(accessionId: AccessionId) {
    if (!user.canSetWithdrawalUser(accessionId)) {
      readAccession(accessionId)
      throw AccessDeniedException("No permission to set withdrawal user for accession $accessionId")
    }
  }

  fun triggerAutomation(automationId: AutomationId) {
    if (!user.canTriggerAutomation(automationId)) {
      readAutomation(automationId)
      throw AccessDeniedException("No permission to trigger automation $automationId")
    }
  }

  fun updateAccession(accessionId: AccessionId) {
    if (!user.canUpdateAccession(accessionId)) {
      readAccession(accessionId)
      throw AccessDeniedException("No permission to update accession $accessionId")
    }
  }

  fun updateAppVersions() {
    if (!user.canUpdateAppVersions()) {
      throw AccessDeniedException("No permission to update app versions")
    }
  }

  fun updateAutomation(automationId: AutomationId) {
    if (!user.canUpdateAutomation(automationId)) {
      readAutomation(automationId)
      throw AccessDeniedException("No permission to update automation $automationId")
    }
  }

  fun updateBatch(batchId: BatchId) {
    if (!user.canUpdateBatch(batchId)) {
      readBatch(batchId)
      throw AccessDeniedException("No permission to update seedling batch $batchId")
    }
  }

  fun updateDevice(deviceId: DeviceId) {
    if (!user.canUpdateDevice(deviceId)) {
      readDevice(deviceId)
      throw AccessDeniedException("No permission to update device $deviceId")
    }
  }

  fun updateDeviceManager(deviceManagerId: DeviceManagerId) {
    if (!user.canUpdateDeviceManager(deviceManagerId)) {
      readDeviceManager(deviceManagerId)
      throw AccessDeniedException("No permission to update device manager")
    }
  }

  fun updateDeviceTemplates() {
    if (!user.canUpdateDeviceTemplates()) {
      throw AccessDeniedException("No permission to update device templates")
    }
  }

  fun updateFacility(facilityId: FacilityId) {
    if (!user.canUpdateFacility(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to update facility $facilityId")
    }
  }

  fun updateNotification(notificationId: NotificationId) {
    if (!user.canUpdateNotification(notificationId)) {
      readNotification(notificationId)
      throw AccessDeniedException("No permission to update notification")
    }
  }

  fun updateNotifications(organizationId: OrganizationId?) {
    if (!user.canUpdateNotifications(organizationId)) {
      if (organizationId != null) {
        readOrganization(organizationId)
      }
      throw AccessDeniedException("No permission to update notifications")
    }
  }

  fun updateOrganization(organizationId: OrganizationId) {
    if (!user.canUpdateOrganization(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to update organization $organizationId")
    }
  }

  fun updateSpecies(speciesId: SpeciesId) {
    if (!user.canUpdateSpecies(speciesId)) {
      readSpecies(speciesId)
      throw AccessDeniedException("No permission to update species $speciesId")
    }
  }

  fun updateStorageLocation(storageLocationId: StorageLocationId) {
    if (!user.canUpdateStorageLocation(storageLocationId)) {
      readStorageLocation(storageLocationId)
      throw AccessDeniedException("No permission to update storage location")
    }
  }

  fun updateTimeseries(deviceId: DeviceId) {
    if (!user.canUpdateTimeseries(deviceId)) {
      readTimeseries(deviceId)
      throw AccessDeniedException("No permission to update timeseries on device $deviceId")
    }
  }

  fun updateUpload(uploadId: UploadId) {
    if (!user.canUpdateUpload(uploadId)) {
      readUpload(uploadId)
      throw AccessDeniedException("No permission to update upload")
    }
  }
}
