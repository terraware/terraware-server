package com.terraformation.backend.device

import com.terraformation.backend.db.BalenaDeviceId
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean

/** Stub implementation of Balena client that doesn't interact with the actual Balena service. */
@ManagedBean
class BalenaClient {
  private val log = perClassLogger()

  fun configureDeviceManager(balenaId: BalenaDeviceId, facilityId: FacilityId, token: String) {
    val tokenExcerpt = token.substring(0..8) + "..."
    log.info("Configure Balena device $balenaId with facility $facilityId, token $tokenExcerpt")
  }
}
