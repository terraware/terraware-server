package com.terraformation.backend.device.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.DeviceManagerNotFoundException
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.daos.DeviceManagersDao
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.default_schema.tables.references.DEVICE_MANAGERS
import jakarta.inject.Named
import java.time.Clock
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class DeviceManagerStore(
    private val clock: Clock,
    private val deviceManagersDao: DeviceManagersDao,
    private val dslContext: DSLContext,
) {
  fun fetchOneByBalenaId(balenaId: BalenaDeviceId): DeviceManagersRow? {
    return deviceManagersDao.fetchOneByBalenaId(balenaId)?.unlessInaccessible()
  }

  fun fetchOneByFacilityId(facilityId: FacilityId): DeviceManagersRow? {
    return deviceManagersDao.fetchOneByFacilityId(facilityId)?.unlessInaccessible()
  }

  fun fetchOneById(id: DeviceManagerId): DeviceManagersRow {
    return deviceManagersDao.fetchOneById(id)?.unlessInaccessible()
        ?: throw DeviceManagerNotFoundException(id)
  }

  /** Returns all device managers the current user has permission to see. */
  fun findAll(): List<DeviceManagersRow> {
    val facilityIds = currentUser().facilityRoles.keys.ifEmpty { null }
    val condition =
        DSL.or(
            listOfNotNull(
                DEVICE_MANAGERS.FACILITY_ID.isNull,
                facilityIds?.let { DEVICE_MANAGERS.FACILITY_ID.`in`(it) },
            )
        )

    return dslContext
        .selectFrom(DEVICE_MANAGERS)
        .where(condition)
        .fetchInto(DeviceManagersRow::class.java)
  }

  /**
   * Locks the database row for a device manager and returns its information. This should be called
   * inside a transaction (otherwise the lock will be immediately released).
   */
  fun getLockedById(id: DeviceManagerId): DeviceManagersRow {
    requirePermissions { updateDeviceManager(id) }

    return dslContext
        .selectFrom(DEVICE_MANAGERS)
        .where(DEVICE_MANAGERS.ID.eq(id))
        .forUpdate()
        .fetchOneInto(DeviceManagersRow::class.java) ?: throw DeviceManagerNotFoundException(id)
  }

  fun insert(row: DeviceManagersRow) {
    if (row.id != null) {
      throw IllegalArgumentException("ID must be null")
    }

    requirePermissions {
      createDeviceManager()
      row.facilityId?.let { updateFacility(it) }
    }

    row.createdTime = clock.instant()

    deviceManagersDao.insert(row)
  }

  fun update(row: DeviceManagersRow) {
    val id = row.id ?: throw IllegalArgumentException("ID must be non-null")

    requirePermissions {
      updateDeviceManager(id)
      row.facilityId?.let { updateFacility(it) }
    }

    deviceManagersDao.update(row)
  }

  private fun DeviceManagersRow.unlessInaccessible(): DeviceManagersRow? =
      id?.let { if (currentUser().canReadDeviceManager(it)) this else null }
}
