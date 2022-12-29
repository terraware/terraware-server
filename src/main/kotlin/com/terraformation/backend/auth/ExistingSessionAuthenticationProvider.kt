package com.terraformation.backend.auth

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import org.keycloak.adapters.tomcat.SimplePrincipal
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationProvider
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken

/**
 * No-op authentication provider that allows existing preauthenticated sessions to make requests.
 *
 * This is for use in automated tests of front end code. Before running a test suite, we insert a
 * session into the Spring sessions table, and it has a `SPRING_SECURITY_CONTEXT` attribute that
 * contains a [PreAuthenticatedAuthenticationToken]. (There is no way to create such a token using
 * API requests, only by directly manipulating the database.) This class "looks up" the user's
 * details by just creating a dummy [User] object.
 */
class ExistingSessionAuthenticationProvider : PreAuthenticatedAuthenticationProvider() {
  init {
    setPreAuthenticatedUserDetailsService { User(it.name, "PreAuthenticated", emptyList()) }
  }

  /**
   * Returns a serialized Spring Security context suitable for inserting into the session store. You
   * can call this from a scratch file if you are writing a new front-end test suite and you want to
   * initialize a terraware-server database so the tests can make authenticated API calls.
   *
   * Typical usage:
   * 1. Insert a user into the `users` table. Give it a random `auth_id` value.
   * 2. Call this method with the ID you used.
   * 3. Insert a row into the `spring_session` table. Use a random UUID as the session ID and set
   *    the expiration times far into the future.
   * 4. Insert a row into the `spring_session_attributes` table with an attribute name of
   *    `SPRING_SECURITY_CONTEXT` and the return value of this method as the attribute value.
   *
   * Then you can set the `SESSION` cookie to the base64-encoded session ID, and requests will use
   * the session without having to log in with Keycloak.
   */
  @Suppress("unused")
  fun generateSecurityContext(authId: String): ByteArray {
    val token = PreAuthenticatedAuthenticationToken(SimplePrincipal(authId), "dummy")
    val session = SecurityContextImpl(token)

    val byteStream = ByteArrayOutputStream()
    val objStream = ObjectOutputStream(byteStream)
    objStream.writeObject(session)
    objStream.close()

    return byteStream.toByteArray()
  }
}
