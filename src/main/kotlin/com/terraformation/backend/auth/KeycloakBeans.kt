package com.terraformation.backend.auth

import com.terraformation.backend.config.TerrawareServerConfig
import org.keycloak.OAuth2Constants
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
  fun keycloak(config: TerrawareServerConfig): Keycloak? {
    val keycloakConfig = config.keycloak

    return KeycloakBuilder.builder()
        .clientId(keycloakConfig.clientId)
        .clientSecret(keycloakConfig.clientSecret)
        .realm(keycloakConfig.realm)
        .serverUrl(keycloakConfig.serverUrl.toString())
        .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
        .build()
  }

  @Bean
  fun realmResource(config: TerrawareServerConfig, keycloak: Keycloak): RealmResource? {
    val realm = config.keycloak.realm
    return keycloak.realm(realm)
  }
}
