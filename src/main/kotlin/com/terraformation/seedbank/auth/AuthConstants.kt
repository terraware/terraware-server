package com.terraformation.seedbank.auth

import org.springframework.security.core.GrantedAuthority

enum class Role : GrantedAuthority {
  AUTHENTICATED,
  API_CLIENT,
  USER,
  ORG_ADMIN,
  SUPER_ADMIN;

  override fun getAuthority() = toString()
}

/** JWT claim with list of subscribable MQTT topics. Defined by Mosquitto JWT plugin. */
const val JWT_MQTT_SUBSCRIBABLE_TOPICS_CLAIM = "subs"

/** JWT claim with list of publishable MQTT topics. Defined by Mosquitto JWT plugin. */
const val JWT_MQTT_PUBLISHABLE_TOPICS_CLAIM = "publ"
