package com.terraformation.seedbank.mqtt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.terraformation.seedbank.auth.JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM
import com.terraformation.seedbank.auth.JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM
import com.terraformation.seedbank.config.TerrawareServerConfig
import java.util.Date
import java.util.UUID
import javax.annotation.ManagedBean

@ManagedBean
class JwtGenerator(private val config: TerrawareServerConfig) {
  fun generateMqttToken(subject: String, topicPattern: String): String {
    val secret = config.jwtSecret ?: throw IllegalArgumentException("No JWT secret is configured")

    val date = Date()

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

    return jwt.serialize()
  }
}
