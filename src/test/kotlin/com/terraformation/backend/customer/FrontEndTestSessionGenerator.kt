package com.terraformation.backend.customer

import com.terraformation.backend.auth.SimplePrincipal
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import org.springframework.security.authentication.TestingAuthenticationToken
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

  val token = TestingAuthenticationToken(SimplePrincipal(authId), "test")
  token.isAuthenticated = true
  val springContext = SecurityContextImpl(token)

  ByteArrayOutputStream().use { byteStream ->
    ObjectOutputStream(byteStream).use { objectStream -> objectStream.writeObject(springContext) }

    val hex = byteStream.toByteArray().toHexString()

    println("Hex-encoded SPRING_SECURITY_CONTEXT for auth ID $authId:")
    println()
    println(hex)
  }
}
