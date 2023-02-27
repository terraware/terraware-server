package com.terraformation.backend.customer.db

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.DeviceManagerUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.KeycloakRequestFailedException
import com.terraformation.backend.db.KeycloakUserNotFoundException
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.records.UserPreferencesRecord
import com.terraformation.backend.db.default_schema.tables.references.USER_PREFERENCES
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.mockUser
import com.terraformation.backend.util.HttpClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.jooq.JSONB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

/**
 * Tests for the user store.
 *
 * This uses a test fixture instead of calling out to a real Keycloak server; see
 * [InMemoryKeycloakUsersResource].
 */
internal class UserStoreTest : DatabaseTest(), RunsAsUser {
  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val objectMapper = jacksonObjectMapper()
  private val publisher = TestEventPublisher()
  private val realmResource: RealmResource = mockk()
  private val usersResource = InMemoryKeycloakUsersResource()
  override val user: TerrawareUser = mockUser()

  private lateinit var httpClient: HttpClient
  private lateinit var organizationStore: OrganizationStore
  private lateinit var parentStore: ParentStore
  private lateinit var permissionStore: PermissionStore
  private lateinit var userStore: UserStore

  private lateinit var responseContent: ByteReadChannel
  private var responseStatusCode: HttpStatusCode = HttpStatusCode.OK
  private var responseHeaders: Headers = headersOf("Content-type", MediaType.APPLICATION_JSON_VALUE)

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
  private val userRepresentation =
      UserRepresentation().apply {
        attributes = mapOf("locale" to listOf("gx"))
        email = "email"
        firstName = "firstName"
        id = authId
        lastName = "lastName"
        username = "email"
      }

  @BeforeEach
  fun setUp() {
    every { config.keycloak } returns keycloakConfig
    every { realmResource.users() } returns usersResource

    every { user.canAddOrganizationUser(organizationId) } returns true
    every { user.canCreateApiKey(organizationId) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canRemoveOrganizationUser(organizationId, any()) } returns true
    every { user.canSetOrganizationUserRole(organizationId, Role.Contributor) } returns true

    val engine = MockEngine {
      respond(content = responseContent, status = responseStatusCode, headers = responseHeaders)
    }

    httpClient = HttpClientConfig().httpClient(engine, objectMapper)

    keycloakProperties.apply {
      authServerUrl = "http://keycloak"
      credentials = mapOf("secret" to "clientSecret")
      realm = "realm"
      resource = "clientId"
    }

    usersResource.create(userRepresentation)

    organizationStore = OrganizationStore(clock, dslContext, organizationsDao, publisher)
    parentStore = ParentStore(dslContext)
    permissionStore = PermissionStore(dslContext)

    userStore =
        UserStore(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            config,
            dslContext,
            httpClient,
            KeycloakInfo(keycloakProperties),
            organizationStore,
            parentStore,
            permissionStore,
            publisher,
            realmResource,
            usersDao,
        )
  }

  @Test
  fun `fetchByAuthId returns existing user without touching Keycloak`() {
    val timeZone = insertTimeZone()
    insertUser(authId = authId, firstName = "f", lastName = "l", timeZone = timeZone)

    val actual = userStore.fetchByAuthId(authId) as IndividualUser

    assertEquals(authId, actual.authId, "Auth ID")
    assertEquals("f", actual.firstName, "First name")
    assertEquals("l", actual.lastName, "Last name")
    assertEquals(timeZone, actual.timeZone, "Time zone")
  }

  @Test
  fun `fetchByAuthId fetches user information from Keycloak if not found locally`() {
    val user = userStore.fetchByAuthId(authId) as IndividualUser

    assertEquals(userRepresentation.email, user.email, "Email")
    assertEquals(Locales.GIBBERISH, user.locale, "Locale")
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

  @Test
  fun `fetchFullNameById returns full name for individual user`() {
    insertUser()

    assertEquals("First Last", userStore.fetchFullNameById(user.userId))
  }

  @Test
  fun `fetchFullNameById returns null for device manager user`() {
    insertUser(type = UserType.DeviceManager)

    assertNull(userStore.fetchFullNameById(user.userId))
  }

  @Nested
  inner class DeviceManagerTest {
    @BeforeEach
    fun setUpOrganization() {
      insertUser()
      insertOrganization()
    }

    @Test
    fun `createDeviceManagerUser throws exception if user does not have permission`() {
      every { user.canCreateApiKey(organizationId) } returns false

      assertThrows<AccessDeniedException> {
        userStore.createDeviceManagerUser(organizationId, "Description")
      }
    }

    @Test
    fun `createDeviceManagerUser registers user in Keycloak and adds it to organization`() {
      val description = "Description"
      val newUser = userStore.createDeviceManagerUser(organizationId, description)

      val keycloakUser = usersResource.get(newUser.authId)!!.toRepresentation()
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
          mapOf(organizationId to Role.Contributor),
          permissionStore.fetchOrganizationRoles(newUser.userId),
          "Should grant contributor role to device manager user")
    }
  }

  @Nested
  inner class GenerateOfflineTokenTest {
    @BeforeEach
    fun setUpOrganization() {
      insertUser()
      insertOrganization()
    }

    @Test
    fun `generateOfflineToken does not work for individual users`() {
      assertThrows<IllegalArgumentException> { userStore.generateOfflineToken(user.userId) }
    }

    @Test
    fun `generateOfflineToken requests a token from Keycloak`() {
      val user = userStore.createDeviceManagerUser(organizationId, null)
      val expectedToken = "token"

      responseContent = ByteReadChannel("""{"refresh_token":"$expectedToken","foo":"bar"}""")

      assertEquals(
          expectedToken,
          userStore.generateOfflineToken(user.userId),
          "Should return refresh token from Keycloak")
    }

    @Test
    fun `generateOfflineToken throws exception if Keycloak returns a malformed token response`() {
      val user = userStore.createDeviceManagerUser(organizationId, null)

      responseContent = ByteReadChannel("welcome to clowntown")

      assertThrows<KeycloakRequestFailedException> { userStore.generateOfflineToken(user.userId) }
    }

    @Test
    fun `generateOfflineToken throws exception if Keycloak does not generate a token`() {
      val user = userStore.createDeviceManagerUser(organizationId, null)

      responseContent = ByteReadChannel("{}")

      assertThrows<KeycloakRequestFailedException> { userStore.generateOfflineToken(user.userId) }
    }

    @Test
    fun `generateOfflineToken generates a temporary password and removes it if token creation fails`() {
      val user = userStore.createDeviceManagerUser(organizationId, null)
      val keycloakUser = usersResource.get(user.authId)!!

      responseContent = ByteReadChannel("body")

      assertThrows<KeycloakRequestFailedException> { userStore.generateOfflineToken(user.userId) }

      // Expected behavior is that we have asked Keycloak to reset the user's password, then asked
      // it to remove the password.
      verify { keycloakUser.resetPassword(any()) }
      verify { keycloakUser.removeCredential(any()) }

      assertTrue(usersResource.credentials.isEmpty(), "Credentials should have been removed")
    }
  }

  @Test
  fun `updateUser updates profile information`() {
    val newFirstName = "Testy"
    val newLastName = "McTestalot"
    val newLocale = Locale("gx")
    val newTimeZone = insertTimeZone()

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
            emailNotificationsEnabled = true,
            locale = newLocale,
            timeZone = newTimeZone,
        )
    userStore.updateUser(modelWithEdits)

    val updatedModel = userStore.fetchOneById(model.userId) as IndividualUser

    assertEquals(oldEmail, updatedModel.email, "Email (DB)")
    assertEquals(newFirstName, updatedModel.firstName, "First name (DB)")
    assertEquals(newLastName, updatedModel.lastName, "Last name (DB)")
    assertEquals(newLocale, updatedModel.locale, "Locale (DB)")
    assertEquals(newTimeZone, updatedModel.timeZone, "Time zone (DB)")
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

  @Nested
  inner class Preferences {
    @BeforeEach
    fun setUp() {
      insertUser()
      insertOrganization()
      insertOrganizationUser()
    }

    @Test
    fun `fetchPreferences returns per-user preferences`() {
      insertPreferences()
      insertPreferences(organizationId = organizationId)

      assertEquals(JSONB.valueOf("""{"org":"null"}"""), userStore.fetchPreferences(null))
    }

    @Test
    fun `fetchPreferences returns per-organization preferences`() {
      insertPreferences()
      insertPreferences(organizationId = organizationId)

      assertEquals(
          JSONB.valueOf("""{"org":"$organizationId"}"""),
          userStore.fetchPreferences(organizationId))
    }

    @Test
    fun `fetchPreferences returns null if no preferences for user`() {
      val otherUserId = UserId(50)
      insertUser(otherUserId)

      insertPreferences(otherUserId)
      insertPreferences(user.userId, organizationId)

      assertNull(userStore.fetchPreferences(null))
    }

    @Test
    fun `fetchPreferences returns null if no preferences for organization`() {
      val otherOrganizationId = OrganizationId(50)

      insertOrganization(otherOrganizationId)
      insertOrganizationUser(organizationId = otherOrganizationId)

      insertPreferences()
      insertPreferences(organizationId = otherOrganizationId)

      assertNull(userStore.fetchPreferences(organizationId))
    }

    @Test
    fun `fetchPreferences throws exception if no permission to read organization`() {
      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<OrganizationNotFoundException> { userStore.fetchPreferences(organizationId) }
    }

    @Test
    fun `updatePreferences inserts preferences if none exist`() {
      val newPreferences = JSONB.valueOf("""{"new":"prefs"}""")
      userStore.updatePreferences(null, newPreferences)

      assertEquals(newPreferences, userStore.fetchPreferences(null))
    }

    @Test
    fun `updatePreferences updates per-user preferences`() {
      insertPreferences()
      insertPreferences(organizationId = organizationId)

      val newPreferences = JSONB.valueOf("""{"new":"prefs"}""")
      userStore.updatePreferences(null, newPreferences)

      assertEquals(newPreferences, userStore.fetchPreferences(null))
    }

    @Test
    fun `updatePreferences updates per-organization preferences`() {
      insertPreferences()
      insertPreferences(organizationId = organizationId)

      val newPreferences = JSONB.valueOf("""{"new":"prefs"}""")
      userStore.updatePreferences(organizationId, newPreferences)

      assertEquals(newPreferences, userStore.fetchPreferences(organizationId))
    }

    @Test
    fun `updatePreferences throws exception if no permission to read organization`() {
      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<OrganizationNotFoundException> {
        userStore.updatePreferences(organizationId, JSONB.valueOf("{}"))
      }
    }

    @Test
    fun `user preferences can contain null values`() {
      val preferences = JSONB.valueOf("""{"key1":null,"key2":"value2"}""")
      userStore.updatePreferences(null, preferences)

      assertEquals(preferences, userStore.fetchPreferences(null))
    }

    private fun insertPreferences(
        userId: UserId = user.userId,
        organizationId: OrganizationId? = null,
        preferences: Map<String, Any> = mapOf("org" to "$organizationId")
    ) {
      dslContext
          .insertInto(
              USER_PREFERENCES,
              USER_PREFERENCES.USER_ID,
              USER_PREFERENCES.ORGANIZATION_ID,
              USER_PREFERENCES.PREFERENCES)
          .values(
              userId, organizationId, JSONB.valueOf(objectMapper.writeValueAsString(preferences)))
          .execute()
    }
  }

  @Nested
  inner class DeleteSelf {
    @BeforeEach
    fun setUp() {
      every { user.authId } returns authId
      every { user.canDeleteSelf() } returns true
    }

    @Test
    fun `system user cannot delete itself`() {
      SystemUser(usersDao).run { assertThrows<AccessDeniedException> { userStore.deleteSelf() } }
    }

    @Test
    fun `device manager user cannot delete itself`() {
      DeviceManagerUser(user.userId, authId, parentStore, permissionStore).run {
        assertThrows<AccessDeniedException> { userStore.deleteSelf() }
      }
    }

    @Test
    fun `anonymizes user in local database`() {
      insertUser(
          authId = authId,
          email = userRepresentation.email,
          firstName = userRepresentation.firstName,
          lastName = userRepresentation.lastName)

      userStore.deleteSelf()

      val updatedUser = usersDao.fetchOneById(user.userId)!!

      assertNotEquals(authId, updatedUser.authId, "Auth ID")
      assertNotEquals(userRepresentation.email, updatedUser.email, "Email address")
      assertNotNull(updatedUser.deletedTime, "Deleted time")
      assertEquals("Deleted", updatedUser.firstName, "First name")
      assertEquals("User", updatedUser.lastName, "Last name")
    }

    @Test
    fun `publishes UserDeletedEvent`() {
      insertUser(authId = authId)

      userStore.deleteSelf()

      publisher.assertEventPublished(UserDeletionStartedEvent(user.userId))
    }

    @Test
    fun `removes user from Keycloak`() {
      insertUser(authId = authId)

      userStore.deleteSelf()

      assertNull(usersResource.get(authId), "User should be deleted from Keycloak")
    }

    @Test
    fun `deletes user preferences`() {
      insertUser(authId = authId)
      insertOrganization()

      userStore.updatePreferences(null, JSONB.valueOf("""{"key":"value"}"""))
      userStore.updatePreferences(organizationId, JSONB.valueOf("""{"key":"value"}"""))

      userStore.deleteSelf()

      val preferences = dslContext.selectFrom(USER_PREFERENCES).fetch()
      assertEquals(emptyList<UserPreferencesRecord>(), preferences, "Preferences")
    }

    @Test
    fun `does not delete user from database if Keycloak deletion fails`() {
      usersResource.simulateRequestFailures = true

      insertUser(
          authId = authId,
          email = userRepresentation.email,
          firstName = userRepresentation.firstName,
          lastName = userRepresentation.lastName)

      val usersRowBefore = usersDao.fetchOneById(user.userId)!!

      assertThrows<Exception> { userStore.deleteSelf() }

      val usersRowAfter = usersDao.fetchOneById(user.userId)

      assertEquals(usersRowBefore, usersRowAfter)
    }

    @Test
    fun `user is not searchable by email after deletion`() {
      insertUser(authId = authId, email = userRepresentation.email)

      userStore.deleteSelf()

      val updatedUser = usersDao.fetchOneById(user.userId)!!

      assertNull(
          userStore.fetchByEmail(userRepresentation.email),
          "Original email address should not be searchable")
      assertNull(
          userStore.fetchByEmail(updatedUser.email!!),
          "Dummy post-deletion email address should not be searchable")
    }
  }
}
