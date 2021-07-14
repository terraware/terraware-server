package com.terraformation.backend.device.mqtt

import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.device.db.TimeSeriesStore
import com.terraformation.backend.log.perClassLogger
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
    val deviceId =
        try {
          deviceStore.getDeviceIdForMqttTopic(message.topic)
        } catch (e: DeviceNotFoundException) {
          log.info("Ignoring sequence update for unknown device: ${e.message}")
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
