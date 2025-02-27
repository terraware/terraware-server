package com.terraformation.backend.customer.model

import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.log.perClassLogger
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class FunderUser(
    val createdTime: Instant,
    override val userId: UserId,
    override val authId: String?,
    val email: String,
    val emailNotificationsEnabled: Boolean,
    val firstName: String?,
    val lastName: String?,
    val cookiesConsented: Boolean?,
    val cookiesConsentedTime: Instant?,
) : TerrawareUser, UserDetails {
  companion object {
    private val log = perClassLogger()
  }

  override val timeZone: ZoneId
    get() = ZoneOffset.UTC

  override val userType: UserType
    get() = UserType.Funder

  /*
   * Funders are not tied to organizations or facilities so do not have roles related to them
   */
  override val organizationRoles: Map<OrganizationId, Role>
    get() {
      throw NotImplementedError("System user does not support enumerating roles")
    }

  override val facilityRoles: Map<FacilityId, Role>
    get() {
      throw NotImplementedError("System user does not support enumerating roles")
    }

  override val globalRoles: Set<GlobalRole>
    get() = emptySet()

  override fun getName(): String = authId ?: throw IllegalStateException("User is unregistered")

  override fun getUsername(): String = authId ?: throw IllegalStateException("User is unregistered")

  override fun isAccountNonExpired(): Boolean = true

  override fun isAccountNonLocked(): Boolean = true

  override fun isCredentialsNonExpired(): Boolean = true

  override fun isEnabled(): Boolean = true

  override fun <T> run(func: () -> T): T = CurrentUserHolder.runAs(this, func, authorities)

  override fun hasAnyAdminRole(): Boolean = false

  override fun getAuthorities(): MutableCollection<out GrantedAuthority> {
    return mutableSetOf()
  }

  override fun getPassword(): String {
    log.warn("Something is trying to get the password of an OAuth2 user")
    return ""
  }
}
