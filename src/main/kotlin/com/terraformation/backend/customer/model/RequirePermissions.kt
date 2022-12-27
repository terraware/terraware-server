package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.currentUser

/**
 * Requires a user to have a certain set of permissions.
 *
 * This allows us to use a declarative code style to do permission checks, like:
 * ```
 * requirePermissions { createDevice(facilityId) }
 * ```
 *
 * If the user doesn't have the requested permissions, this method will throw an appropriate
 * exception.
 *
 * The permission checks are just the methods of [PermissionRequirements]; see that class for the
 * complete list of available ones or to add new ones.
 *
 * @param user Which user's permission to check. In the vast majority of cases, this should be the
 *   current user, which is the default value; you will generally want to omit this parameter.
 * @param func Permission checks. This is called with a [PermissionRequirements] receiver, so inside
 *   the function, `this` refers to the [PermissionRequirements] rather than the calling class. See
 *   [the Kotlin docs](https://kotlinlang.org/docs/lambdas.html#function-literals-with-receiver) for
 *   more information about how that works.
 */
fun requirePermissions(
    user: TerrawareUser = currentUser(),
    func: PermissionRequirements.() -> Unit,
) {
  PermissionRequirements(user).func()
}
