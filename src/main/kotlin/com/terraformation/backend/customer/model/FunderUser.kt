package com.terraformation.backend.customer.model

import com.terraformation.backend.customer.db.PermissionStore
import com.terraformation.backend.db.default_schema.OrganizationId
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
  private val _fundingEntity = ResettableLazy { permissionStore.fetchFundingEntity(userId) }
  override val fundingEntity: FundingEntityId? by _fundingEntity

  override val userType: UserType
    get() = UserType.Funder

  override val defaultPermission: Boolean
    get() = false

  override fun canDeleteSelf() = true

  override fun canListNotifications(organizationId: OrganizationId?) = organizationId == null

  override fun canReadFundingEntity(entityId: FundingEntityId) = fundingEntity == entityId

  override fun canReadUser(userId: UserId) = userId == this.userId

  override fun canListFundingEntityUsers(entityId: FundingEntityId) = fundingEntity == entityId
}
