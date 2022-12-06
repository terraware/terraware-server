package com.terraformation.backend.customer.model

import com.terraformation.backend.RunsAsUser
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
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.nursery.db.BatchNotFoundException
import com.terraformation.backend.nursery.db.WithdrawalNotFoundException
import com.terraformation.backend.tracking.db.DeliveryNotFoundException
import com.terraformation.backend.tracking.db.PlantingNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import kotlin.reflect.KClass
import kotlin.reflect.typeOf
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

/**
 * Tests the exception-throwing logic in [PermissionRequirements].
 *
 * For a read permission on a single object ID, where there should be an [EntityNotFoundException]
 * if the user doesn't have the permission, you can call [testRead] and pass in an ID that is
 * created by the [readableId] method.
 *
 * For a write permission on a single object ID, where there should be an [EntityNotFoundException]
 * if the user doesn't have permission to read the object and an [AccessDeniedException] if they
 * don't have write permission, the test will look like
 * ```
 * allow { methodUnderTest(objectId) } ifUser { canDoWhatever(objectId) }
 * ```
 *
 * The general approach to these tests, which is implemented by [testRead] and [ifUser] for simple
 * scenarios and explicitly in test methods for more complex scenarios, is
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

  /**
   * List of callback functions that will test that an exception is thrown if the user doesn't have
   * read permission on an object, then will grant permission. This is populated implicitly when a
   * test method accesses one of the IDs that's lazy-created by [readableId].
   */
  private val readChecks = mutableListOf<(() -> Unit) -> Unit>()

  // Lazy-instantiated object IDs with implicit testing of read permission handling. When you access
  // one of these IDs for the first time, an entry is added to [readChecks].

  private val accessionId: AccessionId by
      readableId(AccessionNotFoundException::class) { canReadAccession(it) }
  private val automationId: AutomationId by
      readableId(AutomationNotFoundException::class) { canReadAutomation(it) }
  private val batchId: BatchId by readableId(BatchNotFoundException::class) { canReadBatch(it) }
  private val deliveryId: DeliveryId by
      readableId(DeliveryNotFoundException::class) { canReadDelivery(it) }
  private val deviceId: DeviceId by readableId(DeviceNotFoundException::class) { canReadDevice(it) }
  private val deviceManagerId: DeviceManagerId by
      readableId(DeviceManagerNotFoundException::class) { canReadDeviceManager(it) }
  private val facilityId: FacilityId by
      readableId(FacilityNotFoundException::class) { canReadFacility(it) }
  private val notificationUserId = UserId(2)
  private val notificationId: NotificationId by
      readableId(NotificationNotFoundException::class) { canReadNotification(it) }
  private val organizationId: OrganizationId by
      readableId(OrganizationNotFoundException::class) { canReadOrganization(it) }
  private val plantingId: PlantingId by
      readableId(PlantingNotFoundException::class) { canReadPlanting(it) }
  private val plantingSiteId: PlantingSiteId by
      readableId(PlantingSiteNotFoundException::class) { canReadPlantingSite(it) }
  private val role = Role.CONTRIBUTOR
  private val speciesId: SpeciesId by
      readableId(SpeciesNotFoundException::class) { canReadSpecies(it) }
  private val storageLocationId: StorageLocationId by
      readableId(StorageLocationNotFoundException::class) { canReadStorageLocation(it) }
  private val uploadId: UploadId by readableId(UploadNotFoundException::class) { canReadUpload(it) }
  private val userId = UserId(1)
  private val viabilityTestId: ViabilityTestId by
      readableId(ViabilityTestNotFoundException::class) { canReadViabilityTest(it) }
  private val withdrawalId: WithdrawalId by
      readableId(WithdrawalNotFoundException::class) { canReadWithdrawal(it) }

  /**
   * Grants permission to perform a particular operation. This is a simple wrapper around a MockK
   * `every { user.canX() } returns true` call, but with a more concise syntax and a more meaningful
   * name.
   */
  private fun grant(stubBlock: MockKMatcherScope.() -> Boolean) {
    every(stubBlock) returns true
  }

  /**
   * Lazy initialization wrapper that returns an ID. Adds a function to [readChecks] that verifies
   * that an exception of the correct class is thrown by the method under test if the user doesn't
   * have permission to read the ID, then grants read permission.
   */
  private inline fun <reified T> readableId(
      exceptionClass: KClass<out Exception>,
      crossinline grantBlock: TerrawareUser.(T) -> Boolean
  ): Lazy<T> = lazy {
    val id =
        T::class
            .constructors
            .first { it.parameters.size == 1 && it.parameters[0].type == typeOf<Long>() }
            .call(1L)
    readChecks.add { operation ->
      Assertions.assertThrows(exceptionClass.java, operation)
      grant { grantBlock.invoke(user, id) }
    }
    id
  }

  private infix fun (() -> Unit).ifUser(grantBlock: TerrawareUser.() -> Boolean) {
    // Evaluate any lazy arguments, which will have the side effect of adding read checks; we
    // don't care about the specific exception here since the read checks will test for them.
    assertThrows<Exception>(this)

    readChecks.forEach { check -> check(this) }

    assertThrows<AccessDeniedException>(this)

    grant { grantBlock.invoke(user) }
    assertDoesNotThrow(this)
  }

  /**
   * Returns a function that invokes the supplied lambda on the [requirements] object. This should
   * almost always be followed by [ifUser], which will perform the actual testing.
   *
   * This is syntactic sugar for readability and brevity; the following two lines are equivalent:
   * ```
   * allow { foo() }
   * { requirements.foo() }
   * ```
   */
  private fun allow(func: PermissionRequirements.() -> Unit): () -> Unit {
    return { func.invoke(requirements) }
  }

  /**
   * Calls the supplied lambda on the [requirements] object, including any read checks that are
   * required for any IDs that are referenced in the lambda.
   */
  private fun testRead(func: PermissionRequirements.() -> Unit) {
    val funcWithReceiver = { func.invoke(requirements) }

    // Evaluate any lazy arguments, which will have the side effect of adding read checks; we
    // don't care about the specific exception here since the read checks will test for them.
    assertThrows<Exception>(funcWithReceiver)

    readChecks.forEach { check -> check(funcWithReceiver) }

    assertDoesNotThrow(funcWithReceiver)
  }

  @Test
  fun addOrganizationUser() =
      allow { addOrganizationUser(organizationId) } ifUser
          {
            canAddOrganizationUser(organizationId)
          }

  @Test
  fun createAccession() =
      allow { createAccession(facilityId) } ifUser { canCreateAccession(facilityId) }

  @Test
  fun createApiKey() =
      allow { createApiKey(organizationId) } ifUser { canCreateApiKey(organizationId) }

  @Test
  fun createAutomation() =
      allow { createAutomation(facilityId) } ifUser { canCreateAutomation(facilityId) }

  @Test fun createBatch() = allow { createBatch(facilityId) } ifUser { canCreateBatch(facilityId) }

  @Test
  fun createDelivery() =
      allow { createDelivery(plantingSiteId) } ifUser { canCreateDelivery(plantingSiteId) }

  @Test
  fun createDevice() = allow { createDevice(facilityId) } ifUser { canCreateDevice(facilityId) }

  @Test
  fun createDeviceManager() = allow { createDeviceManager() } ifUser { canCreateDeviceManager() }

  @Test
  fun createFacility() =
      allow { createFacility(organizationId) } ifUser { canCreateFacility(organizationId) }

  @Test
  fun createNotification() =
      allow { createNotification(notificationUserId, organizationId) } ifUser
          {
            canCreateNotification(notificationUserId, organizationId)
          }

  @Test
  fun createPlantingSite() =
      allow { createPlantingSite(organizationId) } ifUser { canCreatePlantingSite(organizationId) }

  @Test
  fun createSpecies() =
      allow { createSpecies(organizationId) } ifUser { canCreateSpecies(organizationId) }

  @Test
  fun createStorageLocation() =
      allow { createStorageLocation(facilityId) } ifUser { canCreateStorageLocation(facilityId) }

  @Test
  fun createTimeseries() =
      allow { createTimeseries(deviceId) } ifUser { canCreateTimeseries(deviceId) }

  @Test
  fun createWithdrawalPhoto() =
      allow { createWithdrawalPhoto(withdrawalId) } ifUser
          {
            canCreateWithdrawalPhoto(withdrawalId)
          }

  @Test
  fun deleteAccession() =
      allow { deleteAccession(accessionId) } ifUser { canDeleteAccession(accessionId) }

  @Test
  fun deleteAutomation() =
      allow { deleteAutomation(automationId) } ifUser { canDeleteAutomation(automationId) }

  @Test fun deleteBatch() = allow { deleteBatch(batchId) } ifUser { canDeleteBatch(batchId) }

  @Test
  fun deleteOrganization() =
      allow { deleteOrganization(organizationId) } ifUser { canDeleteOrganization(organizationId) }

  @Test fun deleteSelf() = allow { deleteSelf() } ifUser { canDeleteSelf() }

  @Test
  fun deleteSpecies() = allow { deleteSpecies(speciesId) } ifUser { canDeleteSpecies(speciesId) }

  @Test
  fun deleteStorageLocation() =
      allow { deleteStorageLocation(storageLocationId) } ifUser
          {
            canDeleteStorageLocation(storageLocationId)
          }

  @Test fun deleteUpload() = allow { deleteUpload(uploadId) } ifUser { canDeleteUpload(uploadId) }

  @Test
  fun importGlobalSpeciesData() =
      allow { importGlobalSpeciesData() } ifUser { canImportGlobalSpeciesData() }

  @Test
  fun listAutomations() =
      allow { listAutomations(facilityId) } ifUser { canListAutomations(facilityId) }

  @Test
  fun listGlobalNotifications() =
      allow { listNotifications(null) } ifUser { canListNotifications(null) }

  @Test
  fun listOrganizationNotifications() =
      allow { listNotifications(organizationId) } ifUser { canListNotifications(organizationId) }

  @Test
  fun listOrganizationUsers() =
      allow { listOrganizationUsers(organizationId) } ifUser
          {
            canListOrganizationUsers(organizationId)
          }

  @Test
  fun movePlantingSite() {
    assertThrows<PlantingSiteNotFoundException> {
      requirements.movePlantingSiteToAnyOrg(plantingSiteId)
    }

    grant { user.canReadPlantingSite(plantingSiteId) }
    assertThrows<AccessDeniedException> { requirements.movePlantingSiteToAnyOrg(plantingSiteId) }

    grant { user.canUpdatePlantingSite(plantingSiteId) }
    assertThrows<AccessDeniedException> { requirements.movePlantingSiteToAnyOrg(plantingSiteId) }

    grant { user.canMovePlantingSiteToAnyOrg(plantingSiteId) }
    requirements.movePlantingSiteToAnyOrg(plantingSiteId)
  }

  @Test fun readAccession() = testRead { readAccession(accessionId) }

  @Test fun readAutomation() = testRead { readAutomation(automationId) }

  @Test fun readBatch() = testRead { readBatch(batchId) }

  @Test fun readDelivery() = testRead { readDelivery(deliveryId) }

  @Test fun readDevice() = testRead { readDevice(deviceId) }

  @Test fun readDeviceManager() = testRead { readDeviceManager(deviceManagerId) }

  @Test fun readFacility() = testRead { readFacility(facilityId) }

  @Test fun readNotification() = testRead { readNotification(notificationId) }

  @Test fun readOrganization() = testRead { readOrganization(organizationId) }

  @Test
  fun readOrganizationUser() {
    assertThrows<OrganizationNotFoundException> {
      requirements.readOrganizationUser(organizationId, userId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<UserNotFoundException> {
      requirements.readOrganizationUser(organizationId, userId)
    }

    grant { user.canReadOrganizationUser(organizationId, userId) }
    requirements.readOrganizationUser(organizationId, userId)
  }

  @Test fun readPlanting() = testRead { readPlanting(plantingId) }

  @Test fun readPlantingSite() = testRead { readPlantingSite(plantingSiteId) }

  @Test fun readSpecies() = testRead { readSpecies(speciesId) }

  @Test fun readStorageLocation() = testRead { readStorageLocation(storageLocationId) }

  @Test fun readUpload() = testRead { readUpload(uploadId) }

  @Test fun readViabilityTest() = testRead { readViabilityTest(viabilityTestId) }

  @Test fun readWithdrawal() = testRead { readWithdrawal(withdrawalId) }

  @Test
  fun regenerateAllDeviceManagerTokens() =
      allow { regenerateAllDeviceManagerTokens() } ifUser { canRegenerateAllDeviceManagerTokens() }

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

  @Test fun sendAlert() = allow { sendAlert(facilityId) } ifUser { canSendAlert(facilityId) }

  @Test
  fun setOrganizationUserRole() {
    allow { setOrganizationUserRole(organizationId, role) } ifUser
        {
          canSetOrganizationUserRole(organizationId, role)
        }
  }

  @Test fun setTestClock() = allow { setTestClock() } ifUser { canSetTestClock() }

  @Test
  fun setWithdrawalUser() =
      allow { setWithdrawalUser(accessionId) } ifUser { canSetWithdrawalUser(accessionId) }

  @Test
  fun triggerAutomation() =
      allow { triggerAutomation(automationId) } ifUser { canTriggerAutomation(automationId) }

  @Test
  fun updateAccession() =
      allow { updateAccession(accessionId) } ifUser { canUpdateAccession(accessionId) }

  @Test fun updateAppVersions() = allow { updateAppVersions() } ifUser { canUpdateAppVersions() }

  @Test
  fun updateAutomation() =
      allow { updateAutomation(automationId) } ifUser { canUpdateAutomation(automationId) }

  @Test fun updateBatch() = allow { updateBatch(batchId) } ifUser { canUpdateBatch(batchId) }

  @Test
  fun updateDelivery() =
      allow { updateDelivery(deliveryId) } ifUser { canUpdateDelivery(deliveryId) }

  @Test fun updateDevice() = allow { updateDevice(deviceId) } ifUser { canUpdateDevice(deviceId) }

  @Test
  fun updateDeviceManager() =
      allow { updateDeviceManager(deviceManagerId) } ifUser
          {
            canUpdateDeviceManager(deviceManagerId)
          }

  @Test
  fun updateDeviceTemplates() =
      allow { updateDeviceTemplates() } ifUser { canUpdateDeviceTemplates() }

  @Test
  fun updateFacility() =
      allow { updateFacility(facilityId) } ifUser { canUpdateFacility(facilityId) }

  @Test
  fun updateGlobalNotifications() =
      allow { updateNotifications(null) } ifUser { canUpdateNotifications(null) }

  @Test
  fun updateNotification() =
      allow { updateNotification(notificationId) } ifUser { canUpdateNotification(notificationId) }

  @Test
  fun updateOrganization() =
      allow { updateOrganization(organizationId) } ifUser { canUpdateOrganization(organizationId) }

  @Test
  fun updateOrganizationNotifications() =
      allow { updateNotifications(organizationId) } ifUser
          {
            canUpdateNotifications(organizationId)
          }

  @Test
  fun updatePlantingSite() =
      allow { updatePlantingSite(plantingSiteId) } ifUser { canUpdatePlantingSite(plantingSiteId) }

  @Test
  fun updateSpecies() = allow { updateSpecies(speciesId) } ifUser { canUpdateSpecies(speciesId) }

  @Test
  fun updateStorageLocation() =
      allow { updateStorageLocation(storageLocationId) } ifUser
          {
            canUpdateStorageLocation(storageLocationId)
          }

  @Test fun updateUpload() = allow { updateUpload(uploadId) } ifUser { canUpdateUpload(uploadId) }

  // When adding new permission tests, put them in alphabetical order.
}
