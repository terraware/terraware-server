package com.terraformation.backend.device.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.pojos.DevicesRow
import javax.annotation.ManagedBean

@ManagedBean
class DeviceStore(private val devicesDao: DevicesDao) {
  fun fetchByFacilityId(facilityId: FacilityId): List<DevicesRow> {
    if (!currentUser().canReadFacility(facilityId)) {
      return emptyList()
    }

    return devicesDao.fetchByFacilityId(facilityId)
  }
}
