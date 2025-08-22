package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.ProjectInternalUserModel
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.nursery.db.BatchStore
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class ProjectService(
    private val accessionStore: AccessionStore,
    private val batchStore: BatchStore,
    private val dslContext: DSLContext,
    private val plantingSiteStore: PlantingSiteStore,
    private val projectStore: ProjectStore,
    private val userStore: UserStore,
) {
  fun assignProject(
      projectId: ProjectId,
      accessionIds: Collection<AccessionId>,
      batchIds: Collection<BatchId>,
      plantingSiteIds: Collection<PlantingSiteId>,
  ) {
    dslContext.transaction { _ ->
      accessionStore.assignProject(projectId, accessionIds)
      batchStore.assignProject(projectId, batchIds)
      plantingSiteStore.assignProject(projectId, plantingSiteIds)
    }
  }

  fun updateInternalUsers(projectId: ProjectId, internalUsers: List<ProjectInternalUserModel>) {
    val users = userStore.fetchManyById(internalUsers.map { it.userId })
    users.forEach { user ->
      if (user.globalRoles.isEmpty()) {
        throw IllegalStateException("User ${user.userId} has no global roles.")
      }
    }
    val existingInternalUsers = projectStore.fetchInternalUsers(projectId)
    val existingUsersMap = existingInternalUsers.associateBy { it.userId }
    val desiredUsersMap = internalUsers.associateBy { it.userId }

    val usersToRemove =
        existingInternalUsers
            .filter { existingUser ->
              val finalUser = desiredUsersMap[existingUser.userId]
              finalUser == null ||
                  finalUser.role != existingUser.projectInternalRoleId ||
                  finalUser.roleName != existingUser.roleName
            }
            .mapNotNull { it.userId }

    val usersToAdd =
        internalUsers.filter { finalUser ->
          val existingUser = existingUsersMap[finalUser.userId]
          existingUser == null ||
              existingUser.projectInternalRoleId != finalUser.role ||
              existingUser.roleName != finalUser.roleName
        }

    projectStore.removeInternalUsers(projectId, usersToRemove)
    projectStore.addInternalUsers(projectId, usersToAdd)
  }
}
