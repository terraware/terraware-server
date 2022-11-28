package com.terraformation.backend.auth

import java.net.URI
import javax.inject.Named
import org.keycloak.adapters.springboot.KeycloakSpringBootProperties

@Named
class KeycloakInfo(keycloakProperties: KeycloakSpringBootProperties) {
  /**
   * Client ID the server uses to authenticate users with Keycloak. This is included in redirect
   * URLs to send unauthenticated users to Keycloak's login page.
   */
  val clientId: String = keycloakProperties.resource

  /**
   * URL prefix for Keycloak endpoints for the realm. Ends with trailing slash so it can be used
   * with [URI.resolve].
   */
  val realmBaseUrl =
      URI(
          keycloakProperties.authServerUrl.trimEnd('/') +
              "/realms/" +
              keycloakProperties.realm +
              "/")

  /** URL of OpenID Connect configuration endpoint. */
  val openIdConnectConfigUrl: URI = realmBaseUrl.resolve(".well-known/openid-configuration")
}
