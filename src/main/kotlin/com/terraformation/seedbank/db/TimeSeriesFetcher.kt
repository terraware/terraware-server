package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.TIMESERIES
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class TimeSeriesFetcher(
    private val dslContext: DSLContext,
    private val deviceFetcher: DeviceFetcher
) {
  fun getIdByMqttTopic(topic: String, name: String): Long? {
    val deviceIdSubquery = deviceFetcher.queryDeviceIdForMqttTopic(topic) ?: return null

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
}
