package com.terraformation.seedbank.mqtt

import com.terraformation.seedbank.db.DeviceFetcher
import com.terraformation.seedbank.db.TimeSeriesFetcher
import com.terraformation.seedbank.services.perClassLogger
import javax.annotation.ManagedBean
import org.springframework.context.event.EventListener

@ManagedBean
class MqttTimeseriesListener(
    private val deviceFetcher: DeviceFetcher,
    private val timeSeriesFetcher: TimeSeriesFetcher,
) {
  private val log = perClassLogger()

  @EventListener
  fun handle(message: IncomingTimeseriesUpdateMessage) {
    val deviceId = deviceFetcher.getDeviceIdForMqttTopic(message.topic)
    if (deviceId == null) {
      log.info("Ignoring sequence update for unknown device ${message.topic}")
      return
    }

    val timeseriesIds = timeSeriesFetcher.getTimeseriesIdsByName(deviceId, message.values.keys)
    val valuesByTimeseriesId =
        message.values.filter { it.key in timeseriesIds }.mapKeys { timeseriesIds[it.key]!! }

    valuesByTimeseriesId.forEach { (timeseriesId, value) ->
      timeSeriesFetcher.insertValue(timeseriesId, value, message.timestamp)
    }
  }
}
