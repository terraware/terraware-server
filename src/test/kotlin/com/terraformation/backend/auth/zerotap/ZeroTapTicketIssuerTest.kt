package com.terraformation.backend.auth.zerotap

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import com.terraformation.backend.TestClock
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.config.TerrawareServerConfig.ZeroTapConfig
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlin.io.encoding.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ZeroTapTicketIssuerTest {
  private val clock = TestClock(Instant.ofEpochSecond(1000))

  @Test
  fun `issueTicket returns ES256 ticket with unique ID signed by configured key`() {
    val keyPair = generateKeyPair("secp256r1")
    val issuer = createIssuer(keyPair)

    val ticket = SignedJWT.parse(issuer.issueTicket("auth-id"))
    val otherTicket = SignedJWT.parse(issuer.issueTicket("auth-id"))

    assertEquals(JWSAlgorithm.ES256, ticket.header.algorithm, "Algorithm")
    assertEquals(true, ticket.verify(ECDSAVerifier(keyPair.public as ECPublicKey)), "Signature")
    assertEquals(
        mapOf(
            "aud" to "terraformation-mobile-app-v2",
            "exp" to 1060L,
            "iat" to 1000L,
            "iss" to "http://issuer",
            "sub" to "auth-id",
        ),
        ticket.jwtClaimsSet.toJSONObject().minus("jti"),
        "Claims",
    )
    assertNotEquals(ticket.jwtClaimsSet.jwtid, otherTicket.jwtClaimsSet.jwtid, "Ticket IDs")
  }

  @Test
  fun `issueTicket rejects signing keys that are not on the P-256 curve`() {
    val issuer = createIssuer(generateKeyPair("secp384r1"))

    assertThrows<IllegalArgumentException> { issuer.issueTicket("auth-id") }
  }

  private fun generateKeyPair(curveName: String): KeyPair {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec(curveName))
    return generator.generateKeyPair()
  }

  private fun createIssuer(keyPair: KeyPair): ZeroTapTicketIssuer {
    val config = mockk<TerrawareServerConfig>()
    every { config.webAppUrl } returns URI("http://issuer/")
    every { config.zeroTap } returns
        ZeroTapConfig(
            enabled = true,
            signingKeyPrivate = Base64.encode(keyPair.private.encoded),
        )

    return ZeroTapTicketIssuer(clock, config)
  }
}
