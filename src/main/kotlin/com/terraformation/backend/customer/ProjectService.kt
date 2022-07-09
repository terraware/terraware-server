package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.UserId
import javax.annotation.ManagedBean
import org.jooq.DSLContext

/** Project-related business logic that needs to interact with multiple services. */
@ManagedBean
class ProjectService(
    private val dslContext: DSLContext,
    private val permissionStore: PermissionStore,
    private val projectStore: ProjectStore,
) {

  fun addUser(projectId: ProjectId, userId: UserId) {
    requirePermissions { addProjectUser(projectId) }

    val project = projectStore.fetchOneById(projectId)
    val organizationId = project.organizationId
    if (organizationId !in permissionStore.fetchOrganizationRoles(userId)) {
      throw IllegalArgumentException(
          "Cannot add user that does not belong to project's organization")
    }

    dslContext.transaction { _ -> projectStore.addUser(projectId, userId) }
  }
}
