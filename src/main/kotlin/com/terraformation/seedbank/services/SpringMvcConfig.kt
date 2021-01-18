package com.terraformation.seedbank.services

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

@Configuration
class SpringMvcConfig : WebMvcConfigurer {
  /**
   * Configures the web server to use a newer URL parser than the default one. Specifically, we want
   * this because it provides a more convenient API for URL path suffixes that have multiple path
   * elements. Once Spring MVC switches to using `PathPatternParser` by default, this can go away.
   */
  override fun configurePathMatch(configurer: PathMatchConfigurer) {
    configurer.patternParser = HackyPatternParser()
  }

  /**
   * Workaround for https://github.com/springdoc/springdoc-openapi/issues/965. The bug is fixed but
   * they haven't done a release that includes it yet. When there's a new release, we can get rid of
   * this and use `PathPatternParser` directly.
   */
  class HackyPatternParser : PathPatternParser() {
    override fun parse(pathPattern: String): PathPattern {
      return if (pathPattern == "/**/swagger-ui/**") {
        super.parse("/swagger-ui/**")
      } else {
        super.parse(pathPattern)
      }
    }
  }
}
