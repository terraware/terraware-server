package com.terraformation.backend.device.db

import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.TimeseriesId
import com.terraformation.backend.db.TimeseriesType
import com.terraformation.backend.db.tables.references.TIMESERIES
import com.terraformation.backend.db.tables.references.TIMESERIES_VALUES
import java.time.Clock
import java.time.Instant
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class TimeSeriesStore(
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val deviceStore: DeviceStore
) {
  fun getIdByMqttTopic(topic: String, name: String): TimeseriesId? {
    val deviceIdSubquery = deviceStore.queryDeviceIdForMqttTopic(topic) ?: return null

    val query =
        dslContext
            .select(TIMESERIES.ID)
            .from(TIMESERIES)
            .where(TIMESERIES.DEVICE_ID.eq(deviceIdSubquery))
            .and(TIMESERIES.NAME.eq(name))
    return query.fetchOne(TIMESERIES.ID)
  }

  fun getTimeseriesIdsByName(
      deviceId: DeviceId,
      names: Collection<String>
  ): Map<String, TimeseriesId> {
    with(TIMESERIES) {
      return dslContext
          .select(NAME, ID)
          .from(TIMESERIES)
          .where(DEVICE_ID.eq(deviceId))
          .and(NAME.`in`(names))
          .fetch()
          .associateBy({ it.value1()!! }) { it.value2()!! }
    }
  }

  fun getTimeseriesIdByName(deviceId: DeviceId, name: String): TimeseriesId? {
    with(TIMESERIES) {
      return dslContext
          .select(ID)
          .from(TIMESERIES)
          .where(DEVICE_ID.eq(deviceId))
          .and(NAME.eq(name))
          .fetchOne(ID)
    }
  }

  /**
   * Creates a new timeseries.
   *
   * @throws org.jooq.exception.DataAccessException
   */
  fun create(
      deviceId: DeviceId,
      name: String,
      type: TimeseriesType,
      units: String? = null,
      decimalPlaces: Int? = null
  ): TimeseriesId {
    return with(TIMESERIES) {
      dslContext
          .insertInto(TIMESERIES)
          .set(NAME, name)
          .set(DEVICE_ID, deviceId)
          .set(UNITS, units)
          .set(DECIMAL_PLACES, decimalPlaces)
          .set(TYPE_ID, type)
          .returning(ID)
          .fetchOne()
          ?.id!!
    }
  }

  fun insertValue(
      timeseriesId: TimeseriesId,
      value: String,
      createdTime: Instant = clock.instant()
  ) {
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
