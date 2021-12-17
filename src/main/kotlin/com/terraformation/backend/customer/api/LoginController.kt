package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.ws.rs.BadRequestException
import javax.ws.rs.QueryParam
import org.keycloak.adapters.springsecurity.authentication.KeycloakAuthenticationEntryPoint
import org.keycloak.adapters.springsecurity.authentication.KeycloakCookieBasedRedirect
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
@CustomerEndpoint
class LoginController {
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
      redirect: String,
      request: HttpServletRequest,
      response: HttpServletResponse,
  ) {
    val keycloakLoginUri = KeycloakAuthenticationEntryPoint.DEFAULT_LOGIN_URI
    response.addCookie(KeycloakCookieBasedRedirect.createCookieFromRedirectUrl(redirect))

    // If we're behind a load balancer that does SSL termination, we will be receiving the request
    // as HTTP rather than HTTPS, but the redirect needs to be to the HTTPS URL.
    val scheme = request.getHeader("X-Forwarded-Proto") ?: request.scheme
    if (scheme != "http" && scheme != "https") {
      throw BadRequestException("Unsupported URL scheme")
    }

    val host = request.getHeader("Host")?.let { "//$it" } ?: ""
    val url = "$scheme:$host$keycloakLoginUri"

    response.sendRedirect(url)
  }
}
