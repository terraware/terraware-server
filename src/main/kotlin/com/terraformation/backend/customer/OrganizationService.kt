package com.terraformation.backend.customer

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.OrganizationAbandonedEvent
import com.terraformation.backend.customer.event.ProjectInternalUserAddedEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.event.UserAddedToTerrawareEvent
import com.terraformation.backend.customer.event.UserDeletionStartedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.InvalidTerraformationContactEmail
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.UserNotFoundForEmailException
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
    val isTerraformationContact = role === Role.TerraformationContact

    requirePermissions {
      if (isTerraformationContact) {
        addTerraformationContact(organizationId)
      } else {
        addOrganizationUser(organizationId)
        setOrganizationUserRole(organizationId, role)
      }
    }

    if (isTerraformationContact &&
        !email.endsWith(suffix = "@terraformation.com", ignoreCase = true)) {
      throw InvalidTerraformationContactEmail(email)
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

  /**
   * Assigns a Terraformation Contact in an organization, for an existing Terraformation user. If
   * user does not exist, this function will throw an exception. If email of user to assign as
   * Terraformation Contact already exists as an organization user, the role is simply updated.
   * Otherwise, a new user is created and added as the Terraformation Contact.
   *
   * @param email, email of user to assign as Terraformation Contact
   * @param organizationId, organization in which to assign the Terraformation Contact
   * @return id of user that was assigned as Terraformation Contact
   * @throws UserNotFoundForEmailException
   */
  fun assignTerraformationContact(email: String, organizationId: OrganizationId): UserId {
    requirePermissions { addTerraformationContact(organizationId) }

    val existingUser = userStore.fetchByEmail(email) ?: throw UserNotFoundForEmailException(email)

    assignTerraformationContact(existingUser.userId, organizationId)

    return existingUser.userId
  }

  /**
   * Assigns an existing user to an organization as its Terraformation Contact. If the user already
   * exists as an organization user, the role is simply updated. Otherwise, the user is added as the
   * Terraformation Contact.
   */
  fun assignTerraformationContact(userId: UserId, organizationId: OrganizationId) {
    requirePermissions { addTerraformationContact(organizationId) }

    dslContext.transaction { _ ->
      val orgUserExists =
          dslContext
              .selectOne()
              .from(ORGANIZATION_USERS)
              .where(ORGANIZATION_USERS.ORGANIZATION_ID.eq(organizationId))
              .and(ORGANIZATION_USERS.USER_ID.eq(userId))
              .fetch()
              .isNotEmpty
      if (orgUserExists) {
        organizationStore.setUserRole(organizationId, userId, Role.TerraformationContact)
      } else {
        organizationStore.addUser(organizationId, userId, Role.TerraformationContact)
      }
    }
  }

  fun deleteOrganization(organizationId: OrganizationId) {
    requirePermissions { deleteOrganization(organizationId) }

    dslContext.transaction { _ ->
      val allUsers = organizationStore.fetchUsers(organizationId)

      // Fetch all users that aren't a Terraformation Contact (which cannot be removed by org
      // owners in the client). This allows us to check for the last remaining Owner.
      val users = allUsers.filter { user -> user.role != Role.TerraformationContact }
      if (users.size != 1 || users[0].userId != currentUser().userId) {
        throw OrganizationHasOtherUsersException(organizationId)
      }

      // The backend will handle deletion of the Terraformation Contact when the organization is
      // being deleted.
      val tfContact = allUsers.findLast { user -> user.role == Role.TerraformationContact }
      if (tfContact != null) {
        systemUser.run { organizationStore.removeUser(organizationId, tfContact.userId) }
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

  @EventListener
  fun on(event: ProjectInternalUserAddedEvent) {
    val userId = event.userId
    val organizationId = event.organizationId
    val role = event.role

    if (ProjectService.roleShouldBeTfContact(role)) {
      assignTerraformationContact(userId, organizationId)
    }
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
