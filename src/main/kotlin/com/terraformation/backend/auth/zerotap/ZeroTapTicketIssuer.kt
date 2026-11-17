package com.terraformation.backend.auth.zerotap

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.InstantSource
import java.util.UUID
import kotlin.io.encoding.Base64
import org.bouncycastle.jce.ECNamedCurveTable
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

/**
 * Issues zero-tap tickets: short-lived, single-use JWTs that Keycloak's zero-tap authenticator
 * exchanges for tokens without asking the user to log in.
 */
@ConditionalOnProperty(TerrawareServerConfig.ZERO_TAP_ENABLED_PROPERTY)
@Named
class ZeroTapTicketIssuer(
    private val clock: InstantSource,
    private val config: TerrawareServerConfig,
) {
  private val log = perClassLogger()

  /** Must exactly match the issuer configured in Keycloak's zero-tap authenticator. */
  private val issuer = config.webAppUrl.toString().trimEnd('/')
  private val jwtEncoder: JwtEncoder by lazy { createJwtEncoder() }

  /**
   * Returns a signed ticket for a user. Keycloak only accepts each ticket once, so each one has a
   * unique ID.
   *
   * @param authId The user's Keycloak user ID.
   */
  fun issueTicket(authId: String): String {
    val now = clock.instant()

    val claims =
        JwtClaimsSet.builder()
            .audience(listOf(config.zeroTap.audience))
            .expiresAt(now.plusSeconds(config.zeroTap.ticketLifetimeSeconds))
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .issuer(issuer)
            .subject(authId)
            .build()
    val header = JwsHeader.with(SignatureAlgorithm.ES256).build()

    log.debug(
        "Issuing zero-tap ticket ${claims.id} for subject $authId: issuer $issuer, audience " +
            "${config.zeroTap.audience}, expires ${claims.expiresAt}"
    )

    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
  }

  /**
   * Creates the JWT encoder for zero-tap tickets. The configuration only has the private key, but
   * the encoder requires the public key too, so derive it from the private key.
   */
  private fun createJwtEncoder(): JwtEncoder {
    val keyFactory = KeyFactory.getInstance("EC")
    val privateKeyBytes = Base64.decode(config.zeroTap.signingKeyPrivate!!.trim())
    val privateKey =
        keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as ECPrivateKey

    if (Curve.forECParameterSpec(privateKey.params) != Curve.P_256) {
      throw IllegalArgumentException("Zero-tap signing key must be an EC P-256 key")
    }

    val publicPoint =
        ECNamedCurveTable.getParameterSpec("secp256r1").g.multiply(privateKey.s).normalize()
    val publicKey =
        keyFactory.generatePublic(
            ECPublicKeySpec(
                ECPoint(
                    publicPoint.affineXCoord.toBigInteger(),
                    publicPoint.affineYCoord.toBigInteger(),
                ),
                privateKey.params,
            )
        ) as ECPublicKey

    val jwk = ECKey.Builder(Curve.P_256, publicKey).privateKey(privateKey).build()
    val fingerprint = Base64.encode(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))
    log.debug(
        "Zero-tap tickets will be signed for issuer $issuer with the private key for public key " +
            "${Base64.encode(publicKey.encoded)} (spkiSha256=$fingerprint)"
    )
    return NimbusJwtEncoder(ImmutableJWKSet(JWKSet(jwk)))
  }
}
