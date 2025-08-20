package com.terraformation.backend.auth

import com.terraformation.backend.config.TerrawareServerConfig
import jakarta.inject.Named
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.ws.rs.core.Response
import java.util.concurrent.ConcurrentHashMap
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono

/**
 * Keycloak admin client that talks to a real Keycloak server.
 *
 * Under the hood, this uses the asynchronous [WebClient] to construct API requests. The client is
 * configured to know that Keycloak requests have to be authenticated using OAuth2.
 *
 * The underlying asynchronous responses are mapped to synchronous return values to match the
 * behavior of the official Keycloak admin client library.
 */
@Named
class KeycloakAdminClientImpl(
    private val config: TerrawareServerConfig,
    private val keycloakInfo: KeycloakInfo,
) : KeycloakAdminClient {
  private val webClient: WebClient by lazy { makeWebClient() }

  override fun create(representation: UserRepresentation): Response {
    return webClient.post().uri("/users").bodyValue(representation).retrieveStringResponse()
  }

  override fun searchByEmail(email: String, exact: Boolean): List<UserRepresentation> {
    val users =
        webClient
            .get()
            .uri {
              it.path("/users")
                  .queryParam("email", "{email}")
                  .queryParam("exact", "$exact")
                  .build(mapOf("email" to email))
            }
            .retrieve()
            .bodyToMono<List<UserRepresentation>>()
            .block()!!

    return users
  }

  override fun delete(keycloakId: String): Response {
    return webClient.delete().uri("/users/$keycloakId").retrieveStringResponse()
  }

  override fun executeActionsEmail(
      keycloakId: String,
      clientId: String,
      redirectUri: String,
      lifespan: Int,
      actions: List<String>,
  ) {
    webClient
        .put()
        .uri {
          it.path("/users/$keycloakId/execute-actions-email")
              .queryParam("client_id", "{clientId}")
              .queryParam("redirect_uri", "{redirectUri}")
              .queryParam("lifespan", lifespan)
              .build(mapOf("clientId" to clientId, "redirectUri" to redirectUri))
        }
        .bodyValue(actions)
        .retrieve()
        .toBodilessEntity()
        .block()
  }

  override fun get(keycloakId: String): UserRepresentation? {
    return webClient
        .get()
        .uri("/users/{id}", keycloakId)
        .retrieve()
        .onStatus({ it == HttpStatus.NOT_FOUND }) { Mono.empty() }
        .bodyToMono<UserRepresentation>()
        .block()
  }

  override fun getCredentials(keycloakId: String): List<CredentialRepresentation> {
    return webClient
        .get()
        .uri("/users/{id}/credentials", keycloakId)
        .retrieve()
        .bodyToMono<List<CredentialRepresentation>>()
        .block()!!
  }

  override fun removeCredential(keycloakId: String, credentialId: String) {
    webClient
        .delete()
        .uri("/users/{userId}/credentials/{credentialId}", keycloakId, credentialId)
        .retrieve()
        .toBodilessEntity()
        .block()
  }

  override fun setPassword(keycloakId: String, password: String) {
    val credentials =
        CredentialRepresentation(temporary = false, type = "password", value = password)

    webClient
        .put()
        .uri("/users/{id}/reset-password", keycloakId)
        .bodyValue(credentials)
        .retrieve()
        .toBodilessEntity()
        .block()
  }

  override fun update(representation: UserRepresentation) {
    webClient
        .put()
        .uri("/users/{id}", representation.id)
        .bodyValue(representation)
        .retrieve()
        .toBodilessEntity()
        .block()
  }

  /** Sends a request and retrieves its response as a JAX-RS [Response] with a string entity. */
  private fun WebClient.RequestHeadersSpec<*>.retrieveStringResponse(): Response {
    return exchangeToMono { response ->
          response
              .bodyToMono<String>()
              .flatMap { responseBody ->
                Mono.just(
                    Response.status(response.statusCode().value()).entity(responseBody).build()
                )
              }
              .defaultIfEmpty(Response.status(response.statusCode().value()).build())
        }
        .block()!!
  }

  private fun makeWebClient(): WebClient {
    // Once we've switched from the Keycloak adapter to Spring Security OAuth2, we can copy some
    // of the client registration details from the Spring config, which automatically looks up
    // endpoints via OIDC.
    val tokenUri =
        UriComponentsBuilder.fromUriString(keycloakInfo.issuerUri)
            .pathSegment("protocol", "openid-connect", "token")
            .build(mapOf("realm" to keycloakInfo.realm))
            .toString()
    val adminClient =
        ClientRegistration.withRegistrationId("keycloak")
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .clientId(config.keycloak.clientId ?: keycloakInfo.clientId)
            .clientSecret(config.keycloak.clientSecret ?: keycloakInfo.clientSecret)
            .scope(listOf("openid"))
            .tokenUri(tokenUri)
            .build()
    val adminRepository = InMemoryClientRegistrationRepository(adminClient)

    val filter =
        ServletOAuth2AuthorizedClientExchangeFilterFunction(
            adminRepository,
            InMemoryAuthorizedClientRepository(),
        )
    filter.setDefaultClientRegistrationId("keycloak")

    // Issuer URI is, e.g., http://host/realms/funder. We want http://host/admin/realms/funder
    val adminUri =
        UriComponentsBuilder.fromUriString(keycloakInfo.issuerUri)
            .replacePath(null)
            .pathSegment("admin", "realms", keycloakInfo.realm)
            .build()

    return WebClient.builder().filter(filter).baseUrl("$adminUri").build()
  }

  private class InMemoryAuthorizedClientRepository : OAuth2AuthorizedClientRepository {
    private val clients = ConcurrentHashMap<String, OAuth2AuthorizedClient>()

    override fun <T : OAuth2AuthorizedClient?> loadAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication?,
        request: HttpServletRequest?,
    ): T {
      @Suppress("UNCHECKED_CAST")
      return clients[clientRegistrationId] as T
    }

    override fun saveAuthorizedClient(
        authorizedClient: OAuth2AuthorizedClient,
        principal: Authentication?,
        request: HttpServletRequest?,
        response: HttpServletResponse?,
    ) {
      clients[authorizedClient.clientRegistration.registrationId] = authorizedClient
    }

    override fun removeAuthorizedClient(
        clientRegistrationId: String,
        principal: Authentication?,
        request: HttpServletRequest?,
        response: HttpServletResponse?,
    ) {
      clients.remove(clientRegistrationId)
    }
  }
}
