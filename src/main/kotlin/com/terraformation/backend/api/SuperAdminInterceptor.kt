package com.terraformation.backend.api

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.UserType
import javax.inject.Named
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Allows endpoints to be accessible only by super-admin users. This is used to restrict access to
 * the placeholder admin UI.
 *
 * Any controller class or handler method that has the [RequireSuperAdmin] annotation will return
 * HTTP 403 Forbidden to the client if the current user isn't a super admin.
 */
@Named
class SuperAdminInterceptor : HandlerInterceptor {
  override fun preHandle(
      request: HttpServletRequest,
      response: HttpServletResponse,
      handler: Any
  ): Boolean {
    if (handler is HandlerMethod &&
        (handler.hasMethodAnnotation(RequireSuperAdmin::class.java) ||
            handler.beanType.isAnnotationPresent(RequireSuperAdmin::class.java)) &&
        currentUser().userType != UserType.SuperAdmin) {
      response.sendError(HttpStatus.FORBIDDEN.value(), "Requires administrator privileges.")
      return false
    }

    return super.preHandle(request, response, handler)
  }
}
