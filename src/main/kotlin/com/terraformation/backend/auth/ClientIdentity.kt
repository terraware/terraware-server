package com.terraformation.backend.auth

import com.terraformation.backend.db.OrganizationId
import java.util.*
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.User

abstract class ClientIdentity(username: String, val authorities: Set<GrantedAuthority>) :
    User(username, "", authorities) {
  abstract val organizationId: OrganizationId?

  /** True if the authenticated client is a super admin. */
  val isSuperAdmin
    get() = SuperAdminAuthority in authorities
}

object AnonymousClient : ClientIdentity("ANONYMOUS", emptySet()) {
  override val organizationId: OrganizationId?
    get() = null
}

class ControllerClientIdentity(override val organizationId: OrganizationId) :
    ClientIdentity("controller", emptySet())

class LoggedInUserIdentity(
    username: String,
    override val organizationId: OrganizationId?,
    authorities: Collection<GrantedAuthority>
) : ClientIdentity(username, authorities.toSet())
