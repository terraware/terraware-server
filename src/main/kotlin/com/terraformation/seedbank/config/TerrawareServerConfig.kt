package com.terraformation.seedbank.config

import java.net.URI
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import javax.validation.constraints.Min
import javax.validation.constraints.NotNull
import javax.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.Resource
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
   * is UTC. May be specified as a time zone name or a UTC offset.
   */
  var timeZone: ZoneId = ZoneOffset.UTC

  /**
   * Use a fake clock that can be advanced via API requests. This should only be enabled in test
   * environments.
   */
  var useTestClock: Boolean = false

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
      var topicPrefix: String? = null
  )
}
