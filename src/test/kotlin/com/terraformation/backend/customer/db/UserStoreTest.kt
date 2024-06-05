package com.terraformation.backend.customer.db

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.auth.CredentialRepresentation
import com.terraformation.backend.auth.InMemoryKeycloakAdminClient
import com.terraformation.backend.auth.UserRepresentation
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
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.pojos.UserGlobalRolesRow
import com.terraformation.backend.db.default_schema.tables.records.UserPreferencesRecord
import com.terraformation.backend.db.default_schema.tables.references.USER_PREFERENCES
import com.terraformation.backend.dummyKeycloakInfo
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
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException

/**
 * Tests for the user store.
 *
 * This uses a test fixture instead of calling out to a real Keycloak server; see
 * [InMemoryKeycloakAdminClient].
 */
internal class UserStoreTest : DatabaseTest(), RunsAsUser {
  private val clock = TestClock()
  private val config: TerrawareServerConfig = mockk()
  private val objectMapper = jacksonObjectMapper()
  private val publisher = TestEventPublisher()
  private val keycloakAdminClient = InMemoryKeycloakAdminClient()
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
  private val authId = "authId"
  private val userRepresentation =
      UserRepresentation(
          attributes = mapOf("locale" to listOf("gx")),
          email = "email",
          firstName = "firstName",
          id = authId,
          lastName = "lastName",
          username = "email",
      )

  @BeforeEach
  fun setUp() {
    every { config.keycloak } returns keycloakConfig

    every { user.canAddOrganizationUser(organizationId) } returns true
    every { user.canCreateApiKey(organizationId) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canRemoveOrganizationUser(organizationId, any()) } returns true
    every { user.canSetOrganizationUserRole(organizationId, Role.Contributor) } returns true
    every { user.canReadGlobalRoles() } returns true
    every { user.canUpdateGlobalRoles() } returns true

    val engine = MockEngine {
      respond(content = responseContent, status = responseStatusCode, headers = responseHeaders)
    }

    httpClient = HttpClientConfig().httpClient(engine, objectMapper)

    keycloakAdminClient.create(userRepresentation)

    organizationStore = OrganizationStore(clock, dslContext, organizationsDao, publisher)
    parentStore = ParentStore(dslContext)
    permissionStore = PermissionStore(dslContext)

    userStore =
        UserStore(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            config,
            dslContext,
            httpClient,
            keycloakAdminClient,
            dummyKeycloakInfo(),
            organizationStore,
            parentStore,
            permissionStore,
            publisher,
            usersDao,
        )
  }

  @Test
  fun `fetchByAuthId returns existing user without touching Keycloak`() {
    val timeZone = ZoneId.of("Pacific/Honolulu")
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
    keycloakAdminClient.simulateRequestFailures = true

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
    keycloakAdminClient.simulateRequestFailures = true

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

  @Test
  fun `fetchWithGlobalRoles only returns users with global roles`() {
    insertUser(10)
    val userIdWithOneRole = insertUser(11)
    val userIdWithTwoRoles = insertUser(12)

    insertUserGlobalRole(userIdWithOneRole, GlobalRole.SuperAdmin)
    insertUserGlobalRole(userIdWithTwoRoles, GlobalRole.SuperAdmin)
    insertUserGlobalRole(userIdWithTwoRoles, GlobalRole.AcceleratorAdmin)

    assertEquals(
        listOf(userIdWithOneRole, userIdWithTwoRoles),
        userStore.fetchWithGlobalRoles().map { it.userId }.sortedBy { it.value },
        "IDs of users with global roles")
  }

  @Test
  fun `fetchWithGlobalRoles can search for specific global roles`() {
    val superAdminUser = insertUser(10)
    val acceleratorAdminUser = insertUser(11)
    val readOnlyUser = insertUser(12)

    insertUserGlobalRole(superAdminUser, GlobalRole.SuperAdmin)
    insertUserGlobalRole(acceleratorAdminUser, GlobalRole.AcceleratorAdmin)
    insertUserGlobalRole(readOnlyUser, GlobalRole.ReadOnly)

    assertEquals(
        setOf(acceleratorAdminUser, readOnlyUser),
        userStore
            .fetchWithGlobalRoles(setOf(GlobalRole.AcceleratorAdmin, GlobalRole.ReadOnly))
            .map { it.userId }
            .toSet(),
        "Users with specific global roles")
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

      val keycloakUser = keycloakAdminClient.get(newUser.authId)!!
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

      responseContent = ByteReadChannel("body")

      assertThrows<KeycloakRequestFailedException> { userStore.generateOfflineToken(user.userId) }

      // Expected behavior is that we have asked Keycloak to reset the user's password, then asked
      // it to remove the password.
      assertEquals(
          emptyList<CredentialRepresentation>(),
          keycloakAdminClient.credentials[user.authId],
          "Credentials should have been added then removed")
    }
  }

  @Test
  fun `updateUser updates profile information`() {
    val newCountryCode = "AR"
    val newFirstName = "Testy"
    val newLastName = "McTestalot"
    val newLanguage = Locale.forLanguageTag("gx")
    val newLocale = Locale.of(newLanguage.language, newCountryCode)
    val newTimeZone = ZoneId.of("Pacific/Honolulu")

    insertUser(authId = userRepresentation.id, email = userRepresentation.email)

    val oldEmail = userRepresentation.email
    val model = userStore.fetchByEmail(oldEmail)!!

    every { user.userId } returns model.userId

    val modelWithEdits =
        model.copy(
            countryCode = newCountryCode,
            email = "newemail@x.com",
            firstName = newFirstName,
            lastName = newLastName,
            emailNotificationsEnabled = true,
            locale = newLanguage,
            timeZone = newTimeZone,
        )
    userStore.updateUser(modelWithEdits)

    val updatedModel = userStore.fetchOneById(model.userId) as IndividualUser

    assertEquals(newCountryCode, updatedModel.countryCode, "Country code (DB)")
    assertEquals(oldEmail, updatedModel.email, "Email (DB)")
    assertEquals(newFirstName, updatedModel.firstName, "First name (DB)")
    assertEquals(newLastName, updatedModel.lastName, "Last name (DB)")
    assertEquals(newLocale, updatedModel.locale, "Locale (DB)")
    assertEquals(newTimeZone, updatedModel.timeZone, "Time zone (DB)")
    assertTrue(updatedModel.emailNotificationsEnabled, "Email notifications enabled (DB)")

    val updatedRepresentation = keycloakAdminClient.get(model.authId!!)
    assertEquals(oldEmail, updatedRepresentation?.email, "Email (Keycloak)")
    assertEquals(newFirstName, updatedRepresentation?.firstName, "First name (Keycloak)")
    assertEquals(newLastName, updatedRepresentation?.lastName, "Last name (Keycloak)")
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
      val otherUserId = insertUser(50)

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

      assertNull(keycloakAdminClient.get(authId), "User should be deleted from Keycloak")
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
      keycloakAdminClient.simulateRequestFailures = true

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

  @Nested
  inner class FetchByOrganizationId {
    @BeforeEach
    fun setUp() {
      insertUser()
      insertOrganization()
    }

    @Test
    fun `returns opted-in users`() {
      val otherOrganizationId = OrganizationId(2)

      val optedInNonMember =
          insertUser(100, email = "optedInNonMember@x.com", emailNotificationsEnabled = true)
      val optedInMember =
          insertUser(101, email = "optedInMember@x.com", emailNotificationsEnabled = true)
      val optedOutMember = insertUser(102, email = "optedOutMember@x.com")

      insertOrganization(otherOrganizationId)

      insertOrganizationUser(optedInNonMember, otherOrganizationId)
      insertOrganizationUser(optedInMember, organizationId)
      insertOrganizationUser(optedOutMember, organizationId)

      val expected = setOf("optedInMember@x.com")
      val actual = userStore.fetchByOrganizationId(organizationId).map { it.email }.toSet()

      assertEquals(expected, actual)
    }

    @Test
    fun `returns users with requested roles`() {
      val admin1 = insertUser(100, email = "admin1@x.com", emailNotificationsEnabled = true)
      val admin2 = insertUser(101, email = "admin2@x.com", emailNotificationsEnabled = true)
      val manager = insertUser(102, email = "manager@x.com", emailNotificationsEnabled = true)
      val owner = insertUser(103, email = "owner@x.com", emailNotificationsEnabled = true)

      insertOrganizationUser(admin1, role = Role.Admin)
      insertOrganizationUser(admin2, role = Role.Admin)
      insertOrganizationUser(manager, role = Role.Manager)
      insertOrganizationUser(owner, role = Role.Owner)

      val expected = setOf("admin1@x.com", "admin2@x.com", "owner@x.com")
      val actual =
          userStore
              .fetchByOrganizationId(organizationId, roles = setOf(Role.Owner, Role.Admin))
              .map { it.email }
              .toSet()

      assertEquals(expected, actual)
    }

    @Test
    fun `returns terraformation contact user if one exists`() {
      val tfContact =
          insertUser(100, email = "tfcontact@terraformation.com", emailNotificationsEnabled = true)

      insertOrganizationUser(tfContact, role = Role.TerraformationContact)
      assertEquals(
          userStore.getTerraformationContactUser(organizationId)?.email,
          "tfcontact@terraformation.com")
    }

    @Test
    fun `returns no terraformation contact user if one does not exist`() {
      assertNull(userStore.getTerraformationContactUser(organizationId))
    }
  }

  @Nested
  inner class UpdateGlobalRoles {
    @BeforeEach
    fun setUp() {
      every { user.canUpdateSpecificGlobalRoles(setOf(GlobalRole.SuperAdmin)) } returns true
    }

    @Test
    fun `overwrites existing global roles`() {
      val userId = insertUser(10)
      insertUserGlobalRole(userId, GlobalRole.AcceleratorAdmin)

      userStore.updateGlobalRoles(setOf(userId), setOf(GlobalRole.SuperAdmin))

      assertEquals(
          listOf(UserGlobalRolesRow(userId = userId, globalRoleId = GlobalRole.SuperAdmin)),
          userGlobalRolesDao.findAll())
    }

    @Test
    fun `overwrites existing global roles for a set of users`() {
      val userId1 = insertUser(10)
      val userId2 = insertUser(11)
      insertUserGlobalRole(userId1, GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(userId2, GlobalRole.TFExpert)

      userStore.updateGlobalRoles(setOf(userId1, userId2), setOf(GlobalRole.SuperAdmin))

      assertEquals(
          listOf(
              UserGlobalRolesRow(userId = userId1, globalRoleId = GlobalRole.SuperAdmin),
              UserGlobalRolesRow(userId = userId2, globalRoleId = GlobalRole.SuperAdmin)),
          userGlobalRolesDao.findAll())
    }

    @Test
    fun `can remove all global roles from a set of users`() {
      val userId1 = insertUser(10)
      val userId2 = insertUser(11)
      insertUserGlobalRole(userId1, GlobalRole.SuperAdmin)
      insertUserGlobalRole(userId2, GlobalRole.AcceleratorAdmin)

      userStore.updateGlobalRoles(setOf(userId1, userId2), emptySet())

      assertEquals(emptyList<Any>(), userGlobalRolesDao.findAll())
    }

    @Test
    fun `throws exception if one of the users being modified does not have a Terraformation email address`() {
      val userId1 = insertUser(10, email = "test@elsewhere.com")
      val userId2 = insertUser(11, email = "test@terraformation.com")

      assertThrows<AccessDeniedException> {
        userStore.updateGlobalRoles(setOf(userId1, userId2), emptySet())
      }
    }

    @Test
    fun `does not update any global roles if any of the given userIds are not able to be updated`() {
      val userId1 = insertUser(10, email = "test@elsewhere.com")
      val userId2 = insertUser(11, email = "test@terraformation.com")
      insertUserGlobalRole(userId2, GlobalRole.AcceleratorAdmin)

      assertThrows<AccessDeniedException> {
        userStore.updateGlobalRoles(setOf(userId1, userId2), emptySet())
      }

      assertEquals(
          listOf(UserGlobalRolesRow(userId = userId2, globalRoleId = GlobalRole.AcceleratorAdmin)),
          userGlobalRolesDao.findAll())
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canUpdateGlobalRoles() } returns false

      val userId = insertUser(10)

      assertThrows<AccessDeniedException> { userStore.updateGlobalRoles(setOf(userId), emptySet()) }
    }
  }
}
