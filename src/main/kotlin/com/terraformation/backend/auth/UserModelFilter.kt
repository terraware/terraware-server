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

/** Populates [CurrentUserHolder] with the [UserModel] for incoming requests. */
class UserModelFilter(private val userStore: UserStore) : Filter {
  private val log = perClassLogger()

  override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
    try {
      val keycloakUser = SecurityContextHolder.getContext().authentication

      if (keycloakUser is KeycloakAuthenticationToken) {
        val userModel = userStore.fetchByAuthId(keycloakUser.name)
        CurrentUserHolder.setCurrentUser(userModel)

        log.trace("Loaded UserModel for auth ID ${keycloakUser.name}")
      }

      chain.doFilter(request, response)
    } finally {
      CurrentUserHolder.clearCurrentUser()
      log.trace("Cleared UserModel")
    }
  }
}
