package com.terraformation.backend.customer

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.OrganizationAbandonedEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserDeletedEvent
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

/** Organization-related business logic that needs to interact with multiple services. */
@ManagedBean
class OrganizationService(
    private val dslContext: DSLContext,
    private val organizationStore: OrganizationStore,
    private val publisher: ApplicationEventPublisher,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  fun addUser(email: String, organizationId: OrganizationId, role: Role): UserId {
    requirePermissions {
      addOrganizationUser(organizationId)
      setOrganizationUserRole(organizationId, role)
    }

    return dslContext.transactionResult { _ ->
      val user = userStore.fetchOrCreateByEmail(email)

      organizationStore.addUser(organizationId, user.userId, role)

      publisher.publishEvent(
          UserAddedToOrganizationEvent(user.userId, organizationId, currentUser().userId))

      user.userId
    }
  }

  fun deleteOrganization(organizationId: OrganizationId) {
    requirePermissions { deleteOrganization(organizationId) }

    dslContext.transaction { _ ->
      val users = organizationStore.fetchUsers(organizationId)
      if (users.size != 1 || users[0].userId != currentUser().userId) {
        throw OrganizationHasOtherUsersException(organizationId)
      }

      organizationStore.removeUser(
          organizationId, currentUser().userId, allowRemovingLastOwner = true)

      log.info("Deleted last owner from organization $organizationId")

      publisher.publishEvent(OrganizationAbandonedEvent(organizationId))
    }
  }

  @EventListener
  fun on(event: UserDeletedEvent) {
    val user = userStore.fetchOneById(event.userId)

    dslContext.transaction { _ ->
      user.organizationRoles.keys.forEach { organizationId ->
        organizationStore.removeUser(organizationId, user.userId, allowRemovingLastOwner = true)

        val remainingUsers = organizationStore.countRoleUsers(organizationId)
        if (remainingUsers.values.none { it > 0 }) {
          publisher.publishEvent(OrganizationAbandonedEvent(organizationId))
        }
      }
    }
  }
}
