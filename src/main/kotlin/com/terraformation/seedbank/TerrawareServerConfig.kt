package com.terraformation.seedbank

import javax.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("terraware")
class TerrawareServerConfig {
  /** Secret key to use for JWT signing. This must match the key in the Mosquitto configuration. */
  @Size(min = 32, message = "Secret must be at least 32 bytes (256 bits)")
  var jwtSecret: ByteArray? = null
}
