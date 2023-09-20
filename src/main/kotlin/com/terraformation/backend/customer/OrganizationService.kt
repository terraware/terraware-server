package com.terraformation.backend.customer

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.OrganizationAbandonedEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import org.jobrunr.scheduling.JobScheduler
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener

/** Organization-related business logic that needs to interact with multiple services. */
@Named
class OrganizationService(
    private val dslContext: DSLContext,
    private val organizationStore: OrganizationStore,
    private val publisher: ApplicationEventPublisher,
    @Lazy private val scheduler: JobScheduler,
    private val systemUser: SystemUser,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  fun addUser(email: String, organizationId: OrganizationId, role: Role): UserId {
    requirePermissions {
      if (role == Role.TerraformationContact) {
        addTerraformationContact(organizationId)
      } else {
        addOrganizationUser(organizationId)
        setOrganizationUserRole(organizationId, role)
      }
    }

    return dslContext.transactionResult { _ ->
      val isNewUser =
          try {
            userStore.fetchByEmail(email) == null
          } catch (e: Exception) {
            false
          }

      val user = userStore.fetchOrCreateByEmail(email)

      organizationStore.addUser(organizationId, user.userId, role)

      if (isNewUser) {
        publisher.publishEvent(
            UserAddedToTerrawareEvent(
                userId = user.userId,
                organizationId = organizationId,
                addedBy = currentUser().userId,
            ),
        )
      } else {
        publisher.publishEvent(
            UserAddedToOrganizationEvent(
                userId = user.userId,
                organizationId = organizationId,
                addedBy = currentUser().userId,
            ),
        )
      }

      user.userId
    }
  }

  fun assignTerraformationContact(organizationId: OrganizationId, email: String): UserId {
    return dslContext.transactionResult { _ ->
      val currentTfContactUserId = organizationStore.fetchTerraformationContact(organizationId)
      if (currentTfContactUserId != null) {
        organizationStore.removeUser(organizationId, currentTfContactUserId)
      }
      val existingUser = userStore.fetchByEmail(email)
      val orgUserExists =
          if (existingUser != null) {
            dslContext
                .selectOne()
                .from(ORGANIZATION_USERS)
                .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
                .and(ORGANIZATION_USERS.USER_ID.eq(existingUser.userId))
                .fetch()
                .isNotEmpty
          } else {
            false
          }
      val result =
          if (orgUserExists) {
            organizationStore.setUserRole(
                organizationId, existingUser!!.userId, Role.TerraformationContact)
            existingUser.userId
          } else {
            addUser(email, organizationId, Role.TerraformationContact)
          }
      result
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
    }
  }

  @EventListener
  fun on(event: UserDeletionStartedEvent) {
    val organizationIds = organizationStore.fetchOrganizationIds(event.userId)

    dslContext.transaction { _ ->
      organizationIds.forEach { organizationId ->
        organizationStore.removeUser(organizationId, event.userId, allowRemovingLastOwner = true)
      }
    }
  }

  @EventListener
  fun on(event: OrganizationAbandonedEvent) {
    val organizationId = event.organizationId

    // Schedule the actual deletion via JobRunr rather than just spawning a thread so it will be
    // retried if the server is killed before it finishes.
    scheduler.enqueue<OrganizationService> { deleteAbandonedOrganization(organizationId) }
  }

  /**
   * Purges all of an organization's data from the system. This should always be run asynchronously
   * since it may take a long time if the organization has lots of data such as photos or sensor
   * readings.
   */
  @Suppress("MemberVisibilityCanBePrivate") // Needs to be public for JobRunr
  fun deleteAbandonedOrganization(organizationId: OrganizationId) {
    log.info("Deleting organization $organizationId (may take a while)")

    systemUser.run { organizationStore.delete(organizationId) }

    log.info("Finished deleting organization $organizationId")
  }
}
