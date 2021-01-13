package com.terraformation.seedbank.api

import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.SignedJWT
import com.terraformation.seedbank.TerrawareServerConfig
import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.auth.ControllerClientIdentity
import com.terraformation.seedbank.auth.JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM
import com.terraformation.seedbank.auth.JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM
import com.terraformation.seedbank.auth.LoggedInUserIdentity
import com.terraformation.seedbank.auth.Role
import io.mockk.every
import io.mockk.mockk
import java.util.EnumSet
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TokenControllerTest {
  private val configuration = mockk<TerrawareServerConfig>()
  private val controller = TokenController(configuration)

  private val secret = Random.nextBytes(32)

  private val organizationId = 1L
  private val superAdmin =
      LoggedInUserIdentity("admin", null, EnumSet.of(Role.AUTHENTICATED, Role.SUPER_ADMIN))
  private val apiClient = ControllerClientIdentity(organizationId)

  @BeforeEach
  fun setUp() {
    every { configuration.jwtSecret } returns secret
  }

  @Test
  fun `fails if no JWT secret configured`() {
    every { configuration.jwtSecret } returns null
    assertThrows(NotFoundException::class.java) { controller.generateToken(superAdmin) }
  }

  @Test
  fun `succeeds if JWT secret configured`() {
    assertNotNull(controller.generateToken(superAdmin))
  }

  @Test
  fun `token is signed with configured secret key`() {
    val token = generateToken(superAdmin)
    assertTrue(token.verify(MACVerifier(secret)))
  }

  @Test
  fun `token allows all topics for super admin`() {
    val token = generateToken(superAdmin)
    assertEquals(
        listOf("#"),
        token.jwtClaimsSet.claims[JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM],
        "Publishable topics")
    assertEquals(
        listOf("#"),
        token.jwtClaimsSet.claims[JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM],
        "Subscribable topics")
  }

  @Test
  fun `token only allows per-org topics for API client`() {
    val token = generateToken(apiClient)
    assertEquals(
        listOf("$organizationId/#"),
        token.jwtClaimsSet.claims[JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM],
        "Publishable topics",
    )
    assertEquals(
        listOf("$organizationId/#"),
        token.jwtClaimsSet.claims[JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM],
        "Subscribable topics",
    )
  }

  private fun generateToken(identity: ClientIdentity) =
      SignedJWT.parse(controller.generateToken(identity).token)
}
