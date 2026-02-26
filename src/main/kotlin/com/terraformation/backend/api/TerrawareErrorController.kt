package com.terraformation.backend.api

import com.terraformation.backend.config.TerrawareServerConfig
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.ws.rs.core.UriBuilder
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.web.servlet.error.ErrorAttributes
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.servlet.ModelAndView

/** Customizes the handling of error responses. */
@Controller
class TerrawareErrorController(
    private val config: TerrawareServerConfig,
    private val errorAttributes: ErrorAttributes,
    serverProperties: ServerProperties,
) : BasicErrorController(errorAttributes, serverProperties.error) {
  /**
   * Redirects the user to the web app if the SSO login endpoint returns HTTP 400. This can happen
   * if the user bookmarks a URL that includes OAuth2 state that's no longer valid. Redirecting to
   * the web app will either show them the web app (if they're logged in) or take them to the login
   * page.
   *
   * For other errors, redirects to the web app and passes the error message in the URL so the
   * frontend code can show it to the user.
   *
   * This is only invoked for requests from clients that say they can accept HTML. API clients that
   * expect JSON responses use a different error-handling path.
   */
  override fun errorHtml(
      request: HttpServletRequest,
      response: HttpServletResponse,
  ): ModelAndView {
    val status = getStatus(request)
    val attrs =
        errorAttributes.getErrorAttributes(
            ServletWebRequest(request),
            ErrorAttributeOptions.of(ErrorAttributeOptions.Include.MESSAGE),
        )

    val redirectUri =
        if (status == HttpStatus.BAD_REQUEST && attrs["path"] == "/sso/login") {
          config.webAppUrl
        } else {
          // The default message, when there's no application-supplied one, is "No message
          // available" which isn't really suitable to show to a user.
          val message =
              when (attrs["message"]) {
                "No message available",
                null -> "An error occurred while processing the request."
                else -> attrs["message"]
              }

          UriBuilder.fromUri(config.htmlErrorUrl).queryParam("message", "{arg1}").build(message)
        }

    return ModelAndView("redirect:$redirectUri")
  }
}
