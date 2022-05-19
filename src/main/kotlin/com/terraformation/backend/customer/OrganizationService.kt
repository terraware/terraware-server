package com.terraformation.backend.customer

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.OrganizationDeletedEvent
import com.terraformation.backend.customer.event.UserAddedToOrganizationEvent
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationHasOtherUsersException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.log.perClassLogger
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

/** Organization-related business logic that needs to interact with multiple services. */
@ManagedBean
class OrganizationService(
    private val dslContext: DSLContext,
    private val facilityStore: FacilityStore,
    private val messages: Messages,
    private val organizationStore: OrganizationStore,
    private val projectStore: ProjectStore,
    private val publisher: ApplicationEventPublisher,
    private val siteStore: SiteStore,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  fun addUser(
      email: String,
      organizationId: OrganizationId,
      role: Role,
      projectIds: Collection<ProjectId>
  ): UserId {
    requirePermissions {
      addOrganizationUser(organizationId)
      setOrganizationUserRole(organizationId, role)
      projectIds.forEach { addProjectUser(it) }
    }

    val projects =
        projectIds.map { projectStore.fetchById(it) ?: throw ProjectNotFoundException(it) }
    if (projects.any { it.organizationId != organizationId }) {
      throw IllegalArgumentException("Cannot add user to projects from a different organization")
    }

    return dslContext.transactionResult { _ ->
      val user = userStore.fetchOrCreateByEmail(email)

      organizationStore.addUser(organizationId, user.userId, role)

      projectIds.forEach { projectStore.addUser(it, user.userId) }

      publisher.publishEvent(
          UserAddedToOrganizationEvent(user.userId, organizationId, currentUser().userId))

      user.userId
    }
  }

  fun createOrganization(row: OrganizationsRow, createSeedBank: Boolean): OrganizationModel {
    return dslContext.transactionResult { _ ->
      val orgModel = organizationStore.createWithAdmin(row)
      val name = messages.seedBankDefaultName()

      if (!createSeedBank) {
        orgModel
      } else {
        val projectModel =
            projectStore.create(orgModel.id, name, hidden = true, organizationWide = true)
        val siteModel = siteStore.create(SitesRow(projectId = projectModel.id, name = name))
        val facilityModel = facilityStore.create(siteModel.id, FacilityType.SeedBank, name)

        (1..3).forEach { num ->
          facilityStore.createStorageLocation(
              facilityModel.id, "Refrigerator $num", StorageCondition.Refrigerator)
          facilityStore.createStorageLocation(
              facilityModel.id, "Freezer $num", StorageCondition.Freezer)
        }

        orgModel.copy(
            projects =
                listOf(
                    projectModel.copy(
                        sites = listOf(siteModel.copy(facilities = listOf(facilityModel))))))
      }
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

      publisher.publishEvent(OrganizationDeletedEvent(organizationId))
    }
  }
}
