package com.terraformation.backend.device

import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.DEVICE_MANAGERS
import com.terraformation.backend.device.balena.BalenaClient
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class DeviceManagerService(
    private val balenaClient: BalenaClient,
    private val dslContext: DSLContext,
    private val systemUser: SystemUser,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

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
                  "Generating new token for device manager at facility $facilityId (user $userId)"
              )
              generateOfflineToken(userId, balenaId, facilityId, overwrite = true)
            }
          }
    }
  }
}
