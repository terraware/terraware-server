package com.terraformation.backend.device

import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.DEVICE_MANAGERS
import com.terraformation.backend.device.balena.BalenaClient
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException

@Named
class DeviceManagerService(
    private val balenaClient: BalenaClient,
    private val deviceManagerStore: DeviceManagerStore,
    private val deviceService: DeviceService,
    private val dslContext: DSLContext,
    private val facilityStore: FacilityStore,
    private val parentStore: ParentStore,
    private val systemUser: SystemUser,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  fun connect(deviceManagerId: DeviceManagerId, facilityId: FacilityId) {
    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    dslContext.transaction { _ ->
      // Lock the device manager to prevent two users from claiming it at once.
      val manager = deviceManagerStore.getLockedById(deviceManagerId)
      val balenaId = manager.balenaId ?: throw IllegalStateException("Balena ID must be non-null")

      if (manager.facilityId != null) {
        log.error(
            "Device manager $deviceManagerId already connected to facility " +
                "${manager.facilityId}; rejecting attempt to connect to $facilityId")
        throw AccessDeniedException("Device manager already connected")
      }

      facilityStore.updateConnectionState(
          facilityId, FacilityConnectionState.NotConnected, FacilityConnectionState.Connected)

      deviceService.createDefaultDevices(facilityId)

      val newUser =
          userStore.createDeviceManagerUser(organizationId, "Balena ${manager.balenaUuid}")
      val userId = newUser.userId

      generateOfflineToken(userId, balenaId, facilityId)

      manager.userId = userId
      manager.facilityId = facilityId
      deviceManagerStore.update(manager)

      log.info(
          "Connected device manager $deviceManagerId to facility $facilityId with user $userId")
    }
  }

  fun generateOfflineToken(
      userId: UserId,
      balenaId: BalenaDeviceId,
      facilityId: FacilityId,
      overwrite: Boolean = false,
  ) {
    val token = userStore.generateOfflineToken(userId)

    balenaClient.configureDeviceManager(balenaId, facilityId, token, overwrite)
  }

  fun regenerateAllOfflineTokens() {
    requirePermissions { regenerateAllDeviceManagerTokens() }

    log.info("Regenerating offline tokens for all device managers")

    // This needs to access device managers across all organizations.
    systemUser.run {
      dslContext
          .select(DEVICE_MANAGERS.USER_ID, DEVICE_MANAGERS.BALENA_ID, DEVICE_MANAGERS.FACILITY_ID)
          .from(DEVICE_MANAGERS)
          .where(DEVICE_MANAGERS.USER_ID.isNotNull)
          .fetch()
          .forEach { (userId, balenaId, facilityId) ->
            if (userId != null && balenaId != null && facilityId != null) {
              log.debug(
                  "Generating new token for device manager at facility $facilityId (user $userId)")
              generateOfflineToken(userId, balenaId, facilityId, overwrite = true)
            }
          }
    }
  }
}
