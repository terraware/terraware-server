package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger

data class SimpleUserModel(
    val userId: UserId,
    val fullName: String = "",
) {
  companion object {
    private val log = perClassLogger()

    private fun emailIsTf(email: String): Boolean =
        email.endsWith(suffix = "@terraformation.com", ignoreCase = true) ||
            email == SystemUser.USERNAME

    fun create(
        userId: UserId,
        fullName: String,
        email: String,
        userIsInSameOrg: Boolean,
        userIsDeleted: Boolean,
        messages: Messages,
    ): SimpleUserModel {
      return when {
        (!userIsDeleted && (userIsInSameOrg || currentUser().canReadUser(userId))) ->
            SimpleUserModel(userId, fullName)
        (emailIsTf(email)) -> SimpleUserModel(userId, messages.terraformationTeam())
        (userIsDeleted) -> SimpleUserModel(userId, messages.formerUser())
        else ->
            SimpleUserModel(userId).also {
              log.debug("User {} cannot read name of user {}", currentUser().userId, userId)
            }
      }
    }
  }
}
