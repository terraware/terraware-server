package com.terraformation.seedbank.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.event.Level

internal class MessageParserTest {
  private val objectMapper = ObjectMapper().registerKotlinModule()
  private val parser = MessageParser(objectMapper)

  private val systemTime = Instant.now()!!
  private val topic = "test/topic"
  private val timestampWithSpace = "2021-01-01T12:34:56 Z"
  private val instant = Instant.parse("2021-01-01T12:34:56Z")
  private val timestampFieldName = "\$t"

  @BeforeEach
  fun useFakeClock() {
    parser.clock = Clock.fixed(systemTime, ZoneOffset.UTC)
  }

  @Test
  fun `message with unknown first character does not publish event`() {
    assertParseFailure("xyzzy")
  }

  @Test
  fun `detects log messages`() {
    val logText = "This is a test"
    val payload = "s,log,$timestampWithSpace,WARNING: $logText"

    assertParseResult(IncomingLogMessage(topic, mqtt(payload), instant, Level.WARN, logText))
  }

  @Test
  fun `unknown log levels are treated as INFO`() {
    val logText = "Unknown level test"
    val payload = "s,log,1970-01-01T00:00:00 Z,FOOBAR: $logText"

    assertParseResult(IncomingLogMessage(topic, mqtt(payload), Instant.EPOCH, Level.INFO, logText))
  }

  @Test
  fun `detects text timeseries updates`() {
    val timeseriesName = "my/timeseries/name"
    val value = "timeseries,value,with,commas"
    val payload = "s,$timeseriesName,$timestampWithSpace,$value"

    assertParseResult(
        IncomingTimeseriesUpdateMessage(
            topic, mqtt(payload), instant, mapOf(timeseriesName to value)))
  }

  @Test
  fun `malformed text timeseries update does not publish event`() {
    assertParseFailure("s,x/y")
  }

  @Test
  fun `malformed timestamps are replaced with current system time`() {
    val payload = "s,my/timeseries,THIS_IS_NOT_A_TIMESTAMP,1234.5678"

    assertParseResult(
        IncomingTimeseriesUpdateMessage(
            topic, mqtt(payload), systemTime, mapOf("my/timeseries" to "1234.5678")))
  }

  @Test
  fun `detects JSON timeseries updates`() {
    val payload =
        """{
      |"update": {
      |  "seq1": "val1",
      |  "seq2": 2.3456789,
      |  "$timestampFieldName": "$timestampWithSpace"
      |}
      |}""".trimMargin()

    assertParseResult(
        IncomingTimeseriesUpdateMessage(
            topic, mqtt(payload), instant, mapOf("seq1" to "val1", "seq2" to "2.3456789")))
  }

  @Test
  fun `uses current system time if JSON timeseries update is missing timestamp`() {
    val payload = """{
      |"update": {
      |  "seq1": "val1"
      |}
      |}""".trimMargin()

    assertParseResult(
        IncomingTimeseriesUpdateMessage(topic, mqtt(payload), systemTime, mapOf("seq1" to "val1")))
  }

  @Test
  fun `JSON messages with non-update operations do not generate events`() {
    val payload = """{"foo":{"bar":"baz"}}"""
    assertParseFailure(payload)
  }

  @Test
  fun `JSON messages multiple operations do not generate events`() {
    val payload =
        """{
      |  "update":{"a":"b"},
      |  "foo":{"bar":"baz"}
      |}""".trimMargin()
    assertParseFailure(payload)
  }

  @Test
  fun `malformed JSON messages do not generate events`() {
    assertParseFailure("{{{{{{{{")
  }

  private fun assertParseResult(expected: IncomingMqttMessage) {
    assertEquals(expected, parser.parse(topic, expected.rawMessage))
  }

  private fun assertParseFailure(payload: String) {
    assertNull(parser.parse(topic, mqtt(payload)))
  }

  /** Wraps a string in an MqttMessage. */
  private fun mqtt(payload: String) = MqttMessage(payload.toByteArray())
}
