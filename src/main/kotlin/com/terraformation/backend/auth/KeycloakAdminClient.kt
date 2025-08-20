package com.terraformation.backend.auth

import jakarta.ws.rs.core.Response

/**
 * A client for the Keycloak admin API. The client handles authenticating to Keycloak; callers only
 * need to concern themselves with the contents of the API requests and responses.
 *
 * This is modeled after the official Keycloak admin client library, but with a much smaller surface
 * area (it only covers admin functions we actually use).
 */
interface KeycloakAdminClient {
  fun create(representation: UserRepresentation): Response

  fun delete(keycloakId: String): Response

  fun get(keycloakId: String): UserRepresentation?

  fun getCredentials(keycloakId: String): List<CredentialRepresentation>

  fun executeActionsEmail(
      keycloakId: String,
      clientId: String,
      redirectUri: String,
      lifespan: Int,
      actions: List<String>,
  )

  fun removeCredential(keycloakId: String, credentialId: String)

  fun searchByEmail(email: String, exact: Boolean): List<UserRepresentation>

  fun setPassword(keycloakId: String, password: String)

  fun update(representation: UserRepresentation)
}
