package com.terraformation.seedbank.api.mqtt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.SignedJWT
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.auth.ControllerClientIdentity
import com.terraformation.seedbank.auth.JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM
import com.terraformation.seedbank.auth.JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM
import com.terraformation.seedbank.auth.LoggedInUserIdentity
import com.terraformation.seedbank.auth.Role
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.daos.OrganizationDao
import com.terraformation.seedbank.db.tables.pojos.Organization
import com.terraformation.seedbank.mqtt.JwtGenerator
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

internal class CredentialsControllerTest {
  private val configuration = mockk<TerrawareServerConfig>()
  private val jwtGenerator = JwtGenerator(configuration)
  private val objectMapper = ObjectMapper().registerKotlinModule()
  private val organizationDao = mockk<OrganizationDao>()
  private val controller = CredentialsController(jwtGenerator, objectMapper, organizationDao)

  private val secret = Random.nextBytes(32)

  private val organizationId = 1L
  private val organizationName = "testOrg"

  private val superAdmin =
      LoggedInUserIdentity("admin", null, EnumSet.of(Role.AUTHENTICATED, Role.SUPER_ADMIN))
  private val apiClient = ControllerClientIdentity(organizationId)

  @BeforeEach
  fun setUp() {
    every { configuration.jwtSecret } returns secret
    every { organizationDao.fetchOneById(organizationId) } returns
        Organization(id = organizationId, name = organizationName)
  }

  @Test
  fun `fails if no JWT secret configured`() {
    every { configuration.jwtSecret } returns null
    assertThrows(NotFoundException::class.java) { controller.credentials(superAdmin) }
  }

  @Test
  fun `succeeds if JWT secret configured`() {
    assertNotNull(controller.credentials(superAdmin))
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
        listOf("$organizationName/#"),
        token.jwtClaimsSet.claims[JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM],
        "Publishable topics",
    )
    assertEquals(
        listOf("$organizationName/#"),
        token.jwtClaimsSet.claims[JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM],
        "Subscribable topics",
    )
  }

  @Test
  fun `token includes username from API request`() {
    val token = generateToken(apiClient)
    assertEquals(apiClient.username, token.jwtClaimsSet.subject)
  }

  @Test
  fun `credentials use username from API request`() {
    val credentials = generateCredentials(apiClient)
    assertEquals(apiClient.username, credentials.username)
  }

  private fun generateToken(identity: ClientIdentity): SignedJWT {
    val response = generateCredentials(identity)
    return SignedJWT.parse(response.password)
  }

  private fun generateCredentials(identity: ClientIdentity) =
      objectMapper.readValue<MqttCredentialsResponse>(controller.credentials(identity))
}
