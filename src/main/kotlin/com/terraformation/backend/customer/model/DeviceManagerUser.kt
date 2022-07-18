package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.NotificationId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.UploadId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.log.perClassLogger
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Details about the user who is making the current request and the permissions they have. This
 * always represents a device manager client.
 *
 * Device managers have a limited set of permissions; they can only do things related to managing
 * and monitoring devices.
 *
 * @see IndividualUser for a discussion of how to use this class; for the most part this acts the
 * same, but with a more restricted set of permissions.
 */
data class DeviceManagerUser(
    override val userId: UserId,
    override val authId: String,
    private val parentStore: ParentStore,
    private val permissionStore: PermissionStore,
) : TerrawareUser, UserDetails {
  override val userType: UserType
    get() = UserType.DeviceManager

  override val organizationRoles: Map<OrganizationId, Role> by lazy {
    mapOf(organizationId to Role.CONTRIBUTOR)
  }

  override val facilityRoles: Map<FacilityId, Role> by lazy {
    mapOf(facilityId to Role.CONTRIBUTOR)
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

  // This one isn't a simple "is this the right organization" check because it depends on the
  // target user's organization membership too.
  override fun canCreateNotification(
      targetUserId: UserId,
      organizationId: OrganizationId
  ): Boolean {
    return canAccessOrganization(organizationId) &&
        organizationId in permissionStore.fetchOrganizationRoles(targetUserId)
  }

  override fun canAddOrganizationUser(organizationId: OrganizationId): Boolean = false
  override fun canCountNotifications(): Boolean = false
  override fun canCreateAccession(facilityId: FacilityId): Boolean = false
  override fun canCreateApiKey(organizationId: OrganizationId): Boolean = false
  override fun canCreateDeviceManager(): Boolean = false
  override fun canCreateFacility(organizationId: OrganizationId): Boolean = false
  override fun canCreateSpecies(organizationId: OrganizationId): Boolean = false
  override fun canCreateStorageLocation(facilityId: FacilityId): Boolean = false
  override fun canDeleteAccession(accessionId: AccessionId): Boolean = false
  override fun canDeleteOrganization(organizationId: OrganizationId): Boolean = false
  override fun canDeleteSpecies(speciesId: SpeciesId): Boolean = false
  override fun canDeleteStorageLocation(storageLocationId: StorageLocationId): Boolean = false
  override fun canDeleteUpload(uploadId: UploadId): Boolean = false
  override fun canImportGlobalSpeciesData(): Boolean = false
  override fun canListNotifications(organizationId: OrganizationId?): Boolean = false
  override fun canListOrganizationUsers(organizationId: OrganizationId): Boolean = false
  override fun canReadAccession(accessionId: AccessionId): Boolean = false
  override fun canReadNotification(notificationId: NotificationId): Boolean = false
  override fun canReadSpecies(speciesId: SpeciesId): Boolean = false
  override fun canReadStorageLocation(storageLocationId: StorageLocationId): Boolean = false
  override fun canReadUpload(uploadId: UploadId): Boolean = false
  override fun canRemoveOrganizationUser(organizationId: OrganizationId, userId: UserId): Boolean =
      false
  override fun canSetOrganizationUserRole(organizationId: OrganizationId, role: Role): Boolean =
      false
  override fun canSetTestClock(): Boolean = false
  override fun canUpdateAccession(accessionId: AccessionId): Boolean = false
  override fun canUpdateDeviceManager(deviceManagerId: DeviceManagerId): Boolean = false
  override fun canUpdateDeviceTemplates(): Boolean = false
  override fun canUpdateFacility(facilityId: FacilityId): Boolean = false
  override fun canUpdateNotification(notificationId: NotificationId): Boolean = false
  override fun canUpdateNotifications(organizationId: OrganizationId?): Boolean = false
  override fun canUpdateOrganization(organizationId: OrganizationId): Boolean = false
  override fun canUpdateSpecies(speciesId: SpeciesId): Boolean = false
  override fun canUpdateStorageLocation(storageLocationId: StorageLocationId): Boolean = false
  override fun canUpdateUpload(uploadId: UploadId): Boolean = false
  override fun hasAnyAdminRole(): Boolean = false

  override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
    return mutableSetOf()
  }

  override fun getPassword(): String {
    log.warn("Something is trying to get the password of a device manager user")
    return ""
  }

  override fun getName(): String = authId
  override fun getUsername(): String = authId
  override fun isAccountNonExpired(): Boolean = true
  override fun isAccountNonLocked(): Boolean = true
  override fun isCredentialsNonExpired(): Boolean = true
  override fun isEnabled(): Boolean = true

  override fun <T> run(func: () -> T): T = CurrentUserHolder.runAs(this, func, authorities)

  companion object {
    private val log = perClassLogger()
  }
}
