package com.terraformation.backend.device.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.TimeseriesId
import com.terraformation.backend.db.TimeseriesNotFoundException
import com.terraformation.backend.db.TimeseriesType
import com.terraformation.backend.db.tables.pojos.TimeseriesRow
import com.terraformation.backend.db.tables.references.TIMESERIES
import com.terraformation.backend.db.tables.references.TIMESERIES_VALUES
import com.terraformation.backend.device.model.TimeseriesModel
import com.terraformation.backend.device.model.TimeseriesValueModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.impl.DSL

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

  fun fetchByDeviceId(deviceId: DeviceId): List<TimeseriesModel> {
    requirePermissions { readTimeseries(deviceId) }

    val valuesMultiset =
        DSL.multiset(
                DSL.selectFrom(TIMESERIES_VALUES)
                    .where(TIMESERIES_VALUES.TIMESERIES_ID.eq(TIMESERIES.ID))
                    .orderBy(TIMESERIES_VALUES.CREATED_TIME.desc())
                    .limit(1))
            .convertFrom { result ->
              result.firstOrNull()?.let { TimeseriesValueModel.ofRecord(it) }
            }

    return dslContext
        .select(TIMESERIES.asterisk(), valuesMultiset)
        .from(TIMESERIES)
        .where(TIMESERIES.DEVICE_ID.eq(deviceId))
        .orderBy(TIMESERIES.NAME)
        .fetch { TimeseriesModel(it, it[valuesMultiset]) }
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

    val (type, decimalPlaces) =
        dslContext
            .select(TIMESERIES.TYPE_ID, TIMESERIES.DECIMAL_PLACES)
            .from(TIMESERIES)
            .where(TIMESERIES.ID.eq(timeseriesId))
            .and(TIMESERIES.DEVICE_ID.eq(deviceId))
            .fetchOne()
            ?: throw TimeseriesNotFoundException(deviceId)

    val roundedValue =
        if (type == TimeseriesType.Numeric && decimalPlaces != null) {
          BigDecimal(value)
              .setScale(decimalPlaces, RoundingMode.HALF_UP)
              .stripTrailingZeros()
              .toPlainString()
        } else {
          value
        }

    with(TIMESERIES_VALUES) {
      dslContext
          .insertInto(TIMESERIES_VALUES)
          .set(TIMESERIES_ID, timeseriesId)
          .set(CREATED_TIME, createdTime)
          .set(VALUE, roundedValue)
          .execute()
    }
  }
}
