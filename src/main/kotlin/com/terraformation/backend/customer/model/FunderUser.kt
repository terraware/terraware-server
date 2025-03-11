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
import java.util.Locale
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

data class FunderUser(
    val createdTime: Instant,
    override val userId: UserId,
    override val authId: String? = null,
    override val email: String,
    val emailNotificationsEnabled: Boolean = true,
    val firstName: String? = null,
    val lastName: String? = null,
    val countryCode: String? = null,
    val cookiesConsented: Boolean? = null,
    val cookiesConsentedTime: Instant? = null,
    override val locale: Locale? = null,
    override val timeZone: ZoneId? = null,
) : TerrawareUser, UserDetails {
  companion object {
    private val log = perClassLogger()
  }

  override val userType: UserType
    get() = UserType.Funder

  /*
   * Funders are not tied to organizations or facilities so do not have roles related to them
   */
  override val organizationRoles: Map<OrganizationId, Role>
    get() {
      throw NotImplementedError("Funder user does not support enumerating roles")
    }

  override val facilityRoles: Map<FacilityId, Role>
    get() {
      throw NotImplementedError("Funder user does not support enumerating roles")
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
