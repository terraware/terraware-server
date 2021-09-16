package com.terraformation.backend.auth

import com.terraformation.backend.customer.model.UserModel
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder

/**
 * Returns the details for the user who made the current request.
 *
 * Under the covers, this uses a thread-local variable. Generally, that's fine, since we process
 * each request on its own thread. The variable is populated by [UserModelFilter].
 *
 * One case where this can fail is if code is running outside the context of a request handler, such
 * as in a unit test or a command-line tool. In that case, nothing will have set the thread-local
 * variable.
 *
 * Another problem case is code that passes work to a thread pool, either explicitly or via Kotlin
 * coroutines. The pooled threads won't automatically inherit the original thread's thread-local
 * variables, and you'll have to explicitly pass the user details to the code that runs in the
 * worker thread.
 *
 * In either case, rather than messing with the thread-local variable yourself, you can just wrap
 * your code in [UserModel.run].
 *
 * Design note: This is a top-level function rather than a method on [UserModel] purely to keep the
 * calling code less cluttered, because getting the current user is a very common operation and it
 * reads from global state anyway.
 */
fun currentUser(): UserModel {
  return CurrentUserHolder.getCurrentUser()
      ?: throw AccessDeniedException("Authenticated user required")
}

/**
 * Makes the current [UserModel] available to application code. This is modeled after Spring's
 * [SecurityContextHolder] class: it uses a thread-local variable to hold the model. The variable is
 * populated before the request handler is invoked, then cleared afterwards.
 */
object CurrentUserHolder {
  private val userModelHolder = InheritableThreadLocal<UserModel>()

  fun getCurrentUser(): UserModel? {
    return userModelHolder.get()
  }

  fun setCurrentUser(userModel: UserModel?) {
    if (userModel != null) {
      userModelHolder.set(userModel)
    } else {
      clearCurrentUser()
    }
  }

  fun clearCurrentUser() {
    userModelHolder.remove()
  }
}
