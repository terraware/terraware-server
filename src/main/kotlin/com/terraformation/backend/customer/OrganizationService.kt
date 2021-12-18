package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.email.EmailService
import javax.annotation.ManagedBean
import org.jooq.DSLContext

/** Organization-related business logic that needs to interact with multiple services. */
@ManagedBean
class OrganizationService(
    private val dslContext: DSLContext,
    private val emailService: EmailService,
    private val organizationStore: OrganizationStore,
    private val projectStore: ProjectStore,
    private val userStore: UserStore,
) {
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
}
