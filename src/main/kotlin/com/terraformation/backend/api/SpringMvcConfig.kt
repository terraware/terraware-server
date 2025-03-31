package com.terraformation.backend.api

import org.springframework.context.annotation.Configuration
import org.springframework.format.support.DefaultFormattingConversionService
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Configures the request handling framework. Most configuration is in `application.yaml` but some
 * options require programmatic configuration.
 */
@Configuration
class SpringMvcConfig(
    private val globalRoleInterceptor: GlobalRoleInterceptor,
    private val parameterizedEnumConverter: ParameterizedEnumConverter,
) : WebMvcConfigurer {
  /**
   * Matches URLs to controller paths using a newer matcher. The default matcher doesn't have good
   * support for controllers with parameterized paths that can contain multiple path elements, e.g.,
   * `/api/v1/resources/X/Y/Z` where we want `/X/Y/Z` to be treated as a single parameter.
   *
   * If a future Spring MVC version turns this into a configuration property, or makes the new
   * parser the default, this can go away.
   */
  override fun configurePathMatch(configurer: PathMatchConfigurer) {
    configurer.setPatternParser(PathPatternParser())
  }

  override fun addInterceptors(registry: InterceptorRegistry) {
    registry.addInterceptor(globalRoleInterceptor)
  }

  // Register the enum converter
  override fun addFormatters(registry: org.springframework.format.FormatterRegistry) {
    val conversionService = DefaultFormattingConversionService()
    conversionService.addConverter(parameterizedEnumConverter)
    registry.addConverter(parameterizedEnumConverter)
  }
}
