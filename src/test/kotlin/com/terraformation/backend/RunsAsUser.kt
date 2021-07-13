package com.terraformation.backend

import com.terraformation.backend.customer.model.UserModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken

/**
 * Convenience interface to run test methods with the current user set to a test double. Typically
 * the double will be a mock or a stub created by MockK. This interface just saves you the trouble
 * of having to explicitly set up the security context for each test method.
 *
 * This is a more lightweight alternative to the Spring Security `@WithUserDetails` annotation,
 * which requires setting up a full Spring application context on the test.
 */
interface RunsAsUser {
  /**
   * User to masquerade as while running tests. Typically, you'll want to define this as
   *
   * ```
   * override val user = mockk<TerrawareUserDetails>()
   * ```
   *
   * and then use the MockK API to control the behavior of the stubbed-out user.
   */
  val user: UserModel

  @BeforeEach
  fun setupSecurityContextWithMockUser() {
    SecurityContextHolder.getContext().authentication =
        PreAuthenticatedAuthenticationToken(user, "N/A")
  }

  @AfterEach
  fun clearSecurityContext() {
    SecurityContextHolder.clearContext()
  }
}
