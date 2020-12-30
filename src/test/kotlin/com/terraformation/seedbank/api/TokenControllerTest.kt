package com.terraformation.seedbank.api

import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.SignedJWT
import com.terraformation.seedbank.TerrawareServerConfig
import com.terraformation.seedbank.auth.JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM
import com.terraformation.seedbank.auth.JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM
import com.terraformation.seedbank.auth.ORGANIZATION_ID_ATTR
import com.terraformation.seedbank.auth.Role
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.authentication.DefaultAuthentication
import io.micronaut.security.token.config.TokenConfiguration
import io.mockk.every
import io.mockk.mockk
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TokenControllerTest {
  private val configuration = mockk<TerrawareServerConfig>()
  private val controller = TokenController(configuration)

  private val secret = Random.nextBytes(32)

  private val organizationId = Int(1)
  private val superAdmin =
    DefaultAuthentication(
      "admin",
      mapOf(
        TokenConfiguration.DEFAULT_ROLES_NAME to
            listOf(Role.AUTHENTICATED.name, Role.SUPER_ADMIN.name)
      )
    )
  private val apiClient =
    DefaultAuthentication(
      "apiClient",
      mapOf(
        TokenConfiguration.DEFAULT_ROLES_NAME to
            listOf(Role.API_CLIENT.name, Role.AUTHENTICATED.name),
        ORGANIZATION_ID_ATTR to organizationId
      )
    )

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
      "Publishable topics"
    )
    assertEquals(
      listOf("#"),
      token.jwtClaimsSet.claims[JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM],
      "Subscribable topics"
    )
  }

  @Test
  fun `token only allows per-org topics for API client`() {
    val token = generateToken(apiClient)
    assertEquals(
      listOf("$organizationId/#"),
      token.jwtClaimsSet.claims[JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM],
      "Publishable topics"
    )
    assertEquals(
      listOf("$organizationId/#"),
      token.jwtClaimsSet.claims[JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM],
      "Subscribable topics"
    )
  }

  private fun generateToken(authentication: Authentication) =
    SignedJWT.parse(controller.generateToken(authentication).token)
}
