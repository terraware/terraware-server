package com.terraformation.backend.customer

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.HexFormat
import org.keycloak.KeycloakPrincipal
import org.keycloak.adapters.RefreshableKeycloakSecurityContext
import org.keycloak.adapters.springsecurity.account.KeycloakRole
import org.keycloak.adapters.springsecurity.account.SimpleKeycloakAccount
import org.keycloak.adapters.springsecurity.token.KeycloakAuthenticationToken
import org.keycloak.common.util.Base64Url
import org.keycloak.representations.AccessToken
import org.keycloak.util.JsonSerialization
import org.springframework.security.core.context.SecurityContextImpl

/**
 * Authentication ID from the frontend test suite's initial data set. This isn't a real user ID on
 * the actual service; it's only valid in the context of the frontend test suite.
 */
private const val DEFAULT_AUTH_ID = "0d04525c-7933-4cec-9647-7b6ac2642838"

/**
 * Generates a non-expiring login session for use by the frontend integration test suite. The output
 * of this program should be used as the value of the `SPRING_SECURITY_CONTEXT` session attribute in
 * the frontend test suite's initial database. You'll need to prefix it with either `\x` or `\\x`
 * depending on whether you're including it in an `INSERT`/`UPDATE` statement or the data block of a
 * `COPY` statement, respectively.
 *
 * For example:
 * ```
 * INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
 * VALUES ('<your session ID>', 'SPRING_SECURITY_CONTEXT', '\x<output of this program>');
 * ```
 *
 * You'll usually want to run this using `./gradlew generateFrontEndTestSession` so you don't have
 * to manually set up the right classpath.
 *
 * Generally you should only need to run this after a Spring or Keycloak adapter upgrade that
 * changes the serialized representation of the session data.
 */
fun main(args: Array<String>) {
  val authId = args.getOrNull(0) ?: DEFAULT_AUTH_ID

  // Generate an access token with an "issued-at" time in the past and no expiration time. The token
  // is technically a JSON Web Token with multiple period-delimited parts, but the Keycloak
  // adapter only cares about the second part and is fine with a zero-length first part.
  val accessToken = AccessToken().apply { iat(0) }
  val tokenString = "." + Base64Url.encode(JsonSerialization.writeValueAsBytes(accessToken))

  val keycloakContext =
      RefreshableKeycloakSecurityContext(null, null, tokenString, accessToken, null, null, null)
  val token =
      KeycloakAuthenticationToken(
          SimpleKeycloakAccount(
              KeycloakPrincipal(authId, keycloakContext), emptySet(), keycloakContext),
          true,
          listOf(KeycloakRole("offline_access"), KeycloakRole("uma_authorization")))
  val springContext = SecurityContextImpl(token)

  ByteArrayOutputStream().use { byteStream ->
    ObjectOutputStream(byteStream).use { objectStream -> objectStream.writeObject(springContext) }

    val bytes = byteStream.toByteArray()
    val hex = HexFormat.of().formatHex(bytes)

    println("Hex-encoded SPRING_SECURITY_CONTEXT for auth ID $authId:")
    println()
    println(hex)
  }
}
