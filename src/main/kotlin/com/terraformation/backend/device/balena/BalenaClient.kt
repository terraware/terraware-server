package com.terraformation.backend.device.balena

import com.terraformation.backend.db.BalenaDeviceId
import com.terraformation.backend.db.FacilityId
import java.time.Instant

/** High-level interface for the Balena API. */
interface BalenaClient {
  /** Sets the facility ID and offline refresh token environment variables on a Balena device. */
  fun configureDeviceManager(balenaId: BalenaDeviceId, facilityId: FacilityId, token: String)

  /**
   * Returns the value of the sensor kit ID tag for a particular Balena device. If the tag is not
   * present or the device does not exist, returns null.
   */
  fun getSensorKitIdForBalenaId(balenaId: BalenaDeviceId): String?

  /**
   * Returns a list of the Balena devices that have been modified after a particular time. This is
   * typically used to scan for newly-provisioned devices.
   */
  fun listModifiedDevices(after: Instant): List<BalenaDevice>
}
