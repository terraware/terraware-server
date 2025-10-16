package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.UserId

data class SimpleUserModel(
    val userId: UserId,
    val firstName: String? = null,
    val lastName: String? = null,
) {
  companion object {
    private fun emailIsTf(email: String): Boolean =
        email.endsWith(suffix = "@terraformation.com", ignoreCase = true) ||
            email == SystemUser.USERNAME

    fun create(
        userId: UserId,
        firstName: String,
        lastName: String,
        email: String,
        userIsInSameOrg: Boolean,
    ): SimpleUserModel {
      return when {
        (userIsInSameOrg || currentUser().canReadUser(userId)) ->
            SimpleUserModel(userId, firstName, lastName)
        (emailIsTf(email)) -> SimpleUserModel(userId, "Terraformation", "Team")
        else ->
            throw IllegalArgumentException("User is not in same organization and cannot be viewed")
      }
    }
  }
}
