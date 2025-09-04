package com.terraformation.backend.customer.db

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.terraformation.backend.auth.KeycloakAdminClient
import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.auth.UserRepresentation
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.DeviceManagerUser
import com.terraformation.backend.customer.model.FunderUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.KeycloakRequestFailedException
import com.terraformation.backend.db.KeycloakUserNotFoundException
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.UsersDao
import com.terraformation.backend.db.default_schema.tables.pojos.UsersRow
import com.terraformation.backend.db.default_schema.tables.records.UserGlobalRolesRecord
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.default_schema.tables.references.USER_GLOBAL_ROLES
import com.terraformation.backend.db.default_schema.tables.references.USER_PREFERENCES
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.db.funder.tables.references.FUNDING_ENTITY_USERS
import com.terraformation.backend.log.perClassLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.serialization.JsonConvertException
import jakarta.inject.Named
import jakarta.ws.rs.core.Response
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.apache.commons.codec.binary.Base32
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.context.support.ServletRequestHandledEvent
import org.springframework.web.util.UriComponentsBuilder

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
@Named
class UserStore(
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val httpClient: HttpClient,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val keycloakInfo: KeycloakInfo,
    private val organizationStore: OrganizationStore,
    private val parentStore: ParentStore,
    private val permissionStore: PermissionStore,
    private val publisher: ApplicationEventPublisher,
    private val usersDao: UsersDao,
) {
  private val log = perClassLogger()

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
   *   implies that the user didn't exist in the users table.
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
                keycloakAdminClient.get(authId)
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
   * @param email Email address to search for. If the address has upper-case characters, they will
   *   be folded to lower case.
   * @return null if no Keycloak user has the requested email address.
   * @throws KeycloakRequestFailedException Could not request user information from Keycloak.
   */
  fun fetchByEmail(email: String): IndividualUser? {
    // Keycloak folds email addresses to lower case, so we need to do the same or we'll end up
    // with mismatched user data on the two sides.
    val lowerCaseEmail = email.lowercase()

    val existingUser = usersDao.fetchByEmail(lowerCaseEmail).firstOrNull()

    // Deleted users have invalid email addresses that should never match legitimate calls to this
    // method, but if a caller somehow passes in one of those values, we still don't want to treat
    // it as a match.
    if (existingUser?.deletedTime != null) {
      return null
    }

    val user =
        if (existingUser != null) {
          existingUser
        } else {
          val keycloakUsers =
              try {
                keycloakAdminClient.searchByEmail(lowerCaseEmail, true)
              } catch (e: Exception) {
                throw KeycloakRequestFailedException(
                    "Failed to search for user data in Keycloak",
                    e,
                )
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
   * Returns the row for the user with a given email address.
   *
   * @param email Email address to search for. If the address has upper-case characters, they will
   *   be folded to lower case.
   * @return null if no user has the requested email address, or if users that do have been deleted.
   */
  fun fetchTerrawareUserByEmail(email: String): TerrawareUser? {
    val user = usersDao.fetchByEmail(email.lowercase()).firstOrNull()

    // Deleted users have invalid email addresses that should never match legitimate calls to this
    // method, but if a caller somehow passes in one of those values, we still don't want to treat
    // it as a match.
    if (user?.deletedTime != null) {
      return null
    }

    return user?.let { rowToModel(user) }
  }

  /**
   * Returns the details for the user with a given email address, if they have permission to do so.
   */
  fun fetchByEmailAccelerator(email: String): IndividualUser? {
    val user = fetchByEmail(email)
    if (user != null) {
      requirePermissions { readUser(user.userId) }
    }
    return user
  }

  /**
   * Returns the members of an organization.
   *
   * @param requireOptIn Only return the users who are opted into email notifications.
   * @param roles If nonnull, only return the users with the specified roles in the organization.
   */
  fun fetchByOrganizationId(
      organizationId: OrganizationId,
      requireOptIn: Boolean = true,
      roles: Set<Role>? = null,
  ): List<IndividualUser> {
    if (roles?.isEmpty() == true) {
      return emptyList()
    }

    return dslContext
        .select(USERS.asterisk())
        .from(USERS)
        .join(ORGANIZATION_USERS)
        .on(USERS.ID.eq(ORGANIZATION_USERS.USER_ID))
        .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
        .apply { if (roles != null) and(ORGANIZATION_USERS.ROLE_ID.`in`(roles)) }
        .apply { if (requireOptIn) and(USERS.EMAIL_NOTIFICATIONS_ENABLED.isTrue) }
        .fetchInto(UsersRow::class.java)
        .map { rowToIndividualUser(it) }
  }

  /** Returns the funders of a funding entity. */
  fun fetchByFundingEntityId(fundingEntityId: FundingEntityId): List<FunderUser> {
    return dslContext
        .select(USERS.asterisk())
        .from(USERS)
        .join(FUNDING_ENTITY_USERS)
        .on(USERS.ID.eq(FUNDING_ENTITY_USERS.USER_ID))
        .where(FUNDING_ENTITY_USERS.FUNDING_ENTITY_ID.eq(fundingEntityId))
        .fetchInto(UsersRow::class.java)
        .map { rowToFunderUser(it) }
  }

  /**
   * Returns all users.
   *
   * @param requireOptIn Only return the users who are opted into email notifications.
   */
  fun fetchUsers(requireOptIn: Boolean = true): List<IndividualUser> {

    return dslContext
        .select(USERS.asterisk())
        .from(USERS)
        .where(USERS.USER_TYPE_ID.eq(UserType.Individual))
        .and(USERS.DELETED_TIME.isNull)
        .apply { if (requireOptIn) and(USERS.EMAIL_NOTIFICATIONS_ENABLED.isTrue) }
        .fetchInto(UsersRow::class.java)
        .map { rowToIndividualUser(it) }
  }

  /**
   * Returns the users who have global roles. If [roles] is non-null, only returns users with those
   * roles.
   */
  fun fetchWithGlobalRoles(
      roles: Collection<GlobalRole>? = null,
      additionalCondition: Condition? = null,
  ): List<IndividualUser> {
    requirePermissions { readGlobalRoles() }

    val globalRoleConditions =
        listOfNotNull(
            USER_GLOBAL_ROLES.USER_ID.eq(USERS.ID),
            roles?.let { USER_GLOBAL_ROLES.GLOBAL_ROLE_ID.`in`(it) },
        )

    val conditions =
        listOfNotNull(
            DSL.exists(DSL.selectOne().from(USER_GLOBAL_ROLES).where(globalRoleConditions)),
            additionalCondition,
        )

    return dslContext
        .select(USERS.asterisk())
        .from(USERS)
        .where(conditions)
        .orderBy(DSL.lower(USERS.EMAIL))
        .fetchInto(UsersRow::class.java)
        .map { rowToIndividualUser(it) }
  }

  /**
   * Returns the details for the user with a given user ID. This does not pull information from
   * Keycloak; it only works for users whose data was previously inserted into our users table.
   */
  fun fetchOneById(userId: UserId): TerrawareUser {
    return usersDao.fetchOneById(userId)?.let { rowToModel(it) }
        ?: throw UserNotFoundException(userId)
  }

  fun fetchManyById(userIds: Collection<UserId>): List<TerrawareUser> {
    return usersDao.fetchById(*userIds.toTypedArray()).map { rowToModel(it) }
  }

  /** Returns the details for the user with a given user ID, if they have permission to do so. */
  fun fetchOneByIdAccelerator(userId: UserId): TerrawareUser {
    requirePermissions { readUser(userId) }
    return fetchOneById(userId)
  }

  /** Returns a user's full name, or null if the user has no name. */
  fun fetchFullNameById(userId: UserId): String? {
    return (fetchOneById(userId) as? IndividualUser)?.fullName
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
            email = email.lowercase(),
            emailNotificationsEnabled = false,
            modifiedTime = clock.instant(),
            userTypeId = UserType.Individual,
        )

    usersDao.insert(row)

    log.info("Created unregistered user ${row.id} for email $email")

    return rowToIndividualUser(row)
  }

  /**
   * Updates a user's profile information. Applies changes to the `users` table as well as Keycloak.
   * Currently, only the first and last name can be modified.
   */
  fun updateUser(model: TerrawareUser) {
    if (currentUser().userId != model.userId) {
      throw AccessDeniedException("Cannot modify another user's profile information")
    }

    val usersRow =
        usersDao.fetchOneById(model.userId)
            ?: throw IllegalStateException("Current user not found in users table")

    // If the country code is set, include it in the locale.
    val newLocale = model.locale?.let { Locale.of(it.language, model.countryCode ?: "") }

    // Retain existing cookie consent if the model doesn't include it.
    val newCookiesConsented = model.cookiesConsented ?: usersRow.cookiesConsented
    val newCookiesConsentedTime = model.cookiesConsentedTime ?: usersRow.cookiesConsentedTime

    dslContext.transaction { _ ->
      usersDao.update(
          usersRow.copy(
              cookiesConsented = newCookiesConsented,
              cookiesConsentedTime = newCookiesConsentedTime,
              countryCode = model.countryCode,
              emailNotificationsEnabled = model.emailNotificationsEnabled,
              firstName = model.firstName,
              lastName = model.lastName,
              locale = newLocale,
              timeZone = model.timeZone,
          )
      )

      try {
        val representation =
            keycloakAdminClient.get(usersRow.authId!!)
                ?: throw KeycloakUserNotFoundException("Current user not found in Keycloak")

        representation.firstName = model.firstName
        representation.lastName = model.lastName

        keycloakAdminClient.update(representation)
      } catch (e: KeycloakUserNotFoundException) {
        throw e
      } catch (e: Exception) {
        throw KeycloakRequestFailedException("Failed to update user data in Keycloak", e)
      }
    }
  }

  fun fetchPreferences(organizationId: OrganizationId?): JSONB? {
    requirePermissions { organizationId?.let { readOrganization(it) } }

    val organizationIdCondition =
        if (organizationId != null) {
          USER_PREFERENCES.ORGANIZATION_ID.eq(organizationId)
        } else {
          USER_PREFERENCES.ORGANIZATION_ID.isNull
        }

    return dslContext
        .select(USER_PREFERENCES.PREFERENCES)
        .from(USER_PREFERENCES)
        .where(USER_PREFERENCES.USER_ID.eq(currentUser().userId))
        .and(organizationIdCondition)
        .fetchOne(USER_PREFERENCES.PREFERENCES)
  }

  fun updatePreferences(organizationId: OrganizationId?, preferences: JSONB) {
    requirePermissions { organizationId?.let { readOrganization(it) } }

    dslContext.transaction { _ ->
      dslContext
          .insertInto(USER_PREFERENCES)
          .set(USER_PREFERENCES.USER_ID, currentUser().userId)
          .set(USER_PREFERENCES.ORGANIZATION_ID, organizationId)
          .set(USER_PREFERENCES.PREFERENCES, preferences)
          .run {
            // We have separate unique indexes for per-user and per-user-per-organization
            // preferences, and the ON CONFLICT clause needs to refer to the correct one.
            if (organizationId != null) {
              onConflict(USER_PREFERENCES.USER_ID, USER_PREFERENCES.ORGANIZATION_ID)
                  .where(USER_PREFERENCES.ORGANIZATION_ID.isNotNull)
            } else {
              onConflict(USER_PREFERENCES.USER_ID).where(USER_PREFERENCES.ORGANIZATION_ID.isNull)
            }
          }
          .doUpdate()
          .set(USER_PREFERENCES.PREFERENCES, preferences)
          .execute()
    }
  }

  fun updateGlobalRoles(userIds: Set<UserId>, roles: Set<GlobalRole>) {
    if (roles.isEmpty()) {
      requirePermissions { updateGlobalRoles() }
    } else {
      requirePermissions { updateSpecificGlobalRoles(roles) }
    }

    userIds.forEach {
      val user = fetchOneById(it)
      if (
          user !is IndividualUser || !user.email.endsWith("@terraformation.com", ignoreCase = true)
      ) {
        throw AccessDeniedException("Only Terraformation users may have global roles")
      }
    }

    dslContext.transaction { _ ->
      dslContext
          .deleteFrom(USER_GLOBAL_ROLES)
          .where(USER_GLOBAL_ROLES.USER_ID.`in`(userIds))
          .execute()

      if (roles.isNotEmpty()) {
        val records =
            userIds.flatMap { userId ->
              roles.map { UserGlobalRolesRecord(userId = userId, globalRoleId = it) }
            }

        dslContext.insertInto(USER_GLOBAL_ROLES).set(records).execute()
      }
    }
  }

  /**
   * Creates a new device manager user and registers it with Keycloak.
   *
   * We do a few things to make device manager users easier to deal with in the Keycloak admin
   * console.
   * - The username has a prefix of `api-`
   * - The last name includes the organization ID
   * - The first name is the admin-supplied description
   */
  fun createDeviceManagerUser(
      organizationId: OrganizationId,
      description: String?,
  ): DeviceManagerUser {
    requirePermissions { createApiKey(organizationId) }

    // Use base32 instead of base64 so the username doesn't include "/" and "+".
    val randomString = Base32().encodeToString(Random.nextBytes(15)).lowercase()
    val username = "${config.keycloak.apiClientUsernamePrefix}$randomString"
    val lastName = "Organization $organizationId"

    val keycloakUser = registerKeycloakUser(username, description, lastName, UserType.DeviceManager)
    val usersRow = insertKeycloakUser(keycloakUser, UserType.DeviceManager)
    val userId = usersRow.id ?: throw IllegalStateException("User ID must be non-null")

    organizationStore.addUser(organizationId, userId, Role.Contributor)

    return rowToDeviceManagerUser(usersRow)
  }

  /**
   * Creates a new funder user by email.
   *
   * The user is not registered in Keycloak yet because they have to go through their own
   * registration via email invite.
   */
  fun createFunderUser(
      email: String,
  ): FunderUser {
    val usersRow =
        UsersRow(
            email = email.lowercase(),
            userTypeId = UserType.Funder,
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
        )
    usersDao.insert(usersRow)

    return rowToFunderUser(usersRow)
  }

  /**
   * Registers a user in Keycloak and returns its representation.
   *
   * @throws DuplicateKeyException There was already a user with the requested email address in
   *   Keycloak.
   * @throws KeycloakRequestFailedException Unable to complete registration request.
   */
  private fun registerKeycloakUser(
      username: String,
      firstName: String?,
      lastName: String?,
      type: UserType = UserType.Individual,
  ): UserRepresentation {
    val newKeycloakUser =
        UserRepresentation(
            email = username,
            firstName = firstName,
            groups = defaultKeycloakGroups[type],
            emailVerified = true,
            enabled = true,
            lastName = lastName,
            username = username,
        )

    log.debug("Creating user $username in Keycloak")

    val response = keycloakAdminClient.create(newKeycloakUser)

    if (response.status == HttpStatus.CONFLICT.value()) {
      throw DuplicateKeyException("User already registered")
    } else if (response.statusInfo.family != Response.Status.Family.SUCCESSFUL) {
      val responseBody = response.readEntity(String::class.java)
      log.error(
          "Failed to create user $username in Keycloak: HTTP ${response.status} $responseBody"
      )
      throw KeycloakRequestFailedException("User creation failed")
    }

    log.info("Created user $username in Keycloak")

    val keycloakUser = keycloakAdminClient.searchByEmail(username, true).firstOrNull()
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

    val user =
        keycloakAdminClient.get(authId)
            ?: throw KeycloakUserNotFoundException("Device manager user does not exist in Keycloak")

    // Reset the user's password, so we can use it to authenticate to Keycloak and request a new
    // offline token. There is no administrative Keycloak API to do that on behalf of a user.
    // When we're done, we will remove the password. Use a long random password so that even if
    // this process bombs out, an attacker won't be able to guess the password.
    val randomPassword = Base64.getEncoder().encodeToString(Random.nextBytes(40))

    keycloakAdminClient.setPassword(authId, randomPassword)

    try {
      val tokenUrl =
          UriComponentsBuilder.fromUriString(keycloakInfo.issuerUri)
              .path("/protocol/openid-connect/token")
              .build(mapOf("realm" to keycloakInfo.realm))
              .toString()

      val formParameters =
          Parameters.build {
            append("client_id", config.keycloak.apiClientId)
            append("scope", "offline_access")
            append("grant_type", "password")
            append("username", user.username)
            append("password", randomPassword)
          }

      return runBlocking {
        try {
          httpClient
              .submitForm(url = tokenUrl, formParameters = formParameters)
              .body<OpenIdConnectTokenResponsePayload>()
              .refresh_token
        } catch (e: ClientRequestException) {
          log.error("Keycloak returned HTTP ${e.response.status} for refresh token request")
          log.info("Keycloak response: ${e.response.bodyAsText()}")
          throw KeycloakRequestFailedException("Failed to generate refresh token")
        } catch (e: JsonConvertException) {
          log.error("Keycloak returned malformed response to refresh token request", e)
          throw KeycloakRequestFailedException("Failed to generate refresh token")
        }
      }
    } finally {
      keycloakAdminClient
          .getCredentials(authId)
          .filter { it.type == "password" }
          .forEach { credential ->
            try {
              keycloakAdminClient.removeCredential(authId, credential.id!!)
            } catch (e: Exception) {
              log.error(
                  "Failed to remove temporary password from device manager user $userId " +
                      "($authId) after generating token",
                  e,
              )

              // But return the token anyway; it should be usable and a long random password
              // sticking around afterwards should be harmless.
            }
          }
    }
  }

  fun deleteSelf() {
    requirePermissions { deleteSelf() }

    val user = currentUser()
    deleteUser(user)
  }

  fun deleteUserById(userId: UserId) {
    requirePermissions { deleteUsers() }

    val user = fetchOneById(userId)
    if (user.userType != UserType.Individual && user.userType != UserType.Funder) {
      throw AccessDeniedException("Not allowed to delete non-individual and non-funder users")
    }

    deleteUser(user)
  }

  fun deleteFunderById(userId: UserId) {
    requirePermissions { deleteFunder(userId) }

    val user = fetchOneById(userId)
    if (user.userType != UserType.Funder) {
      throw AccessDeniedException("Not allowed to non-funder users")
    }

    deleteUser(user)
  }

  private fun deleteUser(user: TerrawareUser) {
    dslContext.transaction { _ ->
      val authId = user.authId

      log.info("Deleting user ${user.userId} (auth ID $authId)")

      val row = usersDao.fetchOneById(user.userId) ?: throw UserNotFoundException(user.userId)
      val anonymizedRow =
          row.copy(
              authId = "deleted:${user.userId}",
              deletedTime = clock.instant(),
              email = "deleted:${user.userId}",
              emailNotificationsEnabled = false,
              firstName = "Deleted",
              lastName = "User",
          )
      usersDao.update(anonymizedRow)

      dslContext
          .deleteFrom(USER_PREFERENCES)
          .where(USER_PREFERENCES.USER_ID.eq(user.userId))
          .execute()

      // Handlers in other parts of the system will clean up dangling references to the user. Event
      // handlers run synchronously in the same transaction as the deletion.
      publisher.publishEvent(UserDeletionStartedEvent(user.userId))

      // Keycloak account deletion should come last because it can't be rolled back if some other
      // step in the deletion process fails.
      authId?.let { keycloakAdminClient.delete(it) }
    }
  }

  /**
   * Updates a user's last activity time when an API request is processed.
   *
   * @param[event] Event published by Spring's request-handling layer after each request. It
   *   includes a `userName` field which in our case is set to the user's authentication ID; for
   *   unauthenticated requests, that field is null and this method is a no-op.
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

  /** Returns TF contacts for an organization */
  fun getTerraformationContactUsers(organizationId: OrganizationId): List<IndividualUser> {
    val tfContactIds = organizationStore.fetchTerraformationContacts(organizationId)
    return fetchManyById(tfContactIds).map { user ->
      user as? IndividualUser
          ?: throw IllegalArgumentException("Terraformation Contact users must be individual users")
    }
  }

  private fun rowToIndividualUser(usersRow: UsersRow): IndividualUser {
    return IndividualUser(
        usersRow.createdTime ?: throw IllegalArgumentException("Created time should never be null"),
        usersRow.id ?: throw IllegalArgumentException("User ID should never be null"),
        usersRow.authId,
        usersRow.email ?: throw IllegalArgumentException("Email should never be null"),
        usersRow.emailNotificationsEnabled
            ?: throw IllegalArgumentException("Email notifications enabled should never be null"),
        usersRow.firstName,
        usersRow.lastName,
        usersRow.countryCode,
        usersRow.cookiesConsented,
        usersRow.cookiesConsentedTime,
        usersRow.locale,
        usersRow.timeZone,
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
        permissionStore,
    )
  }

  private fun rowToModel(user: UsersRow): TerrawareUser {
    return when (user.userTypeId) {
      UserType.DeviceManager -> rowToDeviceManagerUser(user)
      UserType.Funder -> rowToFunderUser(user)
      UserType.Individual -> rowToIndividualUser(user)
      UserType.System -> SystemUser(usersDao)
      else ->
          throw AccessDeniedException(
              "User type ${user.userTypeId} is not allowed to be converted to a model"
          )
    }
  }

  private fun rowToFunderUser(usersRow: UsersRow): FunderUser {
    return FunderUser(
        usersRow.createdTime!!,
        usersRow.id ?: throw java.lang.IllegalArgumentException("User ID should never be null"),
        usersRow.authId,
        usersRow.email ?: throw IllegalArgumentException("Email should never be null"),
        usersRow.emailNotificationsEnabled ?: true,
        usersRow.firstName,
        usersRow.lastName,
        usersRow.countryCode,
        usersRow.cookiesConsented,
        usersRow.cookiesConsentedTime,
        usersRow.locale,
        usersRow.timeZone,
        parentStore,
        permissionStore,
    )
  }

  private fun insertKeycloakUser(
      keycloakUser: UserRepresentation,
      type: UserType = UserType.Individual,
  ): UsersRow {
    val existingUser = usersDao.fetchByEmail(keycloakUser.email).firstOrNull()

    return if (existingUser != null && existingUser.authId == null) {
      log.info("Pending user ${existingUser.id} has registered with auth ID ${keycloakUser.id}")

      val updatedRow =
          existingUser.copy(
              authId = keycloakUser.id,
              firstName = keycloakUser.firstName,
              lastName = keycloakUser.lastName,
              modifiedTime = clock.instant(),
          )

      usersDao.update(updatedRow)
      updatedRow
    } else {
      val localeAttribute =
          keycloakUser.attributes?.get("locale")?.firstOrNull()?.let { Locale.forLanguageTag(it) }
      val usersRow =
          UsersRow(
              authId = keycloakUser.id,
              email = keycloakUser.email,
              emailNotificationsEnabled = false,
              firstName = keycloakUser.firstName,
              lastName = keycloakUser.lastName,
              locale = localeAttribute,
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
  @Suppress("PropertyName") // Snake-case property name is defined by OIDC standard
  @JsonIgnoreProperties(ignoreUnknown = true)
  data class OpenIdConnectTokenResponsePayload(val refresh_token: String)

  companion object {
    @Suppress("unused")
    private fun dummyFunctionToImportSymbolsReferredToInComments() {
      currentUser()
    }
  }
}
