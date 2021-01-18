package com.terraformation.seedbank.mqtt

import com.terraformation.seedbank.services.perClassLogger
import javax.annotation.ManagedBean
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.springframework.context.ApplicationEventPublisher

/**
 * Handles incoming MQTT messages. Messages are parsed and, if they're well-formed and relevant to
 * the server, are turned into [IncomingMqttMessage] instances which are then published on the local
 * application event bus. Event listeners react to those messages and trigger appropriate business
 * logic.
 */
@ManagedBean
class MessageHandler(
    private val parser: MessageParser,
    private val publisher: ApplicationEventPublisher,
) : MqttCallbackExtended {
  private val log = perClassLogger()

  override fun connectionLost(cause: Throwable) {
    log.warn("Lost connection to MQTT broker; will try to reconnect")
  }

  override fun messageArrived(topic: String, message: MqttMessage) {
    val incomingMessage = parser.parse(topic, message)
    if (incomingMessage != null) {
      publisher.publishEvent(incomingMessage)
    }
  }

  override fun deliveryComplete(token: IMqttDeliveryToken) {
    // No-op
  }

  override fun connectComplete(reconnect: Boolean, serverURI: String) {
    log.info("Connected to MQTT broker $serverURI")
  }
}
