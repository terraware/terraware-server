package com.terraformation.backend.api

import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.lang.reflect.Method
import java.util.stream.Stream
import kotlin.streams.asStream
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.MergedAnnotations
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Verifies that all our controller methods have the expected set of annotations to generate a good
 * OpenAPI schema document.
 */
class OpenApiAnnotationTest {
  @MethodSource("findAllEndpointMethods")
  @ParameterizedTest(name = "{0}")
  fun `all endpoints declare their success responses`(
      @Suppress("UNUSED_PARAMETER") name: String,
      method: Method
  ) {
    val responseCodes =
        MergedAnnotations.from(method)
            .stream(ApiResponse::class.java)
            .map { it.getString("responseCode").toInt() }
            .toList()

    assertTrue(
        responseCodes.isEmpty() || responseCodes.any { it in 200..299 },
        "No response declared for HTTP 2xx response code")
  }

  companion object {
    @JvmStatic
    private val controllerMethodAnnotations =
        setOf(
            DeleteMapping::class,
            GetMapping::class,
            PatchMapping::class,
            PostMapping::class,
            PutMapping::class,
            RequestMapping::class,
        )

    @JvmStatic
    fun findAllEndpointMethods(): Stream<Arguments> {
      val scanner = ClassPathScanningCandidateComponentProvider(false)
      scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
      val controllerClasses = scanner.findCandidateComponents("com.terraformation.backend")

      return controllerClasses
          .asSequence()
          .mapNotNull { it.beanClassName }
          .mapNotNull { Class.forName(it) }
          .flatMap { clazz ->
            clazz.declaredMethods.filter { method: Method ->
              method.annotations.any { it.annotationClass in controllerMethodAnnotations }
            }
          }
          .map { method -> Arguments.of("${method.declaringClass.name}.${method.name}", method) }
          .asStream()
    }
  }
}
