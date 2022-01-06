package com.terraformation.backend.customer

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.InvitationNotFoundException
import com.terraformation.backend.db.InvitationTooRecentException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.UserAlreadyInOrganizationException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext

/** Organization-related business logic that needs to interact with multiple services. */
@ManagedBean
class OrganizationService(
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val emailService: EmailService,
    private val organizationStore: OrganizationStore,
    private val projectStore: ProjectStore,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  fun invite(
      email: String,
      organizationId: OrganizationId,
      role: Role,
      projectIds: Collection<ProjectId>
  ) {
    requirePermissions {
      addOrganizationUser(organizationId)
      setOrganizationUserRole(organizationId, role)
      projectIds.forEach { addProjectUser(it) }
    }

    val projects =
        projectIds.map { projectStore.fetchById(it) ?: throw ProjectNotFoundException(it) }
    if (projects.any { it.organizationId != organizationId }) {
      throw IllegalArgumentException("Cannot invite user to projects from a different organization")
    }

    dslContext.transaction { _ ->
      val userModel = userStore.fetchOrCreateByEmail(email)

      organizationStore.addUser(organizationId, userModel.userId, role, pending = true)

      projectIds.forEach { projectStore.addUser(it, userModel.userId) }

      // Send email in the transaction so the user will be rolled back if we couldn't notify them
      // about being invited, e.g., because the email address was malformed.
      emailService.sendInvitation(organizationId, userModel.userId)
    }
  }

  /**
   * Resends the invitation message to a user with a pending invitation to an organization.
   *
   * @throws InvitationNotFoundException There is no pending invitation for the user.
   * @throws InvitationTooRecentException There is a pending invitation but not enough time has
   * passed since the most recent invitation message was sent.
   * @throws UserAlreadyInOrganizationException The user is in the organization and doesn't have a
   * pending invitation.
   */
  fun resendInvitation(organizationId: OrganizationId, userId: UserId) {
    requirePermissions { addOrganizationUser(organizationId) }

    try {
      organizationStore.updatePendingInvitation(
          organizationId, userId, config.minimumInvitationInterval) {
        log.info("Resending organization $organizationId invitation to user $userId")
        emailService.sendInvitation(organizationId, userId)
      }
    } catch (e: UserNotFoundException) {
      throw InvitationNotFoundException(userId, organizationId)
    }
  }
}
