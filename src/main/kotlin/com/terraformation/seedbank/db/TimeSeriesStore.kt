package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.TIMESERIES
import com.terraformation.seedbank.db.tables.references.TIMESERIES_VALUE
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
  fun getIdByMqttTopic(topic: String, name: String): Long? {
    val deviceIdSubquery = deviceStore.queryDeviceIdForMqttTopic(topic) ?: return null

    val query =
        dslContext
            .select(TIMESERIES.ID)
            .from(TIMESERIES)
            .where(TIMESERIES.DEVICE_ID.eq(deviceIdSubquery))
            .and(TIMESERIES.NAME.eq(name))
    return query.fetchOne(TIMESERIES.ID)
  }

  fun getTimeseriesIdsByName(deviceId: Long, names: Collection<String>): Map<String, Long> {
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

  fun getTimeseriesIdByName(deviceId: Long, name: String): Long? {
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
      deviceId: Long,
      name: String,
      type: TimeseriesType,
      units: String? = null,
      decimalPlaces: Int? = null
  ): Long {
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

  fun insertValue(timeseriesId: Long, value: String, createdTime: Instant = clock.instant()) {
    with(TIMESERIES_VALUE) {
      dslContext
          .insertInto(TIMESERIES_VALUE)
          .set(TIMESERIES_ID, timeseriesId)
          .set(CREATED_TIME, createdTime)
          .set(VALUE, value)
          .execute()
    }
  }
}
