package com.terraformation.backend.auth

import com.terraformation.backend.VERSION
import org.springframework.context.annotation.Bean
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.config.web.servlet.invoke
import org.springframework.security.web.header.writers.StaticHeadersWriter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@EnableWebSecurity
class SecurityConfig : WebSecurityConfigurerAdapter() {
  override fun configure(http: HttpSecurity) {
    http {
      cors {}
      csrf { disable() }
      authorizeRequests { authorize("/api/v1/seedbank/**", anonymous) }
      authorizeRequests { authorize("/api/v1/device/**", fullyAuthenticated) }
      authorizeRequests { authorize("/api/v1/mqtt/**", fullyAuthenticated) }
      authorizeRequests { authorize("/api/v1/resources/**", fullyAuthenticated) }
      authorizeRequests { authorize("/api/v1/resources", fullyAuthenticated) }
      authorizeRequests { authorize("/api/v1/site/**", fullyAuthenticated) }
      httpBasic {}
      sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }

      // This has nothing to do with security, but Spring Security supports adding custom headers.
      headers { addHeaderWriter(StaticHeadersWriter("Server", "Seedbank-Server/$VERSION")) }
    }
  }

  @Bean
  fun corsConfigurationSource(): CorsConfigurationSource {
    val configuration = CorsConfiguration().applyPermitDefaultValues()
    val source = UrlBasedCorsConfigurationSource()
    configuration.allowedMethods = listOf("*")
    source.registerCorsConfiguration("/**", configuration)
    return source
  }
}
