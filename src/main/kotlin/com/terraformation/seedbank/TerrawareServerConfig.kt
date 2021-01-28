package com.terraformation.seedbank

import java.net.URI
import javax.validation.constraints.Min
import javax.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
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

  @Min(1) var siteModuleId: Long = 0
}
