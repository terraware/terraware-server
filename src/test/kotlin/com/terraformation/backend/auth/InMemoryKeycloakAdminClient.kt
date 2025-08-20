package com.terraformation.backend.auth

import io.mockk.mockk
import jakarta.ws.rs.core.Response
import java.io.IOException
import java.util.UUID

/**
 * Test version of the Keycloak admin client. This runs entirely in memory rather than updating user
 * data on a real Keycloak server.
 *
 * This is a real class rather than a simple call to [mockk] because in order to test how the
 * calling code interacts with Keycloak, we need to maintain a bit of state. There's the risk that
 * this dummy code won't match the actual behavior of Keycloak, so we still need to run real
 * end-to-end tests in a staging environment, but Keycloak is too heavyweight to spin up for unit
 * tests.
 */
class InMemoryKeycloakAdminClient : KeycloakAdminClient {
  /** Set this to true to cause methods to throw IOException. */
  var simulateRequestFailures: Boolean = false

  private val actionsRequired = mutableMapOf<String, List<String>>()
  val credentials = mutableMapOf<String, List<CredentialRepresentation>>()
  private val users = mutableListOf<UserRepresentation>()

  override fun create(representation: UserRepresentation): Response {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    if (
        users.any {
          it.email == representation.email ||
              it.username == representation.username ||
              it.id == representation.id
        }
    ) {
      return Response.status(Response.Status.CONFLICT).entity("Dupe!").build()
    }

    representation.id = representation.id ?: UUID.randomUUID().toString()
    users.add(representation)

    return Response.status(Response.Status.CREATED).build()
  }

  override fun delete(keycloakId: String): Response {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    val user = users.firstOrNull { it.id == keycloakId }

    return if (user != null) {
      actionsRequired.remove(keycloakId)
      users.remove(user)

      // Keycloak returns HTTP 204 on successful deletion.
      Response.status(Response.Status.NO_CONTENT).build()
    } else {
      // Keycloak returns HTTP 404 on deletion if user doesn't exist.
      Response.status(Response.Status.NOT_FOUND).entity("Not found!").build()
    }
  }

  override fun executeActionsEmail(
      keycloakId: String,
      clientId: String,
      redirectUri: String,
      lifespan: Int,
      actions: List<String>,
  ) {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    val user = users.first { it.id == keycloakId }

    actionsRequired[user.email] = actions
  }

  override fun removeCredential(keycloakId: String, credentialId: String) {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    if (keycloakId in credentials) {
      credentials[keycloakId] = getCredentials(keycloakId).filter { it.id != credentialId }
    }
  }

  override fun get(keycloakId: String): UserRepresentation? {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    return users.firstOrNull { it.id == keycloakId }
  }

  override fun getCredentials(keycloakId: String): List<CredentialRepresentation> {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    return credentials[keycloakId] ?: emptyList()
  }

  override fun searchByEmail(email: String, exact: Boolean): List<UserRepresentation> {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }
    if (!exact) {
      throw IllegalArgumentException("Code should not be doing non-exact search by username")
    }

    return users.filter { it.username == email }
  }

  override fun setPassword(keycloakId: String, password: String) {
    credentials[keycloakId] =
        getCredentials(keycloakId) +
            CredentialRepresentation(
                id = UUID.randomUUID().toString(),
                temporary = false,
                type = "password",
                value = password,
            )
  }

  override fun update(representation: UserRepresentation) {
    if (simulateRequestFailures) {
      throw IOException("Simulated request failure")
    }

    users.replaceAll { if (it.id != representation.id) it else representation }
  }
}
