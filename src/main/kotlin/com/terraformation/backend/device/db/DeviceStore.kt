package com.terraformation.backend.device.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.pojos.DevicesRow
import javax.annotation.ManagedBean
import org.springframework.security.access.AccessDeniedException

/** Permission-aware database operations for device configuration data. */
@ManagedBean
class DeviceStore(private val devicesDao: DevicesDao) {
  fun fetchOneById(deviceId: DeviceId): DevicesRow? {
    return if (!currentUser().canReadDevice(deviceId)) {
      null
    } else {
      devicesDao.fetchOneById(deviceId)
    }
  }

  fun fetchByFacilityId(facilityId: FacilityId): List<DevicesRow> {
    if (!currentUser().canReadFacility(facilityId)) {
      return emptyList()
    }

    return devicesDao.fetchByFacilityId(facilityId)
  }

  fun create(devicesRow: DevicesRow): DeviceId {
    val facilityId =
        devicesRow.facilityId ?: throw IllegalArgumentException("No facility ID specified")

    if (!currentUser().canCreateDevice(facilityId)) {
      throw AccessDeniedException("No permission to create devices in facility")
    }

    val parentId = devicesRow.parentId
    if (parentId != null && !currentUser().canUpdateDevice(parentId)) {
      throw AccessDeniedException("No permission to update parent device")
    }

    val newRow = devicesRow.copy(id = null)
    devicesDao.insert(newRow)

    return newRow.id ?: throw IllegalStateException("Insert succeeded but no ID returned")
  }

  fun update(devicesRow: DevicesRow) {
    val deviceId = devicesRow.id ?: throw IllegalArgumentException("No device ID specified")

    if (!currentUser().canUpdateDevice(deviceId)) {
      throw AccessDeniedException("No permission to update device")
    }

    val parentId = devicesRow.parentId
    if (parentId != null && !currentUser().canUpdateDevice(parentId)) {
      throw AccessDeniedException("No permission to update parent device")
    }

    val existing = devicesDao.fetchOneById(deviceId) ?: throw DeviceNotFoundException(deviceId)

    // A null facility ID is fine here because we don't require clients to pass in facility IDs
    // when they update devices. But if they _do_ pass one in, it needs to be the same as the
    // existing one; they can't attempt to move a device to a different facility.
    if (devicesRow.facilityId != null && devicesRow.facilityId != existing.facilityId) {
      throw IllegalArgumentException(
          "Devices may not be moved between facilities; create a new device instead.")
    }

    // Devices can't be moved between facilities.
    devicesDao.update(devicesRow.copy(facilityId = existing.facilityId))
  }
}
