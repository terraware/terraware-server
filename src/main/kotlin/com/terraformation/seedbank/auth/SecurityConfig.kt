package com.terraformation.seedbank.auth

import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.config.web.servlet.invoke

@EnableWebSecurity
class SecurityConfig : WebSecurityConfigurerAdapter() {
  override fun configure(http: HttpSecurity) {
    http {
      authorizeRequests { authorize("/api/v1/seedbank/**", anonymous) }
      authorizeRequests { authorize("/api/v1/device/**", fullyAuthenticated) }
      authorizeRequests { authorize("/api/v1/site/**", fullyAuthenticated) }
      httpBasic {}
    }
  }
}
