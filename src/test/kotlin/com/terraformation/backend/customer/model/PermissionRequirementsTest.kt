package com.terraformation.backend.customer.model

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.DeviceManagerNotFoundException
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.NotificationNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.StorageLocationNotFoundException
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UploadNotFoundException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.ViabilityTestNotFoundException
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

/**
 * Tests the exception-throwing logic in [PermissionRequirements].
 *
 * The general form of the test methods here is
 *
 * 1. Assert the exception that's thrown if the exception-checking method is called when the user
 * has none of the relevant permissions at all
 *
 * 2. For each additional exception that can be thrown (if any), grant the user a new permission and
 * assert that the alternate exception is thrown
 *
 * 3. Grant the final permission that allows the check to succeed
 *
 * 4. Call the permission checking method again; the test will fail if it throws an exception
 */
internal class PermissionRequirementsTest : RunsAsUser {
  override val user: TerrawareUser = mockk(relaxed = true)
  private val requirements = PermissionRequirements(user)

  private val accessionId = AccessionId(1)
  private val automationId = AutomationId(1)
  private val deviceId = DeviceId(1)
  private val deviceManagerId = DeviceManagerId(1)
  private val facilityId = FacilityId(1)
  private val notificationUserId = UserId(2)
  private val notificationId = NotificationId(1)
  private val organizationId = OrganizationId(1)
  private val role = Role.CONTRIBUTOR
  private val speciesId = SpeciesId(1)
  private val storageLocationId = StorageLocationId(1)
  private val uploadId = UploadId(1)
  private val userId = UserId(1)
  private val viabilityTestId = ViabilityTestId(1)

  /**
   * Grants permission to perform a particular operation. This is a simple wrapper around a MockK
   * `every { user.canX() } returns true` call, but with a more concise syntax and a more meaningful
   * name.
   */
  private fun grant(stubBlock: MockKMatcherScope.() -> Boolean) {
    every(stubBlock) returns true
  }

  @Test
  fun addOrganizationUser() {
    assertThrows<OrganizationNotFoundException> { requirements.addOrganizationUser(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.addOrganizationUser(organizationId) }

    grant { user.canAddOrganizationUser(organizationId) }
    requirements.addOrganizationUser(organizationId)
  }

  @Test
  fun createAccession() {
    assertThrows<FacilityNotFoundException> { requirements.createAccession(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.createAccession(facilityId) }

    grant { user.canCreateAccession(facilityId) }
    requirements.createAccession(facilityId)
  }

  @Test
  fun createApiKey() {
    assertThrows<OrganizationNotFoundException> { requirements.createApiKey(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.createApiKey(organizationId) }

    grant { user.canCreateApiKey(organizationId) }
    requirements.createApiKey(organizationId)
  }

  @Test
  fun createAutomation() {
    assertThrows<FacilityNotFoundException> { requirements.createAutomation(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.createAutomation(facilityId) }

    grant { user.canCreateAutomation(facilityId) }
    requirements.createAutomation(facilityId)
  }

  @Test
  fun createDevice() {
    assertThrows<FacilityNotFoundException> { requirements.createDevice(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.createDevice(facilityId) }

    grant { user.canCreateDevice(facilityId) }
    requirements.createDevice(facilityId)
  }

  @Test
  fun createDeviceManager() {
    assertThrows<AccessDeniedException> { requirements.createDeviceManager() }

    grant { user.canCreateDeviceManager() }
    requirements.createDeviceManager()
  }

  @Test
  fun createFacility() {
    assertThrows<OrganizationNotFoundException> { requirements.createFacility(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.createFacility(organizationId) }

    grant { user.canCreateFacility(organizationId) }
    requirements.createFacility(organizationId)
  }

  @Test
  fun createNotification() {
    assertThrows<OrganizationNotFoundException> {
      requirements.createNotification(notificationUserId, organizationId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> {
      requirements.createNotification(notificationUserId, organizationId)
    }

    grant { user.canCreateNotification(notificationUserId, organizationId) }
    requirements.createNotification(notificationUserId, organizationId)
  }

  @Test
  fun createSpecies() {
    assertThrows<OrganizationNotFoundException> { requirements.createSpecies(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.createSpecies(organizationId) }

    grant { user.canCreateSpecies(organizationId) }
    requirements.createSpecies(organizationId)
  }

  @Test
  fun createStorageLocation() {
    assertThrows<FacilityNotFoundException> { requirements.createStorageLocation(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.createStorageLocation(facilityId) }

    grant { user.canCreateStorageLocation(facilityId) }
    requirements.createStorageLocation(facilityId)
  }

  @Test
  fun createTimeseries() {
    assertThrows<DeviceNotFoundException> { requirements.createTimeseries(deviceId) }

    grant { user.canReadDevice(deviceId) }
    assertThrows<AccessDeniedException> { requirements.createTimeseries(deviceId) }

    grant { user.canCreateTimeseries(deviceId) }
    requirements.createTimeseries(deviceId)
  }

  @Test
  fun deleteAccession() {
    assertThrows<AccessionNotFoundException> { requirements.deleteAccession(accessionId) }

    grant { user.canReadAccession(accessionId) }
    assertThrows<AccessDeniedException> { requirements.deleteAccession(accessionId) }

    grant { user.canDeleteAccession(accessionId) }
    requirements.deleteAccession(accessionId)
  }

  @Test
  fun deleteAutomation() {
    assertThrows<AutomationNotFoundException> { requirements.deleteAutomation(automationId) }

    grant { user.canReadAutomation(automationId) }
    assertThrows<AccessDeniedException> { requirements.deleteAutomation(automationId) }

    grant { user.canDeleteAutomation(automationId) }
    requirements.deleteAutomation(automationId)
  }

  @Test
  fun deleteOrganization() {
    assertThrows<OrganizationNotFoundException> { requirements.deleteOrganization(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.deleteOrganization(organizationId) }

    grant { user.canDeleteOrganization(organizationId) }
    requirements.deleteOrganization(organizationId)
  }

  @Test
  fun deleteSpecies() {
    assertThrows<SpeciesNotFoundException> { requirements.deleteSpecies(speciesId) }

    grant { user.canReadSpecies(speciesId) }
    assertThrows<AccessDeniedException> { requirements.deleteSpecies(speciesId) }

    grant { user.canDeleteSpecies(speciesId) }
    requirements.deleteSpecies(speciesId)
  }

  @Test
  fun deleteStorageLocation() {
    assertThrows<StorageLocationNotFoundException> {
      requirements.deleteStorageLocation(storageLocationId)
    }

    grant { user.canReadStorageLocation(storageLocationId) }
    assertThrows<AccessDeniedException> { requirements.deleteStorageLocation(storageLocationId) }

    grant { user.canDeleteStorageLocation(storageLocationId) }
    requirements.deleteStorageLocation(storageLocationId)
  }

  @Test
  fun deleteUpload() {
    assertThrows<UploadNotFoundException> { requirements.deleteUpload(uploadId) }

    grant { user.canReadUpload(uploadId) }
    assertThrows<AccessDeniedException> { requirements.deleteUpload(uploadId) }

    grant { user.canDeleteUpload(uploadId) }
    requirements.deleteUpload(uploadId)
  }

  @Test
  fun importGlobalSpeciesData() {
    assertThrows<AccessDeniedException> { requirements.importGlobalSpeciesData() }

    grant { user.canImportGlobalSpeciesData() }
    requirements.importGlobalSpeciesData()
  }

  @Test
  fun listAutomations() {
    assertThrows<FacilityNotFoundException> { requirements.listAutomations(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.listAutomations(facilityId) }

    grant { user.canListAutomations(facilityId) }
    requirements.listAutomations(facilityId)
  }

  @Test
  fun listGlobalNotifications() {
    assertThrows<AccessDeniedException> { requirements.listNotifications(null) }

    grant { user.canListNotifications(null) }
    requirements.listNotifications(null)
  }

  @Test
  fun listOrganizationNotifications() {
    assertThrows<OrganizationNotFoundException> { requirements.listNotifications(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.listNotifications(organizationId) }

    grant { user.canListNotifications(organizationId) }
    requirements.listNotifications(organizationId)
  }

  @Test
  fun listOrganizationUsers() {
    assertThrows<OrganizationNotFoundException> {
      requirements.listOrganizationUsers(organizationId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.listOrganizationUsers(organizationId) }

    grant { user.canListOrganizationUsers(organizationId) }
    requirements.listOrganizationUsers(organizationId)
  }

  @Test
  fun readAccession() {
    assertThrows<AccessionNotFoundException> { requirements.readAccession(accessionId) }

    grant { user.canReadAccession(accessionId) }
    requirements.readAccession(accessionId)
  }

  @Test
  fun readAutomation() {
    assertThrows<AutomationNotFoundException> { requirements.readAutomation(automationId) }

    grant { user.canReadAutomation(automationId) }
    requirements.readAutomation(automationId)
  }

  @Test
  fun readDevice() {
    assertThrows<DeviceNotFoundException> { requirements.readDevice(deviceId) }

    grant { user.canReadDevice(deviceId) }
    requirements.readDevice(deviceId)
  }

  @Test
  fun readDeviceManager() {
    assertThrows<DeviceManagerNotFoundException> { requirements.readDeviceManager(deviceManagerId) }

    grant { user.canReadDeviceManager(deviceManagerId) }
    requirements.readDeviceManager(deviceManagerId)
  }

  @Test
  fun readFacility() {
    assertThrows<FacilityNotFoundException> { requirements.readFacility(facilityId) }

    grant { user.canReadFacility(facilityId) }
    requirements.readFacility(facilityId)
  }

  @Test
  fun readNotification() {
    assertThrows<NotificationNotFoundException> { requirements.readNotification(notificationId) }

    grant { user.canReadNotification(notificationId) }
    requirements.readNotification(notificationId)
  }

  @Test
  fun readOrganization() {
    assertThrows<OrganizationNotFoundException> { requirements.readOrganization(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    requirements.readOrganization(organizationId)
  }

  @Test
  fun readSpecies() {
    assertThrows<SpeciesNotFoundException> { requirements.readSpecies(speciesId) }

    grant { user.canReadSpecies(speciesId) }
    requirements.readSpecies(speciesId)
  }

  @Test
  fun readStorageLocation() {
    assertThrows<StorageLocationNotFoundException> {
      requirements.readStorageLocation(storageLocationId)
    }

    grant { user.canReadStorageLocation(storageLocationId) }
    requirements.readStorageLocation(storageLocationId)
  }

  @Test
  fun readUpload() {
    assertThrows<UploadNotFoundException> { requirements.readUpload(uploadId) }

    grant { user.canReadUpload(uploadId) }
    requirements.readUpload(uploadId)
  }

  @Test
  fun readViabilityTest() {
    assertThrows<ViabilityTestNotFoundException> { requirements.readViabilityTest(viabilityTestId) }

    grant { user.canReadViabilityTest(viabilityTestId) }
    requirements.readViabilityTest(viabilityTestId)
  }

  @Test
  fun regenerateAllDeviceManagerTokens() {
    assertThrows<AccessDeniedException> { requirements.regenerateAllDeviceManagerTokens() }

    grant { user.canRegenerateAllDeviceManagerTokens() }
    requirements.regenerateAllDeviceManagerTokens()
  }

  @Test
  fun removeOrganizationUser() {
    assertThrows<OrganizationNotFoundException> {
      requirements.removeOrganizationUser(organizationId, userId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> {
      requirements.removeOrganizationUser(organizationId, userId)
    }

    grant { user.canRemoveOrganizationUser(organizationId, userId) }
    requirements.removeOrganizationUser(organizationId, userId)
  }

  @Test
  fun sendAlert() {
    assertThrows<FacilityNotFoundException> { requirements.sendAlert(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.sendAlert(facilityId) }

    grant { user.canSendAlert(facilityId) }
    requirements.sendAlert(facilityId)
  }

  @Test
  fun setOrganizationUserRole() {
    assertThrows<OrganizationNotFoundException> {
      requirements.setOrganizationUserRole(organizationId, role)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> {
      requirements.setOrganizationUserRole(organizationId, role)
    }

    grant { user.canSetOrganizationUserRole(organizationId, role) }
    requirements.setOrganizationUserRole(organizationId, role)
  }

  @Test
  fun setTestClock() {
    assertThrows<AccessDeniedException> { requirements.setTestClock() }

    grant { user.canSetTestClock() }
    requirements.setTestClock()
  }

  @Test
  fun triggerAutomation() {
    assertThrows<AutomationNotFoundException> { requirements.triggerAutomation(automationId) }

    grant { user.canReadAutomation(automationId) }
    assertThrows<AccessDeniedException> { requirements.triggerAutomation(automationId) }

    grant { user.canTriggerAutomation(automationId) }
    requirements.triggerAutomation(automationId)
  }

  @Test
  fun updateAccession() {
    assertThrows<AccessionNotFoundException> { requirements.updateAccession(accessionId) }

    grant { user.canReadAccession(accessionId) }
    assertThrows<AccessDeniedException> { requirements.updateAccession(accessionId) }

    grant { user.canUpdateAccession(accessionId) }
    requirements.updateAccession(accessionId)
  }

  @Test
  fun updateAutomation() {
    assertThrows<AutomationNotFoundException> { requirements.updateAutomation(automationId) }

    grant { user.canReadAutomation(automationId) }
    assertThrows<AccessDeniedException> { requirements.updateAutomation(automationId) }

    grant { user.canUpdateAutomation(automationId) }
    requirements.updateAutomation(automationId)
  }

  @Test
  fun updateDevice() {
    assertThrows<DeviceNotFoundException> { requirements.updateDevice(deviceId) }

    grant { user.canReadDevice(deviceId) }
    assertThrows<AccessDeniedException> { requirements.updateDevice(deviceId) }

    grant { user.canUpdateDevice(deviceId) }
    requirements.updateDevice(deviceId)
  }

  @Test
  fun updateDeviceManager() {
    assertThrows<DeviceManagerNotFoundException> {
      requirements.updateDeviceManager(deviceManagerId)
    }

    grant { user.canReadDeviceManager(deviceManagerId) }
    assertThrows<AccessDeniedException> { requirements.updateDeviceManager(deviceManagerId) }

    grant { user.canUpdateDeviceManager(deviceManagerId) }
    requirements.updateDeviceManager(deviceManagerId)
  }

  @Test
  fun updateDeviceTemplates() {
    assertThrows<AccessDeniedException> { requirements.updateDeviceTemplates() }

    grant { user.canUpdateDeviceTemplates() }
    requirements.updateDeviceTemplates()
  }

  @Test
  fun updateFacility() {
    assertThrows<FacilityNotFoundException> { requirements.updateFacility(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.updateFacility(facilityId) }

    grant { user.canUpdateFacility(facilityId) }
    requirements.updateFacility(facilityId)
  }

  @Test
  fun updateGlobalNotifications() {
    assertThrows<AccessDeniedException> { requirements.updateNotifications(null) }

    grant { user.canUpdateNotifications(null) }
    requirements.updateNotifications(null)
  }

  @Test
  fun updateNotification() {
    assertThrows<NotificationNotFoundException> { requirements.updateNotification(notificationId) }

    grant { user.canUpdateNotification(notificationId) }
    requirements.updateNotification(notificationId)
  }

  @Test
  fun updateOrganization() {
    assertThrows<OrganizationNotFoundException> { requirements.updateOrganization(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.updateOrganization(organizationId) }

    grant { user.canUpdateOrganization(organizationId) }
    requirements.updateOrganization(organizationId)
  }

  @Test
  fun updateOrganizationNotifications() {
    assertThrows<OrganizationNotFoundException> { requirements.updateNotifications(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.updateNotifications(organizationId) }

    grant { user.canUpdateNotifications(organizationId) }
    requirements.updateNotifications(organizationId)
  }

  @Test
  fun updateSpecies() {
    assertThrows<SpeciesNotFoundException> { requirements.updateSpecies(speciesId) }

    grant { user.canReadSpecies(speciesId) }
    assertThrows<AccessDeniedException> { requirements.updateSpecies(speciesId) }

    grant { user.canUpdateSpecies(speciesId) }
    requirements.updateSpecies(speciesId)
  }

  @Test
  fun updateStorageLocation() {
    assertThrows<StorageLocationNotFoundException> {
      requirements.updateStorageLocation(storageLocationId)
    }

    grant { user.canReadStorageLocation(storageLocationId) }
    assertThrows<AccessDeniedException> { requirements.updateStorageLocation(storageLocationId) }

    grant { user.canUpdateStorageLocation(storageLocationId) }
    requirements.updateStorageLocation(storageLocationId)
  }

  @Test
  fun updateUpload() {
    assertThrows<UploadNotFoundException> { requirements.updateUpload(uploadId) }

    grant { user.canReadUpload(uploadId) }
    assertThrows<AccessDeniedException> { requirements.updateUpload(uploadId) }

    grant { user.canUpdateUpload(uploadId) }
    requirements.updateUpload(uploadId)
  }

  // When adding new permission tests, put them in alphabetical order.
}
