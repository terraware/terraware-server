package com.terraformation.backend.customer.db

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.DeviceManagerUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.KeycloakRequestFailedException
import com.terraformation.backend.db.KeycloakUserNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.UsersRow
import com.terraformation.backend.db.tables.references.USERS
import com.terraformation.backend.log.perClassLogger
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.annotation.ManagedBean
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import kotlin.random.Random
import org.apache.commons.codec.binary.Base32
import org.jooq.DSLContext
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.context.event.EventListener
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.context.support.ServletRequestHandledEvent

/**
 * Data accessor for user information.
 *
 * If you just want to get the details for the currently logged-in user, call [currentUser]; you
 * don't need this class for that. Use this class if you want to access information about other
 * users.
 *
 * This class is a little different from some of the other Store classes because of the interaction
 * between terraware-server and Keycloak. When a new user registers with Keycloak and accesses
 * terraware-server for the first time, we automatically insert a row into the users table for them,
 * and pre-populate it with the profile information they gave to Keycloak when they signed up.
 * Fetching a user's details can thus result in an API call to the Keycloak server.
 *
 * Spring Security calls this class to look up users when it is authenticating requests.
 */
@ManagedBean
class UserStore(
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val httpClient: HttpClient,
    private val keycloakInfo: KeycloakInfo,
    private val objectMapper: ObjectMapper,
    private val organizationStore: OrganizationStore,
    private val parentStore: ParentStore,
    private val permissionStore: PermissionStore,
    realmResource: RealmResource,
    private val usersDao: UsersDao,
) {
  private val log = perClassLogger()
  private val usersResource = realmResource.users()

  /**
   * Default Keycloak groups for each user type. Currently, we only auto-assign device manager users
   * to a Keycloak group.
   */
  private val defaultKeycloakGroups =
      mapOf(UserType.DeviceManager to listOf(config.keycloak.apiClientGroupName))

  /**
   * Returns the details for the user with a given Keycloak user ID. Pulls the user's information
   * from Keycloak if they don't exist in our users table yet.
   *
   * @throws KeycloakRequestFailedException Could not request user information from Keycloak. This
   * implies that the user didn't exist in the users table.
   * @throws KeycloakUserNotFoundException There is no user with that ID in the Keycloak database.
   */
  fun fetchByAuthId(authId: String): TerrawareUser {
    val existingUser = usersDao.fetchOneByAuthId(authId)
    val user =
        if (existingUser != null) {
          existingUser
        } else {
          val keycloakUser =
              try {
                usersResource.get(authId)?.toRepresentation()
              } catch (e: Exception) {
                throw KeycloakRequestFailedException("Failed to request user data from Keycloak", e)
              }

          if (keycloakUser != null) {
            try {
              insertKeycloakUser(keycloakUser)
            } catch (e: DuplicateKeyException) {
              // If the client authenticates for the first time and then sends two API requests in
              // parallel, both requests might try to create the user locally. Only one will
              // succeed thanks to the unique constraint on users.auth_id, but at that point the
              // user should exist and be readable from the other request handler thread.

              log.debug("Got DuplicateKeyException when inserting Keycloak user")
              usersDao.fetchOneByAuthId(authId) ?: throw e
            }
          } else {
            throw KeycloakUserNotFoundException("User ID does not exist")
          }
        }

    return rowToModel(user)
  }

  /**
   * Returns the details for the user with a given email address. Pulls the user's information from
   * Keycloak if they don't exist in our users table yet.
   *
   * @return null if no Keycloak user has the requested email address.
   * @throws KeycloakRequestFailedException Could not request user information from Keycloak.
   */
  fun fetchByEmail(email: String): IndividualUser? {
    val existingUser = usersDao.fetchByEmail(email).firstOrNull()
    val user =
        if (existingUser != null) {
          existingUser
        } else {
          val keycloakUsers =
              try {
                usersResource.search(email, true)
              } catch (e: Exception) {
                throw KeycloakRequestFailedException(
                    "Failed to search for user data in Keycloak", e)
              }
          if (keycloakUsers.isNotEmpty()) {
            insertKeycloakUser(keycloakUsers.first())
          } else {
            null
          }
        }

    return user?.let { rowToIndividualUser(it) }
  }

  /**
   * Returns the details for the user with a given user ID. This does not pull information from
   * Keycloak; it only works for users whose data was previously inserted into our users table.
   */
  fun fetchOneById(userId: UserId): TerrawareUser {
    return usersDao.fetchOneById(userId)?.let { rowToModel(it) }
        ?: throw UserNotFoundException(userId)
  }

  /**
   * Fetches the user with a particular email address, or creates one without any authentication
   * information. This is used when inviting users who don't have accounts yet. When they register
   * on Keycloak, their Keycloak identity will be linked to the user that's created here.
   */
  fun fetchOrCreateByEmail(email: String): IndividualUser {
    val existingUser = fetchByEmail(email)
    if (existingUser != null) {
      return existingUser
    }

    val row =
        UsersRow(
            createdTime = clock.instant(),
            email = email,
            emailNotificationsEnabled = false,
            modifiedTime = clock.instant(),
            userTypeId = UserType.Individual)

    usersDao.insert(row)

    log.info("Created unregistered user ${row.id} for email $email")

    return rowToIndividualUser(row)
  }

  /**
   * Updates a user's profile information. Applies changes to the `users` table as well as Keycloak.
   * Currently, only the first and last name can be modified.
   */
  fun updateUser(model: IndividualUser) {
    if (currentUser().userId != model.userId) {
      throw AccessDeniedException("Cannot modify another user's profile information")
    }

    val usersRow =
        usersDao.fetchOneById(model.userId)
            ?: throw IllegalStateException("Current user not found in users table")

    dslContext.transaction { _ ->
      usersDao.update(
          usersRow.copy(
              emailNotificationsEnabled = model.emailNotificationsEnabled,
              firstName = model.firstName,
              lastName = model.lastName))

      try {
        val keycloakUser = usersResource.get(usersRow.authId)
        val representation = keycloakUser.toRepresentation()

        representation.firstName = model.firstName
        representation.lastName = model.lastName

        keycloakUser.update(representation)
      } catch (e: Exception) {
        throw KeycloakRequestFailedException("Failed to update user data in Keycloak", e)
      }
    }
  }

  /**
   * Creates a new device manager user and registers it with Keycloak.
   *
   * We do a few things to make device manager users easier to deal with in the Keycloak admin
   * console.
   *
   * - The username has a prefix of `api-`
   * - The last name includes the organization ID
   * - The first name is the admin-supplied description
   */
  fun createDeviceManagerUser(
      organizationId: OrganizationId,
      description: String?
  ): DeviceManagerUser {
    requirePermissions { createApiKey(organizationId) }

    // Use base32 instead of base64 so the username doesn't include "/" and "+".
    val randomString = Base32().encodeToString(Random.nextBytes(15)).lowercase()
    val username = "${config.keycloak.apiClientUsernamePrefix}$randomString"
    val lastName = "Organization $organizationId"

    val keycloakUser = registerKeycloakUser(username, description, lastName, UserType.DeviceManager)
    val usersRow = insertKeycloakUser(keycloakUser, UserType.DeviceManager)
    val userId = usersRow.id ?: throw IllegalStateException("User ID must be non-null")

    organizationStore.addUser(organizationId, userId, Role.CONTRIBUTOR)

    return rowToDeviceManagerUser(usersRow)
  }

  /**
   * Registers a user in Keycloak and returns its representation.
   *
   * @throws DuplicateKeyException There was already a user with the requested email address in
   * Keycloak.
   * @throws KeycloakRequestFailedException Unable to complete registration request.
   */
  private fun registerKeycloakUser(
      username: String,
      firstName: String?,
      lastName: String?,
      type: UserType = UserType.Individual,
  ): UserRepresentation {
    val newKeycloakUser = UserRepresentation()
    newKeycloakUser.isEmailVerified = true
    newKeycloakUser.isEnabled = true
    newKeycloakUser.email = username
    newKeycloakUser.firstName = firstName
    newKeycloakUser.groups = defaultKeycloakGroups[type]
    newKeycloakUser.lastName = lastName
    newKeycloakUser.username = username

    log.debug("Creating user $username in Keycloak")

    val response = usersResource.create(newKeycloakUser)

    if (response.status == HttpStatus.CONFLICT.value()) {
      throw DuplicateKeyException("User already registered")
    } else if (response.statusInfo.family != Response.Status.Family.SUCCESSFUL) {
      val responseBody = response.readEntity(String::class.java)
      log.error(
          "Failed to create user $username in Keycloak: HTTP ${response.status} $responseBody")
      throw KeycloakRequestFailedException("User creation failed")
    }

    log.info("Created user $username in Keycloak")

    val keycloakUser = usersResource.search(username, true).firstOrNull()
    if (keycloakUser == null) {
      log.error("Created Keycloak user $username but failed to find them immediately afterwards")
      throw KeycloakUserNotFoundException("User creation succeeded but couldn't find user!")
    }

    return keycloakUser
  }

  /**
   * Generates a new offline token for the device manager user with a given user ID. This revokes
   * any previously existing offline tokens.
   *
   * Note that this also resets the user's password! Device manager users can't log in using
   * passwords so there is no harm done to them, but you can't use this on an individual user.
   *
   * This may fail if it is called concurrently for the same user ID; if none of the current calls
   * succeeds, the user's old offline token will continue to work.
   */
  fun generateOfflineToken(userId: UserId): String {
    val usersRow =
        usersDao.fetchOneById(userId) ?: throw IllegalArgumentException("User does not exist")
    val authId = usersRow.authId ?: throw IllegalStateException("User has no authentication ID")

    if (usersRow.userTypeId != UserType.DeviceManager) {
      throw IllegalArgumentException("Offline tokens may only be generated for device managers")
    }

    val user = usersResource.get(authId)

    // Reset the user's password, so we can use it to authenticate to Keycloak and request a new
    // offline token. There is no administrative Keycloak API to do that on behalf of a user.
    // When we're done, we will remove the password. Use a long random password so that even if
    // this process bombs out, an attacker won't be able to guess the password.
    val credentials = randomPasswordCredential()

    user.resetPassword(credentials)

    try {
      val tokenUrl = keycloakInfo.realmBaseUrl.resolve("protocol/openid-connect/token")

      val formSubmission =
          mapOf(
                  "client_id" to config.keycloak.apiClientId,
                  "scope" to "offline_access",
                  "grant_type" to "password",
                  "username" to user.toRepresentation().username,
                  "password" to credentials.value)
              .map { (name, value) -> name to URLEncoder.encode(value, StandardCharsets.UTF_8) }
              .joinToString("&") { (name, value) -> "$name=$value" }

      val request =
          HttpRequest.newBuilder()
              .uri(tokenUrl)
              .header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED)
              .POST(HttpRequest.BodyPublishers.ofString(formSubmission))
              .build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() != HttpStatus.OK.value()) {
        log.error("Keycloak returned HTTP ${response.statusCode()} for refresh token request")
        log.info("Keycloak response: ${response.body()}")
        throw KeycloakRequestFailedException("Failed to generate refresh token")
      }

      val tokenResponsePayload =
          try {
            objectMapper.readValue<OpenIdConnectTokenResponsePayload>(response.body())
          } catch (e: JsonProcessingException) {
            log.error("Keycloak returned malformed response to refresh token request", e)
            log.info("Keycloak response: ${response.body()}")
            throw KeycloakRequestFailedException("Failed to generate refresh token")
          }

      return tokenResponsePayload.refresh_token
    } finally {
      user
          .credentials()
          .filter { it.type == "password" }
          .forEach { credential ->
            try {
              user.removeCredential(credential.id)
            } catch (e: Exception) {
              log.error(
                  "Failed to remove temporary password from device manager user $userId " +
                      "(${user.toRepresentation().id}) after generating token",
                  e)

              // But return the token anyway; it should be usable and a long random password
              // sticking around afterwards should be harmless.
            }
          }
    }
  }

  /**
   * Updates a user's last activity time when an API request is processed.
   *
   * @param[event] Event published by Spring's request-handling layer after each request. It
   * includes a `userName` field which in our case is set to the user's authentication ID; for
   * unauthenticated requests, that field is null and this method is a no-op.
   */
  @EventListener
  fun updateLastActivityTime(event: ServletRequestHandledEvent) {
    event.userName?.let { authId ->
      dslContext
          .update(USERS)
          .set(USERS.LAST_ACTIVITY_TIME, Instant.ofEpochMilli(event.timestamp))
          .where(USERS.AUTH_ID.eq(authId))
          .execute()
    }
  }

  private fun randomPasswordCredential(): CredentialRepresentation {
    return CredentialRepresentation().apply {
      isTemporary = false
      type = "password"
      value = Base64.getEncoder().encodeToString(Random.nextBytes(40))
    }
  }

  private fun rowToIndividualUser(usersRow: UsersRow): IndividualUser {
    return IndividualUser(
        usersRow.id ?: throw IllegalArgumentException("User ID should never be null"),
        usersRow.authId,
        usersRow.email ?: throw IllegalArgumentException("Email should never be null"),
        usersRow.emailNotificationsEnabled
            ?: throw IllegalArgumentException("Email notifications enabled should never be null"),
        usersRow.firstName,
        usersRow.lastName,
        usersRow.userTypeId ?: throw IllegalArgumentException("User type should never be null"),
        parentStore,
        permissionStore,
    )
  }

  private fun rowToDeviceManagerUser(usersRow: UsersRow): DeviceManagerUser {
    return DeviceManagerUser(
        usersRow.id ?: throw IllegalArgumentException("User ID should never be null"),
        usersRow.authId ?: throw IllegalArgumentException("Auth ID should never be null"),
        parentStore,
        permissionStore)
  }

  private fun rowToModel(user: UsersRow): TerrawareUser {
    return if (user.userTypeId == UserType.DeviceManager) {
      rowToDeviceManagerUser(user)
    } else {
      rowToIndividualUser(user)
    }
  }

  private fun insertKeycloakUser(
      keycloakUser: UserRepresentation,
      type: UserType = UserType.Individual
  ): UsersRow {
    val existingUser = usersDao.fetchByEmail(keycloakUser.email).firstOrNull()

    return if (existingUser != null && existingUser.authId == null) {
      log.info("Pending user ${existingUser.id} has registered with auth ID ${keycloakUser.id}")

      val updatedRow =
          existingUser.copy(
              authId = keycloakUser.id,
              firstName = keycloakUser.firstName,
              lastName = keycloakUser.lastName,
              modifiedTime = clock.instant())

      usersDao.update(updatedRow)
      updatedRow
    } else {
      val usersRow =
          UsersRow(
              authId = keycloakUser.id,
              email = keycloakUser.email,
              emailNotificationsEnabled = false,
              firstName = keycloakUser.firstName,
              lastName = keycloakUser.lastName,
              userTypeId = type,
              createdTime = clock.instant(),
              modifiedTime = clock.instant(),
          )

      usersDao.insert(usersRow)

      log.info("New user ${usersRow.id} has registered with auth ID ${keycloakUser.id}")

      usersRow
    }
  }

  /** Relevant parts of the payload of a valid response to an OpenID Connect token request. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  data class OpenIdConnectTokenResponsePayload(val refresh_token: String)

  companion object {
    @Suppress("unused")
    private fun dummyFunctionToImportSymbolsReferredToInComments() {
      currentUser()
    }
  }
}
