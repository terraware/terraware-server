package com.terraformation.backend.customer

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.event.UserAddedToProjectEvent
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.UserId
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

/** Project-related business logic that needs to interact with multiple services. */
@ManagedBean
class ProjectService(
    private val dslContext: DSLContext,
    private val publisher: ApplicationEventPublisher,
    private val permissionStore: PermissionStore,
    private val projectStore: ProjectStore,
) {

  fun addUser(projectId: ProjectId, userId: UserId) {
    requirePermissions { addProjectUser(projectId) }

    val project = projectStore.fetchById(projectId) ?: throw ProjectNotFoundException(projectId)
    val organizationId = project.organizationId
    if (organizationId !in permissionStore.fetchOrganizationRoles(userId)) {
      throw IllegalArgumentException(
          "Cannot add user that does not belong to project's organization")
    }

    return dslContext.transactionResult { _ ->
      projectStore.addUser(projectId, userId)

      publisher.publishEvent(UserAddedToProjectEvent(userId, projectId, currentUser().userId))
    }
  }
}
