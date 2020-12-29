package com.terraformation.seedbank

import io.micronaut.context.annotation.ConfigurationProperties
import javax.validation.constraints.Size

@ConfigurationProperties("terraware.server")
class TerrawareServerConfig {
  @Size(min = 32)
  var jwtSecret: ByteArray? = null
}
