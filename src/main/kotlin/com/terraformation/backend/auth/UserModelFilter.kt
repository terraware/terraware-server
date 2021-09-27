package com.terraformation.backend.auth

import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.log.perClassLogger
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import org.keycloak.adapters.springsecurity.token.KeycloakAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken

/** Populates [CurrentUserHolder] with the [UserModel] for incoming requests. */
class UserModelFilter(private val userStore: UserStore) : Filter {
  private val log = perClassLogger()

  override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
    try {
      val authentication = SecurityContextHolder.getContext().authentication

      if (authentication is KeycloakAuthenticationToken ||
          authentication is PreAuthenticatedAuthenticationToken) {
        val userModel = userStore.fetchByAuthId(authentication.name)
        CurrentUserHolder.setCurrentUser(userModel)

        log.trace("Loaded UserModel for auth ID ${authentication.name}")
      }

      chain.doFilter(request, response)
    } finally {
      CurrentUserHolder.clearCurrentUser()
      log.trace("Cleared UserModel")
    }
  }
}
