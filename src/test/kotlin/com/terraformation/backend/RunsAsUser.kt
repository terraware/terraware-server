package com.terraformation.backend

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.auth.SimplePrincipal
import com.terraformation.backend.customer.model.TerrawareUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Convenience interface to run tests that need to control the identity of the current user.
 *
 * The user can be a mock or a stub created by MockK, or it can be a concrete [TerrawareUser] backed
 * by the database. In the latter case, you probably want [RunsAsDatabaseUser] instead of this
 * interface.
 *
 * This is a more lightweight alternative to the Spring Security `@WithUserDetails` annotation,
 * which requires setting up a full Spring application context on the test.
 */
interface RunsAsUser {
  /**
   * User to masquerade as while running tests.
   *
   * For tests that implement [RunsAsDatabaseUser] you'll want to implement this as
   *
   *     override lateinit var user: TerrawareUser
   *
   * and it will be initialized automatically. For tests where you want to use a MockK test double
   * instead of a database-backed user, you'll want to implement this as
   *
   *     override val user: TerrawareUser = mockUser()
   *
   * and then use the MockK API to control the behavior of the stubbed-out user.
   */
  val user: TerrawareUser

  @BeforeEach
  fun setupSecurityContextWithMockUser() {
    SecurityContextHolder.getContext().authentication =
        TestingAuthenticationToken(SimplePrincipal("dummy"), "dummy")
    CurrentUserHolder.setCurrentUser(user)
  }

  @AfterEach
  fun clearSecurityContext() {
    try {
      SecurityContextHolder.clearContext()
    } finally {
      CurrentUserHolder.clearCurrentUser()
    }
  }
}
