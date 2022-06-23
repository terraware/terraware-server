package com.terraformation.backend.device.balena

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.BalenaDeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.log.perClassLogger
import java.time.Instant
import javax.annotation.ManagedBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@ConditionalOnProperty(
    TerrawareServerConfig.BALENA_ENABLED_PROPERTY, havingValue = "false", matchIfMissing = true)
@ManagedBean
class StubBalenaClient : BalenaClient {
  private val log = perClassLogger()

  override fun configureDeviceManager(
      balenaId: BalenaDeviceId,
      facilityId: FacilityId,
      token: String
  ) {
    val tokenExcerpt = token.substring(0, 8) + "..."
    log.info(
        "Would configure Balena device $balenaId with facility $facilityId, token $tokenExcerpt")
  }

  override fun getSensorKitIdForBalenaId(balenaId: BalenaDeviceId): String? {
    return null
  }

  override fun listModifiedDevices(after: Instant): List<BalenaDevice> {
    return emptyList()
  }
}
