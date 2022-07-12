package com.terraformation.backend.device

import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.FacilityConnectionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.device.balena.BalenaClient
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class DeviceManagerService(
    private val balenaClient: BalenaClient,
    private val deviceManagerStore: DeviceManagerStore,
    private val deviceService: DeviceService,
    private val dslContext: DSLContext,
    private val facilityStore: FacilityStore,
    private val parentStore: ParentStore,
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
      val token = userStore.generateOfflineToken(userId)

      balenaClient.configureDeviceManager(balenaId, facilityId, token)

      manager.userId = userId
      manager.facilityId = facilityId
      deviceManagerStore.update(manager)

      log.info(
          "Connected device manager $deviceManagerId to facility $facilityId with user $userId")
    }
  }
}
