package com.terraformation.backend.auth

import org.springframework.security.core.GrantedAuthority

/**
 * Indicates that a user is a super administrator with access to privileged operations. This is an
 * application-wide privilege, not tied to a particular organization.
 *
 * Unlike organization-level roles, which are purely used by our own code, this object is visible to
 * Spring Security. It can thus be used to protect privileged API endpoints using Spring Security's
 * role-based access control features.
 */
object SuperAdminAuthority : GrantedAuthority {
  override fun getAuthority(): String {
    return "ROLE_SUPER_ADMIN"
  }

  @Suppress("unused") // GrantedAuthority extends Serializable
  private fun readResolve(): Any = SuperAdminAuthority
}
