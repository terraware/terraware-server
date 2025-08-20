package com.terraformation.backend.auth

import com.terraformation.backend.VERSION
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.DeviceManagerUser
import com.terraformation.backend.customer.model.IndividualUser
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authorization.AuthenticatedAuthorizationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.header.writers.StaticHeadersWriter
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Configures Spring Security to authenticate incoming requests.
 *
 * You probably don't need to mess with any of this. In the application code, you can call
 * [currentUser] to get the currently logged-in user's details.
 *
 * Most of the heavy lifting is done by the Spring Security OAuth2 code. It takes care of verifying
 * the signatures on bearer tokens, redirecting the user to Keycloak to log in, and creating new
 * sessions for interactive users.
 *
 * Once a user's authentication details are verified, they're stored in the [SecurityContextHolder].
 * For interactive users, Spring creates a new login session and sets a cookie with the session ID.
 * The authentication details are serialized and stored as a session attribute so the client doesn't
 * have to pass them to the server with every request.
 *
 * Non-interactive clients (e.g., the device manager) authenticate using a bearer token they request
 * directly from Keycloak; they pass the token to the server with every request and no sessions or
 * cookies are generated for them.
 *
 * The authentication details Spring OAuth2 produces are generic and have no Terraware-specific
 * information, just the Keycloak user ID. [TerrawareUserFilter], which runs after the
 * authentication details are verified, looks up the [IndividualUser] or [DeviceManagerUser] with
 * the given Keycloak user ID and stores it in [CurrentUserHolder] to make it available to
 * application code.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val config: TerrawareServerConfig,
    private val userStore: UserStore,
) {
  @Bean
  fun securityFilter(
      http: HttpSecurity,
      clientRegistrationRepository: ClientRegistrationRepository,
  ): SecurityFilterChain {
    // https://github.com/spring-projects/spring-security/issues/16162
    val fullyAuthenticated =
        AuthenticatedAuthorizationManager.fullyAuthenticated<RequestAuthorizationContext>()

    http {
      cors {}
      csrf { disable() }
      authorizeHttpRequests {
        // Allow unauthenticated users to fetch localized strings.
        authorize("/api/v1/i18n/**", permitAll)

        // Allow unauthenticated users to check their app versions for compatibility.
        authorize("/api/v1/versions", permitAll)

        // Allow unauthenticated users to fetch OpenAPI docs.
        authorize("/v3/**", permitAll)
        authorize("/swagger-ui/**", permitAll)
        authorize("/swagger-ui.html", permitAll)

        // Allow unauthenticated users (such as load balancers) to access health checks.
        authorize("/actuator/health", permitAll)

        authorize("/api/**", fullyAuthenticated)
        authorize("/admin/**", fullyAuthenticated)
      }

      httpBasic { disable() }

      // By default, Spring Security will create a new session for each request that doesn't already
      // have one. It will persist the session to the session store and generate a cookie, and then
      // when a new request comes in, it will see the cookie and look up the existing session to
      // get the user's identity.
      //
      // For noninteractive clients, we don't want that; they'll include an authentication token
      // with each request and with the default behavior, we'd be constantly creating a bunch of
      // sessions the client would just ignore because it's not looking at the Cookie header in the
      // response.
      //
      // But for interactive clients, we do want a cookie so they don't have to authenticate each
      // request separately.
      //
      // Setting the session creation policy to NEVER tells Spring Security to never automatically
      // create a session. But the application code is still allowed to explicitly create one, and
      // Spring Security will honor it. In this case, the "application code" is the Keycloak
      // adapter's interactive login endpoint (/sso/login, which the user gets redirected to after
      // entering their password): the adapter explicitly creates a session so it can attach the
      // user's Keycloak identity to the session.
      //
      // This gives us the behavior we want: a session cookie for interactive users, and no cookie
      // for programmatic clients because they never go through the interactive login process and
      // thus never hit the /sso/login endpoint.
      sessionManagement { sessionCreationPolicy = SessionCreationPolicy.NEVER }

      // This has nothing to do with security, but Spring Security supports adding custom headers.
      headers { addHeaderWriter(StaticHeadersWriter("Server", "Terraware-Server/$VERSION")) }

      oauth2Login {
        authorizationEndpoint {
          baseUri = "/api/oauth2/authorization"
          authorizationRequestResolver =
              CustomOAuth2AuthorizationRequestResolver(
                  clientRegistrationRepository,
                  "/api/oauth2/authorization",
              )
        }
        redirectionEndpoint { baseUri = "/api/oauth2/code/*" }
      }

      logout {
        logoutUrl = "/sso/logout"
        logoutSuccessHandler =
            OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository).apply {
              setPostLogoutRedirectUri("{baseUrl}")
            }
      }

      // Allow clients to authenticate using JWT-encoded access tokens.
      oauth2ResourceServer { jwt {} }

      // Add a request handling filter that uses the user ID from the OAuth2 authentication data to
      // look up a TerrawareUser. This needs to come after Spring has had a chance to authenticate
      // the request.
      addFilterAfter<BearerTokenAuthenticationFilter>(TerrawareUserFilter(userStore))

      // Add a request filter that logs request and response payloads for users whose email
      // addresses match a configured regex.
      if (config.requestLog.emailRegex != null) {
        addFilterAfter<TerrawareUserFilter>(RequestResponseLoggingFilter(config.requestLog))
      }

      // For requests from API clients, return HTTP 401 instead of redirecting to the login page.
      // "API clients" are distinguished from browser clients by the presence of application/json
      // in the Accept header line.
      exceptionHandling {
        val matcher = MediaTypeRequestMatcher(MediaType.APPLICATION_JSON)
        matcher.setIgnoredMediaTypes(setOf(MediaType.ALL))

        defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), matcher)
      }
    }

    return http.build()
  }

  @Bean
  fun corsConfigurationSource(): CorsConfigurationSource {
    val configuration = CorsConfiguration().applyPermitDefaultValues()
    val source = UrlBasedCorsConfigurationSource()
    configuration.allowedMethods = listOf("*")
    configuration.allowedOriginPatterns = listOf("*")
    configuration.allowCredentials = true
    source.registerCorsConfiguration("/**", configuration)
    return source
  }
}
