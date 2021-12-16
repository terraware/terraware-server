package com.terraformation.backend.auth

import com.terraformation.backend.VERSION
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.UserModel
import org.keycloak.adapters.springsecurity.KeycloakConfiguration
import org.keycloak.adapters.springsecurity.authentication.KeycloakAuthenticationEntryPoint
import org.keycloak.adapters.springsecurity.authentication.KeycloakAuthenticationProvider
import org.keycloak.adapters.springsecurity.config.KeycloakWebSecurityConfigurerAdapter
import org.keycloak.adapters.springsecurity.filter.KeycloakAuthenticationProcessingFilter
import org.keycloak.adapters.springsecurity.token.KeycloakAuthenticationToken
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.config.web.servlet.invoke
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.header.writers.StaticHeadersWriter
import org.springframework.security.web.session.HttpSessionEventPublisher
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Configures Spring Security to authenticate incoming requests.
 *
 * You probably don't need to mess with any of this. In the application code, you can call
 * [currentUser] to get the currently logged-in user's details.
 *
 * Most of the heavy lifting is done by the Keycloak Java adapter library. It takes care of
 * verifying the signatures on bearer tokens, redirecting the user to Keycloak to log in, and
 * creating new sessions for interactive users. A lot of the code in this class is just to configure
 * Spring Security to use that library to authenticate incoming requests.
 *
 * The key thing we get out of that library is a [KeycloakAuthenticationToken] object, which has
 * data about the current user. That object gets stored in the Spring [SecurityContextHolder].
 *
 * Among other things, that object includes the Keycloak user ID. [UserModelFilter] uses that ID to
 * look up the current user's [UserModel] and make it available to application code.
 *
 * When an interactive user logs in, their first request includes the [KeycloakAuthenticationToken]
 * in JSON form. But it is pretty large; we don't want browsers to have to send it to us on every
 * request. So Spring Security creates a session that gets persisted to a session store (currently
 * just a couple database tables). The session data includes the [KeycloakAuthenticationToken].
 * Subsequent requests can then look the data up using a session ID in a cookie.
 *
 * Non-interactive clients (e.g., the device manager) authenticate using a bearer token they request
 * directly from Keycloak.
 */
@KeycloakConfiguration
class SecurityConfig(private val userStore: UserStore) : KeycloakWebSecurityConfigurerAdapter() {

  override fun configure(http: HttpSecurity) {
    super.configure(http)

    http {
      cors {}
      csrf { disable() }
      authorizeRequests {
        // Allow unauthenticated users to fetch the endpoint that redirects them to the login page.
        authorize("/api/v1/login", permitAll)

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

      // Add a request handling filter that uses the KeycloakAuthenticationToken to look up a
      // UserModel. This needs to come after the Keycloak client library has had a chance to
      // authenticate the request.
      addFilterAfter<KeycloakAuthenticationProcessingFilter>(UserModelFilter(userStore))
    }
  }

  /** Configures Spring Security to use the Keycloak client library to authenticate requests. */
  override fun configure(auth: AuthenticationManagerBuilder) {
    auth.authenticationProvider(KeycloakAuthenticationProvider())
    auth.authenticationProvider(ExistingSessionAuthenticationProvider())
  }

  override fun sessionAuthenticationStrategy(): SessionAuthenticationStrategy {
    // Keycloak docs recommend using RegisterSessionAuthenticationStrategy but that class isn't
    // cluster-aware, and it only exists to provide functionality we don't need.
    return NullAuthenticatedSessionStrategy()
  }

  /**
   * Configures Spring Security to return HTTP 401 for unauthenticated API requests and redirect
   * unauthenticated non-API requests to the login page.
   *
   * This doesn't apply to /api/v1/login, which is configured to allow anonymous access; the handler
   * for that endpoint explicitly issues a redirect to the Keycloak login page if the user isn't
   * logged in.
   */
  override fun authenticationEntryPoint(): AuthenticationEntryPoint {
    return KeycloakAuthenticationEntryPoint(
        adapterDeploymentContext(), AntPathRequestMatcher("/api/**"))
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

  /** Notifies Keycloak when sessions are created and destroyed. */
  @Bean
  fun httpSessionEventPublisher(): ServletListenerRegistrationBean<HttpSessionEventPublisher> {
    return ServletListenerRegistrationBean(HttpSessionEventPublisher())
  }
}
