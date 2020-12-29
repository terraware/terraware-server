package com.terraformation.seedbank.auth

import io.micronaut.security.authentication.Authentication
import io.micronaut.security.authentication.UserDetails
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

private fun roleStringsToEnumSet(names: Collection<*>?): EnumSet<Role> {
  return names?.map { Role.valueOf("$it") }?.let { EnumSet.copyOf(it) }
      ?: EnumSet.noneOf(Role::class.java)
}

val Authentication.roles: EnumSet<Role>
  get() = roleStringsToEnumSet(attributes[TokenConfiguration.DEFAULT_ROLES_NAME] as List<*>?)

val UserDetails.roleSet: EnumSet<Role>
  get() = roleStringsToEnumSet(roles)

/** True if the authenticated client is a super admin. */
val Authentication.isSuperAdmin
  get() = Role.SUPER_ADMIN in roles

/** JWT claim with list of subscribable MQTT topics. Defined by Mosquitto JWT plugin. */
const val JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM = "subs"

/** JWT claim with list of publishable MQTT topics. Defined by Mosquitto JWT plugin. */
const val JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM = "publ"
