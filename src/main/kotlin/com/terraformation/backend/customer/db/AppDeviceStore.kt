package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.AppDeviceModel
import com.terraformation.backend.db.AppDeviceId
import com.terraformation.backend.db.tables.references.ACCESSIONS
import com.terraformation.backend.db.tables.references.APP_DEVICES
import com.terraformation.backend.util.eqOrIsNull
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL

@ManagedBean
class AppDeviceStore(private val dslContext: DSLContext, private val clock: Clock) {
  /**
   * Returns an app device ID for a given set of details about a device. If there is already a
   * device with those values, returns its existing ID, otherwise inserts a new one.
   */
  fun getOrInsertDevice(appDevice: AppDeviceModel): AppDeviceId {
    with(APP_DEVICES) {
      val existingId =
          dslContext
              .select(ID)
              .from(APP_DEVICES)
              .where(APP_BUILD.eqOrIsNull(appDevice.appBuild))
              .and(APP_NAME.eqOrIsNull(appDevice.appName))
              .and(BRAND.eqOrIsNull(appDevice.brand))
              .and(MODEL.eqOrIsNull(appDevice.model))
              .and(NAME.eqOrIsNull(appDevice.name))
              .and(OS_TYPE.eqOrIsNull(appDevice.osType))
              .and(OS_VERSION.eqOrIsNull(appDevice.osVersion))
              .and(UNIQUE_ID.eqOrIsNull(appDevice.uniqueId))
              .fetchOne(ID)

      if (existingId != null) {
        return existingId
      }

      return dslContext
          .insertInto(APP_DEVICES)
          .set(APP_BUILD, appDevice.appBuild)
          .set(APP_NAME, appDevice.appName)
          .set(BRAND, appDevice.brand)
          .set(CREATED_TIME, clock.instant())
          .set(MODEL, appDevice.model)
          .set(NAME, appDevice.name)
          .set(OS_TYPE, appDevice.osType)
          .set(OS_VERSION, appDevice.osVersion)
          .set(UNIQUE_ID, appDevice.uniqueId)
          .onConflictDoNothing()
          .returning(ID)
          .fetchOne()
          ?.id
          ?: throw DataAccessException("Unable to insert new device")
    }
  }

  /**
   * Returns the device information for a given ID. If the ID is null or doesn't exist, returns
   * null.
   */
  fun fetchById(id: AppDeviceId?): AppDeviceModel? {
    return if (id != null) {
      dslContext.selectFrom(APP_DEVICES).where(APP_DEVICES.ID.eq(id)).fetchOne {
        AppDeviceModel(it)
      }
    } else {
      null
    }
  }

  fun appDeviceMultiset(
      idField: Field<AppDeviceId?> = ACCESSIONS.APP_DEVICE_ID
  ): Field<AppDeviceModel?> {
    return DSL.multiset(DSL.selectFrom(APP_DEVICES).where(APP_DEVICES.ID.eq(idField)))
        .convertFrom { result -> result.firstOrNull()?.let { AppDeviceModel(it) } }
  }
}
