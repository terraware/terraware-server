package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.net.URI
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.ws.rs.QueryParam
import org.keycloak.adapters.springsecurity.authentication.KeycloakAuthenticationEntryPoint
import org.keycloak.adapters.springsecurity.authentication.KeycloakCookieBasedRedirect
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
@CustomerEndpoint
class LoginController {
  private val keycloakLoginUri = KeycloakAuthenticationEntryPoint.DEFAULT_LOGIN_URI

  @ApiResponse(
      responseCode = "302",
      description =
          "Redirects to a login page. After login, the user will be redirected back to the URL " +
              "specified in the \"redirect\" parameter.")
  @GetMapping("/api/v1/login")
  @Operation(
      summary = "Redirects to a login page.",
      description =
          "For interactive web applications, this can be used to redirect the user to a login " +
              "page to allow the application to make other API requests. The login process will " +
              "set a cookie that will authenticate to the API, and will then redirect back to " +
              "the application. One approach is to use this in error response handlers: if an " +
              "API request returns HTTP 401 Unauthorized, set location.href to this endpoint " +
              "and set \"redirect\" to the URL of the page the user was on so they'll return " +
              "there after logging in.")
  fun login(
      @QueryParam("redirect")
      @Schema(
          description =
              "URL to redirect to after login. The list of valid redirect URLs is restricted; " +
                  "this must be the URL of a Terraware web application.")
      redirect: URI,
      request: HttpServletRequest,
      response: HttpServletResponse,
  ) {
    response.addCookie(KeycloakCookieBasedRedirect.createCookieFromRedirectUrl(redirect.toString()))

    // We'll be redirecting to a Keycloak endpoint /sso/login which will take care of the OAuth
    // handshaking. If we just redirect to the path, the server will grab the protocol and hostname
    // from the current HTTP request to construct the full URL for the Location header. That's fine
    // in production, but if you're using a local dev environment that proxies API requests to the
    // production or staging servers, the resulting redirect would point at the actual server rather
    // than the proxy, and cookies would be assigned to the wrong domain.
    //
    // Instead, use the protocol, host, and port from the redirect URL, on the assumption that it
    // will also be a valid place to send API requests.
    //
    // This assumption will break if we ever want API requests to be served from a different host
    // than the web app itself, but for now we don't want to do that.

    response.sendRedirect(redirect.resolve(keycloakLoginUri).toString())
  }
}
