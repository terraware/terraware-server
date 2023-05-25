package com.terraformation.backend.api

import javax.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.getProperty
import org.springframework.util.unit.DataSize
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartResolver
import org.springframework.web.multipart.commons.CommonsMultipartResolver
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Configures the request handling framework. Most configuration is in `application.yaml` but some
 * options require programmatic configuration.
 */
@Configuration
class SpringMvcConfig(private val superAdminInterceptor: SuperAdminInterceptor) : WebMvcConfigurer {
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
    registry.addInterceptor(superAdminInterceptor)
  }

  /**
   * Configure the application's handling of multipart form data (file uploads) to allow streaming
   * of the file data.
   *
   * By default, the entire request is loaded into memory before the controller method is called.
   * With the "resolve lazily" flag, Spring will only load the request into memory if the controller
   * method has a parameter such as a [MultipartFile] that requires everything to already be in
   * memory.
   *
   * We can thus have controller methods that take [HttpServletRequest] parameters and access the
   * underlying input stream, allowing us to support uploading huge files without requiring equally
   * huge amounts of memory.
   *
   * Honors the standard Spring settings for maximum file size for file upload endpoints that need
   * the file to be loaded into memory. Endpoints that stream the file data can check the file size
   * explicitly if needed.
   */
  @Bean
  fun customMultipartResolver(env: Environment): MultipartResolver {
    val maxFileSize = env.getProperty<DataSize?>("spring.servlet.multipart.max-file-size")
    val maxUploadSize = env.getProperty<DataSize?>("spring.servlet.multipart.max-request-size")

    return CommonsMultipartResolver().apply {
      setResolveLazily(true)
      setMaxUploadSize(maxUploadSize?.toBytes() ?: -1)
      setMaxUploadSizePerFile(maxFileSize?.toBytes() ?: -1)
    }
  }
}
