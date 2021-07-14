package com.terraformation.backend.mqtt

import java.time.Instant
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.slf4j.event.Level

/** Common properties for application events generated due to incoming MQTT messages. */
interface IncomingMqttMessage {
  val topic: String
  val rawMessage: MqttMessage
}

/** Application event for incoming updates to timeseries values. */
data class IncomingTimeseriesUpdateMessage(
    override val topic: String,
    override val rawMessage: MqttMessage,
    val timestamp: Instant,
    val values: Map<String, String>,
) : IncomingMqttMessage

/** Application event for log messages published via MQTT. */
data class IncomingLogMessage(
    override val topic: String,
    override val rawMessage: MqttMessage,
    val timestamp: Instant,
    val level: Level,
    val text: String
) : IncomingMqttMessage
