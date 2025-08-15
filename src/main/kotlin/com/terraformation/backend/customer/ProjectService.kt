package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.ProjectInternalRole
import com.terraformation.backend.db.default_schema.UserId
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
  companion object {
    /** These ProjectInternalRoles should also become TF Contacts. */
    private val TF_CONTACT_PROJECT_ROLES =
        setOf(ProjectInternalRole.ProjectLead, ProjectInternalRole.RestorationLead)

    fun roleShouldBeTfContact(role: ProjectInternalRole?): Boolean {
      return role in TF_CONTACT_PROJECT_ROLES
    }
  }

  fun assignProject(
      projectId: ProjectId,
      accessionIds: Collection<AccessionId>,
      batchIds: Collection<BatchId>,
      plantingSiteIds: Collection<PlantingSiteId>
  ) {
    dslContext.transaction { _ ->
      accessionStore.assignProject(projectId, accessionIds)
      batchStore.assignProject(projectId, batchIds)
      plantingSiteStore.assignProject(projectId, plantingSiteIds)
    }
  }

  fun addInternalUserRole(
      projectId: ProjectId,
      userId: UserId,
      role: ProjectInternalRole? = null,
      roleName: String? = null
  ) {
    val user = userStore.fetchOneById(userId)
    if (user.globalRoles.isEmpty()) {
      throw IllegalStateException("Only global users can be added as project internal roles.")
    }

    projectStore.addInternalUser(projectId, userId, role, roleName)
  }
}
