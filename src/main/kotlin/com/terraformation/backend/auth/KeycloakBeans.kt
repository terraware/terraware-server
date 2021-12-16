package com.terraformation.backend.auth

import com.terraformation.backend.config.TerrawareServerConfig
import org.keycloak.OAuth2Constants
import org.keycloak.adapters.springboot.KeycloakSpringBootConfigResolver
import org.keycloak.adapters.springboot.KeycloakSpringBootProperties
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.keycloak.admin.client.resource.RealmResource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Makes Keycloak admin API functions available to application code. This uses Keycloak's official
 * admin API client library.
 *
 * If you are interacting with Keycloak, you will generally want to call [RealmResource] rather than
 * the top-level [Keycloak] object.
 */
@Configuration
class KeycloakBeans {
  @Bean
  fun keycloak(
      config: TerrawareServerConfig,
      keycloakProperties: KeycloakSpringBootProperties
  ): Keycloak? {
    val keycloakAdapterSecret = keycloakProperties.credentials["secret"]?.toString()

    return KeycloakBuilder.builder()
        .clientId(config.keycloak.clientId ?: keycloakProperties.resource)
        .clientSecret(config.keycloak.clientSecret ?: keycloakAdapterSecret)
        .realm(keycloakProperties.realm)
        .serverUrl(keycloakProperties.authServerUrl)
        .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
        .build()
  }

  @Bean
  fun realmResource(
      keycloak: Keycloak,
      keycloakProperties: KeycloakSpringBootProperties
  ): RealmResource? {
    return keycloak.realm(keycloakProperties.realm)
  }

  /**
   * Allows Keycloak config settings to be specified in `application.yaml` rather than in a separate
   * config file.
   */
  @Bean fun keycloakConfigResolver() = KeycloakSpringBootConfigResolver()
}
