package com.terraformation.backend.auth

import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.DeviceManagerUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.KeycloakUserNotFoundException
import com.terraformation.backend.log.perClassLogger
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletResponse
import org.apache.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Populates [CurrentUserHolder] with the [IndividualUser] or [DeviceManagerUser] for incoming
 * requests.
 */
class TerrawareUserFilter(private val userStore: UserStore) : Filter {
  private val log = perClassLogger()

  override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
    try {
      val authentication = SecurityContextHolder.getContext().authentication

      if (authentication != null) {
        val user =
            try {
              userStore.fetchByAuthId(authentication.name)
            } catch (e: KeycloakUserNotFoundException) {
              if (response is HttpServletResponse) {
                response.sendError(HttpStatus.SC_UNAUTHORIZED)
                return
              } else {
                throw IllegalStateException(
                    "Response is not an HttpServletResponse; unable to return error status",
                )
              }
            }

        CurrentUserHolder.setCurrentUser(user)

        log.trace("Loaded ${user.javaClass.simpleName} for auth ID ${authentication.name}")
      }

      chain.doFilter(request, response)
    } finally {
      CurrentUserHolder.clearCurrentUser()
      log.trace("Cleared IndividualUser")
    }
  }
}
