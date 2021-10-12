package com.terraformation.backend.auth

/**
 * Actions that can be marked as required on a Keycloak user. Technically, these are dynamic because
 * they can be renamed in the Keycloak admin UI, so they aren't declared as constants in the
 * Keycloak client library. But we use the default names.
 */
enum class KeycloakRequiredActions(val keyword: String) {
  /**
   * Require the user to change their password on next login. This can also be set on a newly
   * created user who has no password at all yet.
   */
  UpdatePassword("UPDATE_PASSWORD")
}
