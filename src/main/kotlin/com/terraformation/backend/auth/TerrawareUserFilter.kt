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
import jakarta.servlet.http.HttpServletRequest
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
        try {
          val user = userStore.fetchByAuthId(authentication.name)
          CurrentUserHolder.setCurrentUser(user)
          log.trace("Loaded ${user.javaClass.simpleName} for auth ID ${authentication.name}")
        } catch (e: KeycloakUserNotFoundException) {
          log.debug("User ${authentication.name} no longer exists in Keycloak; clearing session")
          (request as? HttpServletRequest)?.getSession(false)?.invalidate()
          SecurityContextHolder.clearContext()
        }
      }

      chain.doFilter(request, response)
    } finally {
      CurrentUserHolder.clearCurrentUser()
      log.trace("Cleared IndividualUser")
    }
  }
}
