package com.terraformation.backend.device.mqtt

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.log
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.MQTT_ENABLED_PROPERTY)
@ManagedBean
class MqttLoggingListener {
  private val log = perClassLogger()

  @EventListener
  fun handle(event: IncomingTimeseriesUpdateMessage) {
    with(event) { log.info("Got sequence update on $topic with timestamp $timestamp: $values") }
  }

  /**
   * Writes an incoming log message (text sequence update with a sequence name of "log") to the
   * server's logs. The logger name includes the MQTT topic name with periods instead of slashes, so
   * that the server's logging configuration can be used to selectively control log routing by MQTT
   * topic name.
   */
  @EventListener
  fun handle(event: IncomingLogMessage) {
    with(event) {
      val dottedTopic = topic.replace('/', '.')
      val topicLogger = LoggerFactory.getLogger("mqtt.$dottedTopic")
      topicLogger.log(level, text)
    }
  }
}
