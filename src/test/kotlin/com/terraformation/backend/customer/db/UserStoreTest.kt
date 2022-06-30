package com.terraformation.backend.customer.db

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.KeycloakRequestFailedException
import com.terraformation.backend.db.KeycloakUserNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.mockUser
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.keycloak.adapters.springboot.KeycloakSpringBootProperties
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.security.access.AccessDeniedException

/**
 * Tests for the user store.
 *
 * This uses a test fixture instead of calling out to a real Keycloak server; see
 * [InMemoryKeycloakUsersResource].
 */
internal class UserStoreTest : DatabaseTest(), RunsAsUser {
  private val clock: Clock = mockk()
  private val config: TerrawareServerConfig = mockk()
  private val httpClient: HttpClient = mockk()
  private val realmResource: RealmResource = mockk()
  private val usersResource = InMemoryKeycloakUsersResource()
  override val user: TerrawareUser = mockUser()

  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var permissionStore: PermissionStore
  private lateinit var userStore: UserStore

  private val keycloakConfig =
      TerrawareServerConfig.KeycloakConfig(
          clientId = "clientId",
          clientSecret = "clientSecret",
          apiClientId = "api",
          apiClientGroupName = "/api-clients",
          apiClientUsernamePrefix = "prefix-",
      )
  private val keycloakProperties = KeycloakSpringBootProperties()

  private val authId = "authId"
  private val organizationId = OrganizationId(1)
  private val userRepresentation =
      UserRepresentation().apply {
        email = "email"
        firstName = "firstName"
        id = authId
        lastName = "lastName"
        username = "email"
      }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC
    every { config.keycloak } returns keycloakConfig
    every { realmResource.users() } returns usersResource

    every { user.canAddOrganizationUser(organizationId) } returns true
    every { user.canCreateApiKey(organizationId) } returns true
    every { user.canDeleteApiKey(organizationId) } returns true
    every { user.canListApiKeys(organizationId) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canRemoveOrganizationUser(organizationId, any()) } returns true
    every { user.canSetOrganizationUserRole(organizationId, Role.CONTRIBUTOR) } returns true

    keycloakProperties.apply {
      authServerUrl = "http://keycloak"
      credentials = mapOf("secret" to "clientSecret")
      realm = "realm"
      resource = "clientId"
    }

    usersResource.create(userRepresentation)

    organizationStore = OrganizationStore(clock, dslContext, organizationsDao)
    parentStore = ParentStore(dslContext)
    permissionStore = PermissionStore(dslContext)

    userStore =
        UserStore(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            config,
            dslContext,
            httpClient,
            KeycloakInfo(keycloakProperties),
            jacksonObjectMapper(),
            organizationStore,
            parentStore,
            permissionStore,
            realmResource,
            usersDao,
        )
  }

  @Test
  fun `fetchByAuthId returns existing user without touching Keycloak`() {
    insertUser(authId = authId)

    val actual = userStore.fetchByAuthId(authId)

    assertEquals(authId, actual.authId)
  }

  @Test
  fun `fetchByAuthId fetches user information from Keycloak if not found locally`() {
    val user = userStore.fetchByAuthId(authId)

    assertEquals(userRepresentation.email, user.email)
  }

  @Test
  fun `fetchByAuthId adds auth ID and name to existing user with matching email address`() {
    insertUser(authId = null, email = userRepresentation.email)

    val newUser = userStore.fetchByAuthId(authId)

    assertEquals(user.userId, newUser.userId, "Should use existing user ID")

    val updatedRow = usersDao.fetchOneById(newUser.userId)!!

    assertEquals(userRepresentation.firstName, updatedRow.firstName, "First name")
    assertEquals(userRepresentation.lastName, updatedRow.lastName, "Last name")
    assertEquals(authId, updatedRow.authId, "Auth ID")
  }

  @Test
  fun `fetchByAuthId throws exception if user not found in Keycloak`() {
    assertThrows<KeycloakUserNotFoundException> { userStore.fetchByAuthId("nonexistent") }
  }

  @Test
  fun `fetchByAuthId throws exception if Keycloak request fails`() {
    usersResource.simulateRequestFailures = true

    assertThrows<KeycloakRequestFailedException> { userStore.fetchByAuthId(authId) }
  }

  @Test
  fun `fetchByEmail returns existing user without touching Keycloak`() {
    insertUser(email = userRepresentation.email)

    val actual = userStore.fetchByEmail(userRepresentation.email)

    assertEquals(userRepresentation.email, actual?.email)
  }

  @Test
  fun `fetchByEmail fetches user information from Keycloak if not found locally`() {
    val actual = userStore.fetchByEmail(userRepresentation.email)

    assertEquals(userRepresentation.email, actual?.email)
  }

  @Test
  fun `fetchByEmail returns null if user does not exist in Keycloak`() {
    assertNull(userStore.fetchByEmail("nonexistent"))
  }

  @Test
  fun `fetchByEmail throws exception if Keycloak request fails`() {
    usersResource.simulateRequestFailures = true

    assertThrows<KeycloakRequestFailedException> {
      userStore.fetchByEmail(userRepresentation.email)
    }
  }

  @Test
  fun `fetchOrCreateByEmail returns existing user`() {
    insertUser(email = userRepresentation.email)

    val actual = userStore.fetchOrCreateByEmail(userRepresentation.email)

    assertEquals(userRepresentation.email, actual.email)
  }

  @Test
  fun `fetchOrCreateByEmail creates new user with no authId if email not found`() {
    val email = "nonexistent@example.org"
    val actual = userStore.fetchOrCreateByEmail(email)

    assertEquals(email, actual.email)
    assertNull(actual.authId)
  }

  @Nested
  inner class ApiClientTest {
    @BeforeEach
    fun setUpOrganization() {
      insertUser()
      insertOrganization(organizationId)
    }

    @Test
    fun `createApiClient throws exception if user does not have permission to create clients`() {
      every { user.canCreateApiKey(organizationId) } returns false

      assertThrows<AccessDeniedException> {
        userStore.createApiClient(organizationId, "Description")
      }
    }

    @Test
    fun `createApiClient registers user in Keycloak and adds it to organization`() {
      val description = "Description"
      val newUser = userStore.createApiClient(organizationId, description)

      val keycloakUser = usersResource.get(newUser.authId!!)!!.toRepresentation()
      assertEquals(
          description, keycloakUser.firstName, "Should use description as first name in Keycloak")
      assertEquals(
          "Organization $organizationId",
          keycloakUser.lastName,
          "Should use organization ID as last name in Keycloak")
      assertEquals(
          config.keycloak.apiClientUsernamePrefix,
          keycloakUser.username.substring(0, config.keycloak.apiClientUsernamePrefix.length),
          "Should include username prefix in Keycloak")
      assertEquals(
          listOf("/api-clients"),
          keycloakUser.groups,
          "Should add user to API clients group in Keycloak")

      assertEquals(
          mapOf(organizationId to Role.CONTRIBUTOR),
          newUser.organizationRoles,
          "Should grant contributor role to API client user")
    }

    @Test
    fun `deleteApiClient removes user from Keycloak`() {
      val user = userStore.createApiClient(organizationId, null)
      val authId = user.authId!!

      assertNotNull(usersResource.get(authId), "User exists in Keycloak after creation")

      userStore.deleteApiClient(user.userId)

      assertNull(usersResource.get(authId), "User does not exist in Keycloak after deletion")
    }

    @Test
    fun `deleteApiClient causes user to no longer be findable`() {
      val user = userStore.createApiClient(organizationId, null)

      assertTrue(userStore.deleteApiClient(user.userId), "Deletion should have succeeded")

      assertNull(userStore.fetchById(user.userId), "Looking up by user ID should fail")
      assertNull(userStore.fetchByEmail(user.email), "Looking up by username should fail")
      assertThrows<IllegalArgumentException>("Should not be able to generate token for user") {
        userStore.generateOfflineToken(user.userId)
      }
      assertThrows<KeycloakUserNotFoundException>("Looking up user by auth ID should fail") {
        userStore.fetchByAuthId(user.authId!!)
      }
    }

    @Test
    fun `deleteApiClient deletes user locally even if already deleted from Keycloak`() {
      val user = userStore.createApiClient(organizationId, null)

      usersResource.delete(user.authId)

      assertTrue(userStore.deleteApiClient(user.userId), "Deletion should have succeeded")

      assertNull(userStore.fetchById(user.userId), "User should not exist after deletion")
    }
  }

  @Nested
  inner class GenerateOfflineTokenTest {
    @BeforeEach
    fun setUpOrganization() {
      insertUser()
      insertOrganization(organizationId)
    }

    @Test
    fun `generateOfflineToken does not work for individual users`() {
      assertThrows<IllegalArgumentException> { userStore.generateOfflineToken(user.userId) }
    }

    @Test
    fun `generateOfflineToken requests a token from Keycloak`() {
      val user = userStore.createApiClient(organizationId, null)
      val expectedToken = "token"

      val response: HttpResponse<String> = mockk()
      val requestSlot = slot<HttpRequest>()
      every { httpClient.send(capture(requestSlot), any<HttpResponse.BodyHandler<*>>()) } returns
          response
      every { response.statusCode() } returns 200
      every { response.body() } returns """{"refresh_token":"$expectedToken","foo":"bar"}"""

      assertEquals(
          expectedToken,
          userStore.generateOfflineToken(user.userId),
          "Should return refresh token from Keycloak")
    }

    @Test
    fun `generateOfflineToken throws exception if Keycloak returns a malformed token response`() {
      val user = userStore.createApiClient(organizationId, null)

      val response: HttpResponse<String> = mockk()
      val requestSlot = slot<HttpRequest>()
      every { httpClient.send(capture(requestSlot), any<HttpResponse.BodyHandler<*>>()) } returns
          response
      every { response.statusCode() } returns 200
      every { response.body() } returns """welcome to clowntown"""

      assertThrows<KeycloakRequestFailedException> { userStore.generateOfflineToken(user.userId) }
    }

    @Test
    fun `generateOfflineToken throws exception if Keycloak does not generate a token`() {
      val user = userStore.createApiClient(organizationId, null)

      val response: HttpResponse<String> = mockk()
      val requestSlot = slot<HttpRequest>()
      every { httpClient.send(capture(requestSlot), any<HttpResponse.BodyHandler<*>>()) } returns
          response
      every { response.statusCode() } returns 200
      every { response.body() } returns """{}"""

      assertThrows<KeycloakRequestFailedException> { userStore.generateOfflineToken(user.userId) }
    }

    @Test
    fun `generateOfflineToken generates a temporary password and removes it if token creation fails`() {
      val user = userStore.createApiClient(organizationId, null)
      val keycloakUser = usersResource.get(user.authId!!)!!

      val response: HttpResponse<String> = mockk()
      every { httpClient.send(any(), any<HttpResponse.BodyHandler<*>>()) } returns response
      every { response.statusCode() } returns 500
      every { response.body() } returns "body"

      assertThrows<KeycloakRequestFailedException> { userStore.generateOfflineToken(user.userId) }

      // Expected behavior is that we have asked Keycloak to reset the user's password, then asked
      // it
      // to remove the password.
      verify { keycloakUser.resetPassword(any()) }
      verify { keycloakUser.removeCredential(any()) }

      assertTrue(usersResource.credentials.isEmpty(), "Credentials should have been removed")
    }
  }

  @Test
  fun `updateUser updates profile information`() {
    val newFirstName = "Testy"
    val newLastName = "McTestalot"

    insertUser(authId = userRepresentation.id, email = userRepresentation.email)

    val oldEmail = userRepresentation.email
    val model = userStore.fetchByEmail(oldEmail)!!

    val mockUserResource = usersResource.get(model.authId!!)!!
    val representationSlot = slot<UserRepresentation>()
    every { mockUserResource.update(capture(representationSlot)) } just Runs
    every { user.userId } returns model.userId

    val modelWithEdits =
        model.copy(
            email = "newemail@x.com",
            firstName = newFirstName,
            lastName = newLastName,
            emailNotificationsEnabled = true)
    userStore.updateUser(modelWithEdits)

    val updatedModel = userStore.fetchById(model.userId)!!

    assertEquals(oldEmail, updatedModel.email, "Email (DB)")
    assertEquals(newFirstName, updatedModel.firstName, "First name (DB)")
    assertEquals(newLastName, updatedModel.lastName, "Last name (DB)")
    assertTrue(updatedModel.emailNotificationsEnabled, "Email notifications enabled (DB)")

    val updatedRepresentation = representationSlot.captured
    assertEquals(oldEmail, updatedRepresentation.email, "Email (Keycloak)")
    assertEquals(newFirstName, updatedRepresentation.firstName, "First name (Keycloak)")
    assertEquals(newLastName, updatedRepresentation.lastName, "Last name (Keycloak)")
  }

  @Test
  fun `updateUser does not allow updating other users`() {
    insertUser(email = userRepresentation.email)

    every { user.userId } returns UserId(12345678)

    val model = userStore.fetchByEmail(userRepresentation.email)!!

    assertThrows<AccessDeniedException> { userStore.updateUser(model) }
  }
}
