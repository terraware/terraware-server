package com.terraformation.backend.auth

import javax.servlet.http.HttpServletRequest
import org.keycloak.adapters.AdapterTokenStore
import org.keycloak.adapters.KeycloakDeployment
import org.keycloak.adapters.OAuthRequestAuthenticator
import org.keycloak.adapters.RequestAuthenticator
import org.keycloak.adapters.spi.AdapterSessionStore
import org.keycloak.adapters.spi.HttpFacade
import org.keycloak.adapters.springsecurity.authentication.RequestAuthenticatorFactory
import org.keycloak.adapters.springsecurity.authentication.SpringSecurityRequestAuthenticator
import org.keycloak.adapters.springsecurity.filter.KeycloakAuthenticationProcessingFilter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration

/**
 * Allows local non-HTTPS frontend dev environments to be used with staging and production
 * terraware-server instances.
 *
 * This works around a problem with the way URLs are generated in the login flow. If a local dev
 * environment at the default location of `http://localhost:3000/` is proxying API requests to
 * staging, the flow of requests should look something like this from the browser's point of view.
 *
 * 1. `http://localhost:3000/api/v1/login?redirect=http://localhost:3000/` puts the requested
 * redirect URL in a cookie and issues a redirect to
 * 2. `http://localhost:3000/sso/login` which reads the cookie, saves the value to the database
 * along with some other state about the login handshake (the database record gets a state ID, which
 * we'll call XYZ here) and redirects to
 * 3. `https://auth.staging.terraware.io/...?redirect_uri=http://localhost:3000/sso/login&state=XYZ`
 * 4. (User logs in)
 * 5. `http://localhost:3000/sso/login?state=XYZ` saves the authentication information, creates a
 * session cookie, and redirects to the URL from the query parameter in step 1,
 * 6. `http://localhost:3000/`
 *
 * The problem is that in dev environments, the `/api` and `/sso` requests, which are HTTP, are
 * being proxied by the local Node.js server to a load balancer at AWS, which speaks HTTPS. The load
 * balancer, however, forwards the requests to the actual terraware-server instances as HTTP. At
 * that point, the server has no good way of knowing that from the browser's point of view, the
 * request was _not_ originally HTTPS, or that the request went to a custom port number. So when it
 * constructs redirect URLs, it uses `https://localhost/` to match what the load balancer expects.
 * We thus end up telling Keycloak to redirect the user to `https://localhost/sso/login` which won't
 * work.
 *
 * The fix is to replace the scheme and hostname of the `redirect_uri` in step 3 with the redirect
 * URI the client supplied in step 1. We only do this for redirects to localhost since this is only
 * an issue in local dev environments.
 *
 * The substitution happens in [TerrawareOAuthRequestAuthenticator] but in order to make the system
 * use that, we also need to swap out a couple of layers above it.
 */
@Configuration
class HttpLocalhostRedirectConfig {
  @Autowired
  fun injectRequestAuthenticator(filter: KeycloakAuthenticationProcessingFilter) {
    filter.setRequestAuthenticatorFactory(TerrawareRequestAuthenticatorFactory())
  }

  class TerrawareOAuthRequestAuthenticator(
      requestAuthenticator: RequestAuthenticator,
      facade: HttpFacade,
      deployment: KeycloakDeployment,
      sslRedirectPort: Int,
      tokenStore: AdapterSessionStore?
  ) :
      OAuthRequestAuthenticator(
          requestAuthenticator, facade, deployment, sslRedirectPort, tokenStore) {
    private val localhostRegex = Regex("^https?://localhost/")

    override fun stripOauthParametersFromRedirect(): String {
      return useSchemeAndHostFromRedirect(super.stripOauthParametersFromRedirect())
    }

    override fun getRequestUrl(): String {
      return useSchemeAndHostFromRedirect(super.getRequestUrl())
    }

    private fun useSchemeAndHostFromRedirect(originalUrl: String): String {
      val redirectUrl = facade.request.getCookie("KC_REDIRECT")?.value

      return if (redirectUrl != null) {
        originalUrl.replace(localhostRegex, redirectUrl)
      } else {
        originalUrl
      }
    }
  }

  class TerrawareRequestAuthenticator(
      facade: HttpFacade,
      request: HttpServletRequest,
      deployment: KeycloakDeployment,
      tokenStore: AdapterTokenStore,
      sslRedirectPort: Int
  ) : SpringSecurityRequestAuthenticator(facade, request, deployment, tokenStore, sslRedirectPort) {

    override fun createOAuthAuthenticator(): OAuthRequestAuthenticator {
      return TerrawareOAuthRequestAuthenticator(
          this, facade, deployment, sslRedirectPort, tokenStore)
    }
  }

  class TerrawareRequestAuthenticatorFactory : RequestAuthenticatorFactory {
    override fun createRequestAuthenticator(
        facade: HttpFacade,
        request: HttpServletRequest,
        deployment: KeycloakDeployment,
        tokenStore: AdapterTokenStore,
        sslRedirectPort: Int
    ): RequestAuthenticator {
      return TerrawareRequestAuthenticator(facade, request, deployment, tokenStore, sslRedirectPort)
    }
  }
}
