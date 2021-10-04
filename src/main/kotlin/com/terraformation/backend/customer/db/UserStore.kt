package com.terraformation.backend.customer.db

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.KeycloakRequestFailedException
import com.terraformation.backend.db.KeycloakUserNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.DevicesDao
import com.terraformation.backend.db.tables.daos.FeaturesDao
import com.terraformation.backend.db.tables.daos.LayersDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.UsersRow
import com.terraformation.backend.log.perClassLogger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Base64
import javax.annotation.ManagedBean
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import kotlin.random.Random
import org.apache.commons.codec.binary.Base32
import org.keycloak.adapters.springboot.KeycloakSpringBootProperties
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException

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
    private val accessionsDao: AccessionsDao,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val devicesDao: DevicesDao,
    private val featuresDao: FeaturesDao,
    private val httpClient: HttpClient,
    private val keycloakProperties: KeycloakSpringBootProperties,
    private val layersDao: LayersDao,
    private val objectMapper: ObjectMapper,
    private val organizationStore: OrganizationStore,
    private val permissionStore: PermissionStore,
    realmResource: RealmResource,
    private val usersDao: UsersDao,
) {
  private val log = perClassLogger()
  private val usersResource = realmResource.users()

  /**
   * Default Keycloak groups for each user type. Currently, we only auto-assign API client users to
   * a Keycloak group.
   */
  private val defaultKeycloakGroups =
      mapOf(UserType.APIClient to listOf(config.keycloak.apiClientGroupName))

  /**
   * Returns the details for the user with a given Keycloak user ID. Pulls the user's information
   * from Keycloak if they don't exist in our users table yet.
   *
   * @throws KeycloakRequestFailedException Could not request user information from Keycloak. This
   * implies that the user didn't exist in the users table.
   * @throws KeycloakUserNotFoundException There is no user with that ID in the Keycloak database.
   */
  fun fetchByAuthId(authId: String): UserModel {
    val existingUser = usersDao.fetchByAuthId(authId).firstOrNull()
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
            insertKeycloakUser(keycloakUser)
          } else {
            throw KeycloakUserNotFoundException("User ID does not exist")
          }
        }

    return rowToDetails(user)
  }

  /**
   * Returns the details for the user with a given email address. Pulls the user's information frmo
   * Keycloak if they don't exist in our users table yet.
   *
   * @return null if no Keycloak user has the requested email address.
   * @throws KeycloakRequestFailedException Could not request user information from Keycloak.
   */
  fun fetchByEmail(email: String): UserModel? {
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

    return user?.let { rowToDetails(it) }
  }

  /**
   * Returns the details for the user with a given user ID. This does not pull information from
   * Keycloak; it only works for users whose data was previously inserted into our users table.
   *
   * @return null if the user doesn't exist.
   */
  fun fetchById(userId: UserId): UserModel? {
    return usersDao.fetchOneById(userId)?.let { rowToDetails(it) }
  }

  /**
   * Creates a new user as a member of an organization. This registers them in Keycloak and also
   * adds them to the local database.
   */
  fun createUser(
      organizationId: OrganizationId,
      role: Role,
      email: String,
      firstName: String? = null,
      lastName: String? = null,
      redirectUrl: URI? = null,
      requireResetPassword: Boolean = true,
  ): UserModel {
    if (!currentUser().canAddOrganizationUser(organizationId)) {
      throw AccessDeniedException("No permission to add users to this organization")
    }

    val existingUser = fetchByEmail(email)
    if (existingUser != null) {
      if (organizationId in existingUser.organizationRoles) {
        throw DuplicateKeyException("User is already in the organization")
      }

      log.info("User $email already exists; adding to organization $organizationId")
      organizationStore.addUser(organizationId, existingUser.userId, role)
      return existingUser
    }

    log.info("Creating new user $email")

    val keycloakUser = registerKeycloakUser(email, firstName, lastName)
    val usersRow = insertKeycloakUser(keycloakUser)
    val user = rowToDetails(usersRow)

    organizationStore.addUser(organizationId, user.userId, role)

    val userResource =
        usersResource.get(keycloakUser.id)
            ?: throw IllegalStateException("Registered user with Keycloak but could not find them")

    if (requireResetPassword) {
      keycloakUser.requiredActions = listOf("UPDATE_PASSWORD")

      userResource.update(keycloakUser)

      if (redirectUrl != null) {
        userResource.executeActionsEmail(
            keycloakProperties.resource, "$redirectUrl", keycloakUser.requiredActions)
      } else {
        userResource.executeActionsEmail(keycloakUser.requiredActions)
      }
    }

    return user
  }

  /**
   * Creates a new API client user and registers it with Keycloak.
   *
   * We do a few things to make API client users easier to deal with in the Keycloak admin console.
   *
   * - The username has a prefix of `api-`
   * - The last name includes the organization ID
   * - The first name is the admin-supplied description
   */
  fun createApiClient(organizationId: OrganizationId, description: String?): UserModel {
    if (!currentUser().canCreateApiKey(organizationId)) {
      throw AccessDeniedException("No permission to create API keys in this organization")
    }

    // Use base32 instead of base64 so the username doesn't include "/" and "+".
    val randomString = Base32().encodeToString(Random.nextBytes(15)).lowercase()
    val username = "${config.keycloak.apiClientUsernamePrefix}$randomString"
    val lastName = "Organization $organizationId"

    val keycloakUser = registerKeycloakUser(username, description, lastName, UserType.APIClient)
    val usersRow = insertKeycloakUser(keycloakUser, UserType.APIClient)
    val user = rowToDetails(usersRow)

    organizationStore.addUser(organizationId, user.userId, Role.CONTRIBUTOR)

    return user
  }

  /** Deletes an API client user including its Keycloak registration. */
  fun deleteApiClient(userId: UserId): Boolean {
    val user = fetchById(userId) ?: return false

    if (user.userType != UserType.APIClient) {
      throw IllegalArgumentException("User is not an API client")
    }

    user.organizationRoles.keys.forEach { organizationId ->
      if (!currentUser().canDeleteApiKey(organizationId)) {
        throw AccessDeniedException("No permission to delete API keys in this organization")
      }
    }

    log.debug("Removing API client user $userId (${user.authId}) from Keycloak")

    val response = usersResource.delete(user.authId)

    if (response.statusInfo.family == Response.Status.Family.SUCCESSFUL) {
      log.info("Removed API client user $userId (${user.authId}) from Keycloak")
    } else if (response.status == Response.Status.NOT_FOUND.statusCode) {
      log.warn("API client user $userId (${user.authId}) in local database but not in Keycloak")
    } else {
      log.error(
          "Got unexpected HTTP status ${response.status} when deleting API client user $userId " +
              "(${user.authId}) from Keycloak")
      throw KeycloakRequestFailedException("Failed to delete user from Keycloak")
    }

    // For now, completely delete the user from our database. We will likely need to revisit this
    // once we have more objects in the system that are owned by or otherwise associated with
    // specific users.
    user.organizationRoles.keys.forEach { organizationId ->
      organizationStore.removeUser(organizationId, userId)
    }

    usersDao.deleteById(userId)

    return true
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
      type: UserType = UserType.Individual
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
   * Generates a new offline token for the API client user with a given user ID. This revokes any
   * previously existing offline tokens.
   *
   * Note that this also resets the user's password! API client users can't log in using passwords
   * so there is no harm done to them, but you can't use this on an individual user.
   *
   * This may fail if it is called concurrently for the same user ID; if none of the current calls
   * succeeds, the user's old offline token will continue to work.
   */
  fun generateOfflineToken(userId: UserId): String {
    val usersRow =
        usersDao.fetchOneById(userId) ?: throw IllegalArgumentException("User does not exist")
    val authId = usersRow.authId ?: throw IllegalStateException("User has no authentication ID")

    if (usersRow.userTypeId != UserType.APIClient) {
      throw IllegalArgumentException("Offline tokens may only be generated for API clients")
    }

    val user =
        usersResource.get(authId)
            ?: throw KeycloakUserNotFoundException("Keycloak could not find user")

    // Reset the user's password, so we can use it to authenticate to Keycloak and request a new
    // offline token. There is no administrative Keycloak API to do that on behalf of a user.
    // When we're done, we will remove the password. Use a long random password so that even if
    // this process bombs out, an attacker won't be able to guess the password.
    val credentials = randomPasswordCredential()

    user.resetPassword(credentials)

    try {
      val authUrl = URI(keycloakProperties.authServerUrl)
      val tokenUrl =
          authUrl.resolve("/auth/realms/${keycloakProperties.realm}/protocol/openid-connect/token")

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
      user.credentials().filter { it.type == "password" }.forEach { credential ->
        try {
          user.removeCredential(credential.id)
        } catch (e: Exception) {
          log.error(
              "Failed to remove temporary password from API client user $userId " +
                  "(${user.toRepresentation().id}) after generating token",
              e)

          // But return the token anyway; it should be usable and a long random password sticking
          // around afterwards should be harmless.
        }
      }
    }
  }

  private fun randomPasswordCredential(): CredentialRepresentation {
    return CredentialRepresentation().apply {
      isTemporary = false
      type = "password"
      value = Base64.getEncoder().encodeToString(Random.nextBytes(40))
    }
  }

  private fun rowToDetails(usersRow: UsersRow): UserModel {
    return UserModel(
        usersRow.id ?: throw IllegalArgumentException("User ID should never be null"),
        usersRow.authId ?: throw IllegalArgumentException("Auth ID should never be null"),
        usersRow.email ?: throw IllegalArgumentException("Email should never be null"),
        usersRow.firstName,
        usersRow.lastName,
        usersRow.userTypeId ?: throw IllegalArgumentException("User type should never be null"),
        accessionsDao,
        devicesDao,
        featuresDao,
        layersDao,
        permissionStore,
    )
  }

  private fun insertKeycloakUser(
      keycloakUser: UserRepresentation,
      type: UserType = UserType.Individual
  ): UsersRow {
    val usersRow =
        UsersRow(
            authId = keycloakUser.id,
            email = keycloakUser.email,
            firstName = keycloakUser.firstName,
            lastName = keycloakUser.lastName,
            userTypeId = type,
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
        )

    usersDao.insert(usersRow)
    return usersRow
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
