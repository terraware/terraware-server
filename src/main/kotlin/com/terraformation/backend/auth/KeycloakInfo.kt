package com.terraformation.backend.auth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

data class KeycloakInfo(
    val clientId: String,
    val clientSecret: String,
    val issuerUri: String,
) {
  /** URL of OpenID Connect configuration endpoint. */
  val openIdConnectConfigUrl = "$issuerUri/.well-known/openid-configuration"

  val realm = issuerUri.substringAfterLast("/realms/")
}

@Configuration
class KeycloakInfoFactory {
  @Bean
  @Profile("!apidoc") //
  fun keycloakInfo(clientRegistrationRepository: ClientRegistrationRepository): KeycloakInfo {
    val registration: ClientRegistration =
        clientRegistrationRepository.findByRegistrationId("keycloak")
            ?: throw IllegalArgumentException("No client registration found for \"keycloak\"")
    return KeycloakInfo(
        registration.clientId,
        registration.clientSecret,
        registration.providerDetails.issuerUri,
    )
  }

  /**
   * Returns fake Keycloak info for purposes of running the server standalone to generate API docs.
   * We don't want API doc generation to require connectivity to a real Keycloak instance.
   */
  @Bean
  @Profile("apidoc")
  fun apiDocKeycloakInfo() =
      KeycloakInfo("example", "example", "https://example.com/realms/example")
}
