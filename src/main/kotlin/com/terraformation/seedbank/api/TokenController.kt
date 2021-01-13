package com.terraformation.seedbank.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.terraformation.seedbank.TerrawareServerConfig
import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.auth.JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM
import com.terraformation.seedbank.auth.JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM
import com.terraformation.seedbank.services.perClassLogger
import io.swagger.v3.oas.annotations.Hidden
import java.util.Date
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Generates authentication tokens that may be used to authenticate to Mosquitto. */
@RestController
@RequestMapping("/api/v1/token")
@Hidden // Hide from Swagger docs while iterating on the seed bank app's API
class TokenController(private val config: TerrawareServerConfig) {
  private val log = perClassLogger()

  /**
   * Returns a JSON Web Token that may be supplied as the password when authenticating with the MQTT
   * broker. The token is valid for 2 minutes, but may be used to establish a persistent connection
   * to the broker.
   */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  fun generateToken(identity: ClientIdentity): TokenResponse {
    val secret = config.jwtSecret
    if (secret == null) {
      log.error("No JWT secret is configured")
      throw NotFoundException()
    }

    val date = Date()
    val organizationId = identity.organizationId
    val subject = if (organizationId != null) "$organizationId" else "admin"
    val topicPattern = if (organizationId != null) "$organizationId/#" else "#"

    log.info("Topic pattern $topicPattern")
    val claimsSet =
        JWTClaimsSet.Builder()
            .claim(JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM, arrayOf(topicPattern))
            .claim(JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM, arrayOf(topicPattern))
            .expirationTime(Date(date.time + 120000))
            .issuer("terraware-server")
            .issueTime(date)
            .notBeforeTime(date)
            .subject(subject)
            .jwtID(UUID.randomUUID().toString())
            .build()

    val header = JWSHeader.Builder(JWSAlgorithm.HS256).build()
    val jwt = SignedJWT(header, claimsSet)
    val signer = MACSigner(secret)
    jwt.sign(signer)

    return TokenResponse(jwt.serialize())
  }
}

data class TokenResponse(val token: String)
