package com.terraformation.backend.customer.model

import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.funder.FundingEntityId
import com.terraformation.backend.util.ResettableLazy
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

data class FunderUser(
    override val createdTime: Instant,
    override val userId: UserId,
    override val authId: String? = null,
    override val email: String,
    override val emailNotificationsEnabled: Boolean = true,
    override val firstName: String? = null,
    override val lastName: String? = null,
    override val countryCode: String? = null,
    override val cookiesConsented: Boolean? = null,
    override val cookiesConsentedTime: Instant? = null,
    override val locale: Locale? = null,
    override val timeZone: ZoneId? = null,
    private val permissionStore: PermissionStore,
) : TerrawareUser {
  private val _fundingEntityId = ResettableLazy { permissionStore.fetchFundingEntity(userId) }
  override val fundingEntityId: FundingEntityId? by _fundingEntityId

  private val _projectIds = ResettableLazy { permissionStore.fetchFundingEntityProjects(userId) }
  private val projectIds: Set<ProjectId> by _projectIds

  override val userType: UserType
    get() = UserType.Funder

  override val defaultPermission: Boolean
    get() = false

  override fun clearCachedPermissions() {
    _fundingEntityId.reset()
    _projectIds.reset()
  }

  override fun canAcceptCurrentDisclaimer() = true

  override fun canDeleteSelf() = true

  override fun canDeleteFunder(userId: UserId) =
      permissionStore.fetchFundingEntity(userId) == fundingEntityId

  override fun canListFundingEntityUsers(entityId: FundingEntityId) = fundingEntityId == entityId

  override fun canListNotifications(organizationId: OrganizationId?) = organizationId == null

  override fun canReadCurrentDisclaimer(): Boolean = true

  override fun canReadFundingEntity(entityId: FundingEntityId) = fundingEntityId == entityId

  override fun canReadProject(projectId: ProjectId) = projectId in projectIds

  override fun canReadProjectFunderDetails(projectId: ProjectId) = projectId in projectIds

  override fun canReadPublishedReports(projectId: ProjectId) = projectId in projectIds

  override fun canReadUser(userId: UserId) =
      userId == this.userId || permissionStore.fetchFundingEntity(userId) == fundingEntityId

  override fun canUpdateFundingEntityUsers(fundingEntityId: FundingEntityId) =
      fundingEntityId == this.fundingEntityId
}
