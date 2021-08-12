package com.terraformation.backend.customer.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.KeycloakRequestFailedException
import com.terraformation.backend.db.KeycloakUserNotFoundException
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.UsersRow
import io.mockk.every
import io.mockk.mockk
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.UserRepresentation

/**
 * Tests for the user store.
 *
 * This uses a test fixture instead of calling out to a real Keycloak server; see
 * [InMemoryKeycloakUsersResource].
 */
internal class UserStoreTest : DatabaseTest(), RunsAsUser {
  private val clock: Clock = mockk()
  private val config: TerrawareServerConfig = mockk()
  private val realmResource: RealmResource = mockk()
  private val usersResource = InMemoryKeycloakUsersResource()
  override val user: UserModel = mockk()

  private lateinit var accessionsDao: AccessionsDao
  private lateinit var organizationsDao: OrganizationsDao
  private lateinit var organizationStore: OrganizationStore
  private lateinit var permissionStore: PermissionStore
  private lateinit var usersDao: UsersDao
  private lateinit var userStore: UserStore

  private val keycloakConfig =
      TerrawareServerConfig.KeycloakConfig(
          URI("http://keycloak"),
          realm = "realm",
          clientId = "clientId",
          clientSecret = "clientSecret",
      )

  private val authId = "authId"
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

    usersResource.create(userRepresentation)

    val configuration = dslContext.configuration()
    accessionsDao = AccessionsDao(configuration)
    organizationsDao = OrganizationsDao(configuration)
    usersDao = UsersDao(configuration)

    organizationStore = OrganizationStore(clock, dslContext, organizationsDao)
    permissionStore = PermissionStore(dslContext)

    userStore =
        UserStore(
            accessionsDao,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            permissionStore,
            realmResource,
            usersDao)
  }

  @Test
  fun `fetchByAuthId returns existing user without touching Keycloak`() {
    insertUser()

    val actual = userStore.fetchByAuthId(authId)

    assertEquals(authId, actual.authId)
  }

  @Test
  fun `fetchByAuthId fetches user information from Keycloak if not found locally`() {
    val user = userStore.fetchByAuthId(authId)

    assertEquals(userRepresentation.email, user.email)
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
    insertUser()

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

  private fun insertUser(
      representation: UserRepresentation = userRepresentation,
      userType: UserType = UserType.Individual
  ): UsersRow {
    val row =
        UsersRow(
            authId = representation.id,
            email = representation.email,
            firstName = representation.firstName,
            lastName = representation.lastName,
            userTypeId = userType,
            createdTime = clock.instant(),
            modifiedTime = clock.instant())

    usersDao.insert(row)

    return row
  }
}
