package com.terraformation.seedbank.auth

import com.terraformation.seedbank.services.emptyEnumSet
import java.util.EnumSet
import org.springframework.security.core.userdetails.User

abstract class ClientIdentity(username: String, val roles: EnumSet<Role>) :
    User(username, "", roles) {
  abstract val organizationId: Long?

  /** True if the authenticated client is a super admin. */
  val isSuperAdmin
    get() = Role.SUPER_ADMIN in roles
}

object AnonymousClient : ClientIdentity("ANONYMOUS", emptyEnumSet()) {
  override val organizationId: Long?
    get() = null
}

class ControllerClientIdentity(override val organizationId: Long) :
    ClientIdentity("controller", EnumSet.of(Role.API_CLIENT))

class LoggedInUserIdentity(
    username: String,
    override val organizationId: Long?,
    roles: EnumSet<Role>
) : ClientIdentity(username, roles)
