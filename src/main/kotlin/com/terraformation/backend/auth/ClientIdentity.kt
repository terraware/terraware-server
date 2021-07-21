package com.terraformation.backend.auth

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.util.emptyEnumSet
import java.util.EnumSet
import org.springframework.security.core.userdetails.User

abstract class ClientIdentity(username: String, val roles: EnumSet<Role>) :
    User(username, "", roles) {
  abstract val organizationId: OrganizationId?

  /** True if the authenticated client is a super admin. */
  val isSuperAdmin
    get() = Role.SUPER_ADMIN in roles
}

object AnonymousClient : ClientIdentity("ANONYMOUS", emptyEnumSet()) {
  override val organizationId: OrganizationId?
    get() = null
}

class ControllerClientIdentity(override val organizationId: OrganizationId) :
    ClientIdentity("controller", EnumSet.of(Role.API_CLIENT))

class LoggedInUserIdentity(
    username: String,
    override val organizationId: OrganizationId?,
    roles: EnumSet<Role>
) : ClientIdentity(username, roles)
