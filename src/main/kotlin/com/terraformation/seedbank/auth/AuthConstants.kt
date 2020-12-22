package com.terraformation.seedbank.auth

import io.micronaut.security.authentication.Authentication
import io.micronaut.security.token.config.TokenConfiguration
import java.util.EnumSet

enum class Role {
  AUTHENTICATED,
  API_CLIENT,
  USER,
  ORG_ADMIN,
  SUPER_ADMIN
}

const val ORGANIZATION_ID_ATTR = "organizationId"

// Extension functions to extract data from authentication key/value map.

var Authentication.organizationId
  get() = attributes[ORGANIZATION_ID_ATTR] as? Int
  set(value) {
    attributes[ORGANIZATION_ID_ATTR] = value
  }

val Authentication.roles: EnumSet<Role>
  get() =
      (attributes[TokenConfiguration.DEFAULT_ROLES_NAME] as List<*>?)
          ?.map { Role.valueOf("$it") }
          ?.let { EnumSet.copyOf(it) }
          ?: EnumSet.noneOf(Role::class.java)

/** True if the authenticated client is a super admin. */
val Authentication.isSuperAdmin get() = Role.SUPER_ADMIN in roles
