package com.terraformation.seedbank.mqtt

import com.terraformation.seedbank.db.DeviceStore
import com.terraformation.seedbank.db.TimeSeriesStore
import com.terraformation.seedbank.services.perClassLogger
import javax.annotation.ManagedBean
import org.springframework.context.event.EventListener

@ManagedBean
class MqttTimeseriesListener(
    private val deviceStore: DeviceStore,
    private val timeSeriesStore: TimeSeriesStore,
) {
  private val log = perClassLogger()

  @EventListener
  fun handle(message: IncomingTimeseriesUpdateMessage) {
    val deviceId = deviceStore.getDeviceIdForMqttTopic(message.topic)
    if (deviceId == null) {
      log.info("Ignoring sequence update for unknown device ${message.topic}")
      return
    }

    val timeseriesIds = timeSeriesStore.getTimeseriesIdsByName(deviceId, message.values.keys)
    val valuesByTimeseriesId =
        message.values.filter { it.key in timeseriesIds }.mapKeys { timeseriesIds[it.key]!! }

    valuesByTimeseriesId.forEach { (timeseriesId, value) ->
      timeSeriesStore.insertValue(timeseriesId, value, message.timestamp)
    }
  }
}
