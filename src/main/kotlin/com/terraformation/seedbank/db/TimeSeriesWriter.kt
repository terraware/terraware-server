package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.TIMESERIES
import com.terraformation.seedbank.db.tables.references.TIMESERIES_VALUE
import com.terraformation.seedbank.mqtt.IncomingTimeseriesUpdateMessage
import com.terraformation.seedbank.services.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.context.event.EventListener

@ManagedBean
class TimeSeriesWriter(
    private val dslContext: DSLContext,
    private val timeSeriesFetcher: TimeSeriesFetcher,
    private val deviceFetcher: DeviceFetcher
) {
  private val log = perClassLogger()

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
  ) {
    with(TIMESERIES) {
      dslContext
          .insertInto(TIMESERIES)
          .set(NAME, name)
          .set(DEVICE_ID, deviceId)
          .set(UNITS, units)
          .set(DECIMAL_PLACES, decimalPlaces)
          .set(TYPE_ID, type)
          .execute()
    }
  }

  @EventListener
  fun handle(message: IncomingTimeseriesUpdateMessage) {
    val deviceId = deviceFetcher.getDeviceIdForMqttTopic(message.topic)
    if (deviceId == null) {
      log.info("Ignoring sequence update for unknown device ${message.topic}")
      return
    }

    val timeseriesIds = timeSeriesFetcher.getTimeseriesIdsByName(deviceId, message.values.keys)
    val valuesByTimeseriesId =
        message.values.filter { it.key in timeseriesIds }.mapKeys { timeseriesIds[it.key] }

    valuesByTimeseriesId.forEach { (timeseriesId, value) ->
      with(TIMESERIES_VALUE) {
        dslContext
            .insertInto(TIMESERIES_VALUE)
            .set(TIMESERIES_ID, timeseriesId)
            .set(CREATED_TIME, message.timestamp)
            .set(VALUE, value)
            .execute()
      }
    }
  }
}
