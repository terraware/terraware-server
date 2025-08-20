package com.terraformation.backend.api

import com.terraformation.backend.auth.currentUser
import jakarta.inject.Named
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Allows endpoints to be accessible only by users with specific global roles. This is used to
 * restrict access to the placeholder admin UI.
 *
 * Any controller class or handler method that has the [RequireGlobalRole] annotation will return
 * HTTP 403 Forbidden to the client if the current user doesn't have one of the specified global
 * roles.
 */
@Named
class GlobalRoleInterceptor : HandlerInterceptor {
  override fun preHandle(
      request: HttpServletRequest,
      response: HttpServletResponse,
      handler: Any,
  ): Boolean {
    if (handler is HandlerMethod) {
      val requiredRoles =
          handler.getMethodAnnotation(RequireGlobalRole::class.java)?.roles
              ?: handler.beanType.getAnnotation(RequireGlobalRole::class.java)?.roles

      if (!requiredRoles.isNullOrEmpty()) {
        val userRoles = currentUser().globalRoles

        if (requiredRoles.none { it in userRoles }) {
          response.sendError(HttpStatus.FORBIDDEN.value(), "Requires administrator privileges.")
          return false
        }
      }
    }

    return super.preHandle(request, response, handler)
  }
}
