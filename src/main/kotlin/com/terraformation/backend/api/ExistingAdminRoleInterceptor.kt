package com.terraformation.backend.api

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import javax.inject.Named
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Allows endpoints to be accessible only by users who are the admin or owner of at least one
 * organization. This is used to restrict access to the placeholder admin UI.
 *
 * Any controller class or handler method that has the [RequireExistingAdminRole] annotation will
 * return HTTP 403 Forbidden to the client if the current user doesn't have an admin or owner role
 * in any organizations.
 *
 * Since the admin UI is often used in development environments to bootstrap an empty database, the
 * admin requirement can be disabled using the [TerrawareServerConfig.allowAdminUiForNonAdmins]
 * configuration option.
 */
@Named
class ExistingAdminRoleInterceptor(private val config: TerrawareServerConfig) : HandlerInterceptor {
  override fun preHandle(
      request: HttpServletRequest,
      response: HttpServletResponse,
      handler: Any
  ): Boolean {
    if (!config.allowAdminUiForNonAdmins &&
        handler is HandlerMethod &&
        (handler.hasMethodAnnotation(RequireExistingAdminRole::class.java) ||
            handler.beanType.isAnnotationPresent(RequireExistingAdminRole::class.java)) &&
        !currentUser().hasAnyAdminRole()) {
      response.sendError(
          HttpStatus.FORBIDDEN.value(), "Requires organization administrator privileges.")
      return false
    }

    return super.preHandle(request, response, handler)
  }
}
