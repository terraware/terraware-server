package com.terraformation.backend.customer.model

import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.security.core.GrantedAuthority

/**
 * Details about the user who is making the current request and the permissions they have. This
 * always represents a device manager client.
 *
 * Device managers have a limited set of permissions; they can only do things related to managing
 * and monitoring devices.
 *
 * @see IndividualUser for a discussion of how to use this class; for the most part this acts the
 *   same, but with a more restricted set of permissions.
 */
data class DeviceManagerUser(
    override val userId: UserId,
    override val authId: String,
    private val parentStore: ParentStore,
    private val permissionStore: PermissionStore,
) : TerrawareUser {
  companion object {
    private const val EMAIL = "deviceManagerUser@terraformation.com"
  }

  override val description: String
    get() = "device manager user $userId"

  override val timeZone: ZoneId
    get() = ZoneOffset.UTC

  override val userType: UserType
    get() = UserType.DeviceManager

  override val email
    get() = EMAIL

  override val organizationRoles: Map<OrganizationId, Role> by lazy {
    mapOf(organizationId to Role.Contributor)
  }

  override val facilityRoles: Map<FacilityId, Role> by lazy {
    mapOf(facilityId to Role.Contributor)
  }

  override val defaultPermission: Boolean
    get() = false

  override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
    return mutableSetOf()
  }

  private val deviceManagerId: DeviceManagerId by lazy {
    parentStore.getDeviceManagerId(userId)
        ?: throw IllegalStateException("No device manager ID found for user $userId")
  }
  private val organizationId: OrganizationId by lazy {
    parentStore.getOrganizationId(deviceManagerId)
        ?: throw IllegalStateException("No organization found for device manager $deviceManagerId")
  }
  private val facilityId: FacilityId by lazy {
    parentStore.getFacilityId(deviceManagerId)
        ?: throw IllegalStateException("No facility found for device manager $deviceManagerId")
  }

  // Nearly all the permission checks that might return true boil down to, "Is this device manager
  // connected to a facility that is associated with the target object?"
  private fun canAccessFacility(facilityId: FacilityId?) = facilityId == this.facilityId

  /*
   * All permission checks always fail (thanks to defaultPermission) except for operations that a
   * device manager actually needs to perform to report timeseries data.
   */

  private fun canAccessDeviceManager(deviceManagerId: DeviceManagerId?) =
      deviceManagerId == this.deviceManagerId

  private fun canAccessDevice(deviceId: DeviceId) =
      canAccessFacility(parentStore.getFacilityId(deviceId))

  private fun canAccessOrganization(organizationId: OrganizationId?) =
      organizationId == this.organizationId

  private fun canAccessAutomation(automationId: AutomationId) =
      canAccessFacility(parentStore.getFacilityId(automationId))

  override fun canCreateAutomation(facilityId: FacilityId): Boolean = canAccessFacility(facilityId)

  override fun canCreateDevice(facilityId: FacilityId): Boolean = canAccessFacility(facilityId)

  override fun canCreateTimeseries(deviceId: DeviceId): Boolean = canAccessDevice(deviceId)

  override fun canDeleteAutomation(automationId: AutomationId): Boolean =
      canAccessAutomation(automationId)

  override fun canListAutomations(facilityId: FacilityId): Boolean = canAccessFacility(facilityId)

  override fun canListFacilities(organizationId: OrganizationId): Boolean =
      canAccessOrganization(organizationId)

  override fun canReadAutomation(automationId: AutomationId): Boolean =
      canAccessAutomation(automationId)

  override fun canReadDevice(deviceId: DeviceId): Boolean = canAccessDevice(deviceId)

  override fun canReadDeviceManager(deviceManagerId: DeviceManagerId): Boolean =
      canAccessDeviceManager(deviceManagerId)

  override fun canReadFacility(facilityId: FacilityId): Boolean = canAccessFacility(facilityId)

  override fun canReadOrganization(organizationId: OrganizationId): Boolean =
      canAccessOrganization(organizationId)

  override fun canReadTimeseries(deviceId: DeviceId): Boolean = canAccessDevice(deviceId)

  override fun canSendAlert(facilityId: FacilityId): Boolean = canAccessFacility(facilityId)

  override fun canTriggerAutomation(automationId: AutomationId): Boolean =
      canAccessAutomation(automationId)

  override fun canUpdateAutomation(automationId: AutomationId): Boolean =
      canAccessAutomation(automationId)

  override fun canUpdateDevice(deviceId: DeviceId): Boolean = canAccessDevice(deviceId)

  override fun canUpdateTimeseries(deviceId: DeviceId): Boolean = canAccessDevice(deviceId)
}
