package com.terraformation.backend.customer.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.KeycloakRequestFailedException
import com.terraformation.backend.db.KeycloakUserNotFoundException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.AccessionsDao
import com.terraformation.backend.db.tables.daos.UsersDao
import com.terraformation.backend.db.tables.pojos.UsersRow
import java.time.Clock
import javax.annotation.ManagedBean
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.UserRepresentation
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException

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
 * and prepopulate it with the profile information they gave to Keycloak when they signed up.
 * Fetching a user's details can thus result in an API call to the Keycloak server.
 *
 * Spring Security calls this class to look up users when it is authenticating requests.
 */
@ManagedBean
class UserStore(
    private val accessionsDao: AccessionsDao,
    private val clock: Clock,
    private val permissionStore: PermissionStore,
    private val realmResource: RealmResource,
    private val usersDao: UsersDao,
) : UserDetailsService {
  /**
   * Returns the details for the user with a given Keycloak user ID. Pulls the user's information
   * from Keycloak if they don't exist in our users table yet.
   *
   * @throws KeycloakRequestFailedException Could not request user information from Keycloak. This
   * implies that the user didn't exist in the users table.
   * @throws KeycloakUserNotFoundException There is no user with that ID in the Keycloak database.
   */
  @Suppress("MemberVisibilityCanBePrivate")
  fun fetchByAuthId(authId: String): UserModel {
    val existingUser = usersDao.fetchByAuthId(authId).firstOrNull()
    val user =
        if (existingUser != null) {
          existingUser
        } else {
          val keycloakUser =
              try {
                realmResource.users().get(authId)?.toRepresentation()
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
                realmResource.users().search(email, true)
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
   * Returns a user who was authenticated via oauth2-proxy. This is called by Spring Security as
   * part of request authentication; in application code, call [fetchByAuthId] instead since it
   * follows our usual method naming convention.
   */
  override fun loadUserByUsername(username: String): UserDetails {
    try {
      return fetchByAuthId(username)
    } catch (e: KeycloakUserNotFoundException) {
      // Spring Security expects a specific exception to be thrown in this case.
      throw UsernameNotFoundException(e.message, e)
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
        permissionStore,
    )
  }

  private fun insertKeycloakUser(keycloakUser: UserRepresentation): UsersRow {
    val usersRow =
        UsersRow(
            authId = keycloakUser.id,
            email = keycloakUser.email,
            firstName = keycloakUser.firstName,
            lastName = keycloakUser.lastName,
            userTypeId = UserType.Individual,
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
        )

    usersDao.insert(usersRow)
    return usersRow
  }

  companion object {
    @Suppress("unused")
    private fun dummyFunctionToImportSymbolsReferredToInComments() {
      currentUser()
    }
  }
}
