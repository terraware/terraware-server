package com.terraformation.backend.customer

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.AcceleratorAdminInvitedEvent
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.GlobalRole
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

/** User-related business logic that spans multiple stores or fires events. */
@Named
class UserService(
    private val dslContext: DSLContext,
    private val publisher: ApplicationEventPublisher,
    private val userStore: UserStore,
) {
  /**
   * Invites a user to become an admin with a set of global roles. If the email is unknown, creates
   * an unregistered user row and publishes [AcceleratorAdminInvitedEvent] so a registration email
   * gets sent. If the email already maps to a user, only the role set is replaced; no event is
   * published.
   */
  fun inviteAdmin(email: String, globalRoles: Set<GlobalRole>): IndividualUser {
    if (globalRoles.isEmpty()) {
      throw IllegalArgumentException("Must specify at least one global role")
    }

    requirePermissions { updateSpecificGlobalRoles(globalRoles) }

    if (!email.endsWith("@terraformation.com", ignoreCase = true)) {
      throw IllegalArgumentException("Only @terraformation.com emails may be invited as admins")
    }

    return dslContext.transactionResult { _ ->
      val isNewUser = userStore.fetchByEmail(email) == null
      val user = userStore.fetchOrCreateByEmail(email)

      userStore.updateGlobalRoles(setOf(user.userId), globalRoles)

      if (isNewUser) {
        publisher.publishEvent(
            AcceleratorAdminInvitedEvent(
                userId = user.userId,
                email = user.email,
                invitedBy = currentUser().userId,
            )
        )
      }

      userStore.fetchOneById(user.userId) as IndividualUser
    }
  }
}
