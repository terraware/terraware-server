package com.terraformation.backend.device.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.TimeseriesId
import com.terraformation.backend.db.tables.pojos.TimeseriesRow
import com.terraformation.backend.db.tables.references.TIMESERIES
import com.terraformation.backend.db.tables.references.TIMESERIES_VALUES
import java.time.Clock
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class TimeseriesStore(private val clock: Clock, private val dslContext: DSLContext) {

  fun fetchOneByName(deviceId: DeviceId, name: String): TimeseriesRow? {
    if (!currentUser().canReadTimeseries(deviceId)) {
      return null
    }

    return dslContext
        .selectFrom(TIMESERIES)
        .where(TIMESERIES.DEVICE_ID.eq(deviceId))
        .and(TIMESERIES.NAME.eq(name))
        .fetchOneInto(TimeseriesRow::class.java)
  }

  /**
   * Creates a new timeseries or updates an existing one.
   *
   * @throws org.jooq.exception.DataAccessException
   */
  private fun createOrUpdate(row: TimeseriesRow): TimeseriesId {
    val deviceId = row.deviceId ?: throw IllegalArgumentException("No device ID specified")

    requirePermissions { createTimeseries(deviceId) }

    val userId = currentUser().userId

    return with(TIMESERIES) {
      dslContext
          .insertInto(TIMESERIES)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, clock.instant())
          .set(DECIMAL_PLACES, row.decimalPlaces)
          .set(DEVICE_ID, row.deviceId)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(NAME, row.name)
          .set(TYPE_ID, row.typeId)
          .set(UNITS, row.units)
          .onConflict(DEVICE_ID, NAME)
          .doUpdate()
          .set(DECIMAL_PLACES, row.decimalPlaces)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, clock.instant())
          .set(TYPE_ID, row.typeId)
          .set(UNITS, row.units)
          .returning(ID)
          .fetchOne()
          ?.id!!
    }
  }

  /**
   * Creates or updates a list of timeseries. This is all-or-nothing: if any of the changes fail,
   * none of them are applied.
   *
   * @return List of timeseries IDs in the same order as the entries in [rows].
   */
  fun createOrUpdate(rows: List<TimeseriesRow>): List<TimeseriesId> {
    return dslContext.transactionResult { _ -> rows.map { createOrUpdate(it) } }
  }

  fun insertValue(
      deviceId: DeviceId,
      timeseriesId: TimeseriesId,
      value: String,
      createdTime: Instant
  ) {
    requirePermissions { updateTimeseries(deviceId) }

    with(TIMESERIES_VALUES) {
      dslContext
          .insertInto(TIMESERIES_VALUES)
          .set(TIMESERIES_ID, timeseriesId)
          .set(CREATED_TIME, createdTime)
          .set(VALUE, value)
          .execute()
    }
  }
}
