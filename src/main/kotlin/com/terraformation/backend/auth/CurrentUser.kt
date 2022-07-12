package com.terraformation.backend.auth

import com.terraformation.backend.customer.model.DeviceManagerUser
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken

/**
 * Returns the details for the user on whose behalf the current thread is doing work. For code
 * that's handling an API request, this is the [IndividualUser] for the individual user or
 * [DeviceManagerUser] for the API client that made the request. For automated or background jobs,
 * it may also be the [SystemUser].
 *
 * Under the covers, this uses a thread-local variable. For incoming requests, that's fine, since we
 * process each request on its own thread. The variable is populated by [TerrawareUserFilter], which
 * runs before the controller method is executed.
 *
 * Code that runs outside the context of a request handler, such as in a unit test or a command-line
 * tool, needs to run as a user if it performs any operations that are protected by permission
 * checks. The easiest way to do that is to wrap the code in [TerrawareUser.run].
 *
 * This also applies to code that passes work to a thread pool, either explicitly or via Kotlin
 * coroutines. The pooled threads won't automatically inherit the original thread's thread-local
 * variables. If you're using suspending functions with a thread pool, make sure you understand
 * whether and when the code can resume on a different thread than its initial one.
 *
 * Design note: This is a top-level function rather than a method on [IndividualUser] purely to keep
 * the calling code less cluttered, because getting the current user is a very common operation and
 * it reads from global state anyway.
 */
fun currentUser(): TerrawareUser {
  return CurrentUserHolder.getCurrentUser()
      ?: throw AccessDeniedException("Authenticated user required")
}

/**
 * Makes the current [TerrawareUser] available to application code. This is modeled after Spring's
 * [SecurityContextHolder] class: it uses a thread-local variable to hold the model. The variable is
 * populated before the request handler is invoked, then cleared afterwards.
 */
object CurrentUserHolder {
  private val userHolder = InheritableThreadLocal<TerrawareUser>()

  fun getCurrentUser(): TerrawareUser? {
    return userHolder.get()
  }

  fun setCurrentUser(user: TerrawareUser?) {
    if (user != null) {
      userHolder.set(user)
    } else {
      clearCurrentUser()
    }
  }

  fun clearCurrentUser() {
    userHolder.remove()
  }

  /**
   * Runs some code as a particular user. The current user (if any) is restored when [func]
   * finishes.
   *
   * You will usually want to call [TerrawareUser.run] instead of this.
   */
  fun <T> runAs(
      user: TerrawareUser,
      func: () -> T,
      authorities: Collection<GrantedAuthority> = emptyList(),
  ): T {
    val context = SecurityContextHolder.getContext()
    val oldAuthentication = context.authentication
    val oldUser = getCurrentUser()
    val newAuthentication = PreAuthenticatedAuthenticationToken(user, "N/A", authorities)
    try {
      context.authentication = newAuthentication
      setCurrentUser(user)
      return func()
    } finally {
      try {
        context.authentication = oldAuthentication
      } finally {
        setCurrentUser(oldUser)
      }
    }
  }
}
