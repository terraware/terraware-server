package com.terraformation.backend.auth

import org.springframework.security.core.GrantedAuthority

enum class Role : GrantedAuthority {
  AUTHENTICATED,
  API_CLIENT,
  USER,
  ORG_ADMIN,
  SUPER_ADMIN;

  override fun getAuthority() = toString()
}
