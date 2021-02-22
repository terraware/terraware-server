package com.terraformation.seedbank.config

import java.net.URI
import java.nio.file.Path
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.validation.constraints.Min
import javax.validation.constraints.NotNull
import javax.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.validation.annotation.Validated

@ConfigurationProperties("terraware")
@Validated
class TerrawareServerConfig {
  /**
   * Base64-encoded secret key to use for JWT signing. This must match the key in the Mosquitto
   * configuration.
   */
  @Size(min = 32, message = "Secret must be at least 32 bytes (256 bits)")
  var jwtSecret: ByteArray? = null

  /** URL of site-specific configuration file. */
  @NotNull lateinit var siteConfigUrl: Resource

  @Min(1) var siteModuleId: Long = 0

  /**
   * Directory to use for photo storage. The server will attempt to create this directory if it
   * doesn't exist.
   */
  @NotNull lateinit var photoDir: Path

  /**
   * Number of levels of parent directories to create for photos. Photos are stored in a tree of
   * single-character subdirectories from the beginning of the accession number. For example, if
   * this is 3, and `photoDir` is `/x/y`, photos for accession `ABCDEFG` will be stored in
   * `/x/y/A/B/C/ABCDEFG`.
   */
  @Min(0) var photoIntermediateDepth: Int = 3

  /**
   * Server's time zone. This is mostly used to determine when scheduled daily jobs are run. Default
   * is UTC. May be specified as a tz database time zone name such as `US/Hawaii` or a UTC offset
   * such as `+04:00`.
   */
  var timeZone: ZoneId = ZoneOffset.UTC

  /**
   * Use a fake clock that can be advanced via API requests. This should only be enabled in test
   * environments.
   */
  var useTestClock: Boolean = false

  /**
   * What time of day the daily tasks are run. This is treated as a local time in the configured
   * [timeZone]. Default is 8AM.
   */
  @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
  var dailyTasksStartTime: LocalTime = LocalTime.of(8, 0)

  /**
   * API key to create by default. If this is set and the key isn't already present in the `api_key`
   * table, any other API keys are removed from the database at start time and the new key is
   * created. If it is not set, the `api_key` table should be populated some other way.
   *
   * This is just a stopgap; in the future we'll introduce real API key management capability.
   */
  var apiKey: String? = null

  /** Configures the server's communication with an MQTT broker. */
  var mqtt: MqttConfig = MqttConfig()

  class MqttConfig(
      /** If true, connect to an MQTT broker to publish and receive device data. */
      var enabled: Boolean = false,

      /** URI of MQTT broker. Supported schemes are "ws", "wss", and "tcp". */
      var address: URI? = URI("ws://localhost:1883"),

      /** Client identifier to send during MQTT authentication. */
      var clientId: String? = "seedbank-server",

      /**
       * Prefix to use in front of topic names. The server will automatically add "/" between this
       * and the topic names it generates. Default is to directly use the generated topics with no
       * prefix.
       */
      var topicPrefix: String? = null,

      /** How often to retry connecting to the MQTT broker if the connection fails. */
      var connectRetryIntervalMillis: Long = 30000,
  )

  companion object {
    const val MQTT_ENABLED_PROPERTY = "terraware.mqtt.enabled"
  }
}
