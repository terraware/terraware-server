package com.terraformation.backend.auth

import org.keycloak.adapters.springboot.KeycloakSpringBootConfigResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KeycloakBeans {
  /**
   * Allows Keycloak config settings to be specified in `application.yaml` rather than in a separate
   * config file.
   */
  @Bean fun keycloakConfigResolver() = KeycloakSpringBootConfigResolver()
}
