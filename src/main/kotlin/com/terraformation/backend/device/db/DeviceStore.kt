package com.terraformation.backend.device.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.daos.DevicesDao
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import jakarta.inject.Named

/** Permission-aware database operations for device configuration data. */
@Named
class DeviceStore(private val devicesDao: DevicesDao) {
  fun fetchOneById(deviceId: DeviceId): DevicesRow {
    requirePermissions { readDevice(deviceId) }

    return devicesDao.fetchOneById(deviceId) ?: throw DeviceNotFoundException(deviceId)
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

    requirePermissions { createDevice(facilityId) }

    val parentId = devicesRow.parentId
    if (parentId != null) {
      requirePermissions { updateDevice(parentId) }
    }

    val newRow =
        devicesRow.copy(
            id = null,
            createdBy = currentUser().userId,
            modifiedBy = currentUser().userId,
        )
    devicesDao.insert(newRow)

    return newRow.id ?: throw IllegalStateException("Insert succeeded but no ID returned")
  }

  fun update(devicesRow: DevicesRow) {
    val deviceId = devicesRow.id ?: throw IllegalArgumentException("No device ID specified")

    requirePermissions { updateDevice(deviceId) }

    val parentId = devicesRow.parentId
    if (parentId != null) {
      requirePermissions { updateDevice(parentId) }
    }

    val existing = devicesDao.fetchOneById(deviceId) ?: throw DeviceNotFoundException(deviceId)

    // A null facility ID is fine here because we don't require clients to pass in facility IDs
    // when they update devices. But if they _do_ pass one in, it needs to be the same as the
    // existing one; they can't attempt to move a device to a different facility.
    if (devicesRow.facilityId != null && devicesRow.facilityId != existing.facilityId) {
      throw IllegalArgumentException(
          "Devices may not be moved between facilities; create a new device instead."
      )
    }

    // Devices can't be moved between facilities.
    devicesDao.update(
        devicesRow.copy(
            createdBy = existing.createdBy,
            facilityId = existing.facilityId,
            modifiedBy = currentUser().userId,
        )
    )
  }
}
