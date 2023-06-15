package com.terraformation.backend.auth

import java.io.Serializable
import java.security.Principal

/**
 * Minimal implementation of [Principal] that just stores the user's Keycloak ID. This is used when
 * we want to artificially generate an authenticated session, e.g., for the web app's end-to-end
 * test suite.
 */
data class SimplePrincipal(private val name: String) : Principal, Serializable {
  override fun getName() = name
}
