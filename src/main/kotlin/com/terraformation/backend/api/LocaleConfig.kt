package com.terraformation.backend.api

import java.util.Locale
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver

@Configuration
class LocaleConfig {
  /**
   * Make Spring's request handlers look at the HTTP Accept-Language header to set the locale for
   * the current thread.
   */
  @Bean
  fun localeResolver(): AcceptHeaderLocaleResolver {
    val resolver = AcceptHeaderLocaleResolver()

    resolver.setDefaultLocale(Locale.ENGLISH)

    return resolver
  }
}
