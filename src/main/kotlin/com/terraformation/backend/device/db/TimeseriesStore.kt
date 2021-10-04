package com.terraformation.backend.device.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.TimeseriesId
import com.terraformation.backend.db.TimeseriesNotFoundException
import com.terraformation.backend.db.tables.pojos.TimeseriesRow
import com.terraformation.backend.db.tables.references.TIMESERIES
import com.terraformation.backend.db.tables.references.TIMESERIES_VALUES
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.security.access.AccessDeniedException

@ManagedBean
class TimeseriesStore(private val dslContext: DSLContext) {

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

    if (!currentUser().canCreateTimeseries(deviceId)) {
      throw AccessDeniedException("No permission to create timeseries")
    }

    return with(TIMESERIES) {
      dslContext
          .insertInto(TIMESERIES)
          .set(NAME, row.name)
          .set(DEVICE_ID, row.deviceId)
          .set(UNITS, row.units)
          .set(DECIMAL_PLACES, row.decimalPlaces)
          .set(TYPE_ID, row.typeId)
          .onConflict(DEVICE_ID, NAME)
          .doUpdate()
          .set(UNITS, row.units)
          .set(DECIMAL_PLACES, row.decimalPlaces)
          .set(TYPE_ID, row.typeId)
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

  fun insertValue(timeseriesId: TimeseriesId, value: String, createdTime: Instant) {
    val deviceId =
        dslContext
            .select(TIMESERIES.DEVICE_ID)
            .from(TIMESERIES)
            .where(TIMESERIES.ID.eq(timeseriesId))
            .fetchOne(TIMESERIES.DEVICE_ID)
            ?: throw TimeseriesNotFoundException(timeseriesId)

    if (!currentUser().canUpdateTimeseries(deviceId)) {
      if (currentUser().canReadTimeseries(deviceId)) {
        throw AccessDeniedException("No permission to update timeseries")
      } else {
        throw TimeseriesNotFoundException(timeseriesId)
      }
    }

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
