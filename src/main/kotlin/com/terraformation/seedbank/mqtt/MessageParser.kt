package com.terraformation.seedbank.mqtt

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.annotation.ManagedBean
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.slf4j.event.Level
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Parses raw MQTT messages. Messages are formatted per the MQTT API definition in the rhizo-client
 * library.
 */
@ConditionalOnProperty(TerrawareServerConfig.MQTT_ENABLED_PROPERTY)
@ManagedBean
class MessageParser(private val objectMapper: ObjectMapper) {
  var clock = Clock.systemUTC()!!

  private val log = perClassLogger()

  fun parse(topic: String, message: MqttMessage): IncomingMqttMessage? {
    if (message.payload.isEmpty()) {
      log.warn("Ignoring MQTT message on $topic with empty payload")
      return null
    }

    return when (val firstChar = message.payload[0].toInt().toChar()) {
      '{' -> parseJsonMessage(topic, message)
      's' -> parseTextTimeseriesMessage(topic, message)
      else -> {
        log.warn("Received message on $topic with unrecognized format character $firstChar")
        null
      }
    }
  }

  private fun parseJsonMessage(topic: String, message: MqttMessage): IncomingMqttMessage? {
    try {
      val obj = objectMapper.readValue<Map<String, Map<String, String>>>(message.payload)

      if (obj.size != 1) {
        log.warn("Ignoring JSON-format MQTT message with ${obj.size} fields; expected 1")
        return null
      }

      return when (val messageType = obj.keys.first()) {
        "update" -> parseJsonTimeseriesUpdate(topic, message, obj.values.first())
        "send_email", "send_sms", "watchdog" -> {
          log.error("Message type $messageType not supported yet")
          null
        }
        else -> {
          log.error("Ignoring MQTT message with unknown type $messageType")
          null
        }
      }
    } catch (e: JsonProcessingException) {
      log.warn("Unable to deserialize MQTT message", e)
      return null
    }
  }

  private fun parseJsonTimeseriesUpdate(
      topic: String,
      message: MqttMessage,
      updates: Map<String, String>
  ): IncomingMqttMessage {
    // The message includes a field $t with the timestamp, which we want to expose as metadata
    // rather than as an entry in the list of timeseries to update.
    val time = updates["\$t"]
    val timestamp = parseTimestamp(time)

    val updatesWithoutTimestamp = updates.filterKeys { it != "\$t" }

    return IncomingTimeseriesUpdateMessage(topic, message, timestamp, updatesWithoutTimestamp)
  }

  private fun parseTextTimeseriesMessage(
      topic: String,
      message: MqttMessage
  ): IncomingMqttMessage? {
    val payloadString = message.payload.decodeToString()
    val fields = payloadString.split(',', limit = 4)

    if (fields.size != 4) {
      log.warn("CSV-format MQTT message had ${fields.size} fields; expected 4")
      return null
    }

    val timestamp = parseTimestamp(fields[2])

    return if (fields[1] == "log") {
      parseLogMessage(topic, message, timestamp, fields[3])
    } else {
      IncomingTimeseriesUpdateMessage(topic, message, timestamp, mapOf(fields[1] to fields[3]))
    }
  }

  private fun parseLogMessage(
      topic: String,
      message: MqttMessage,
      timestamp: Instant,
      body: String
  ): IncomingMqttMessage {
    val parts = body.split(": ", limit = 2)
    val text = if (parts.size == 2) parts[1] else body

    // Python logging library uses WARNING while our logging uses WARN, so explicitly map the levels
    val level =
        when (parts[0]) {
          "DEBUG" -> Level.DEBUG
          "WARN", "WARNING" -> Level.WARN
          "ERROR" -> Level.ERROR
          else -> Level.INFO
        }

    return IncomingLogMessage(topic, message, timestamp, level, text)
  }

  /**
   * Parses a timestamp. Timestamps from rhizo-client are rendered in ISO-8601 format except that
   * there is a space before the time zone.
   */
  private fun parseTimestamp(time: String?): Instant {
    if (time == null) {
      log.warn("No timestamp in MQTT message; will use current time")
      return clock.instant()
    }

    val timeWithoutWhitespace = time.replace(" ", "")
    return try {
      Instant.parse(timeWithoutWhitespace)
    } catch (e: DateTimeParseException) {
      log.warn("Unable to parse timestamp $time from MQTT message; will use current time")
      clock.instant()
    }
  }
}
