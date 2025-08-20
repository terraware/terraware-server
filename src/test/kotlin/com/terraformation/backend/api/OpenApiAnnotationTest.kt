package com.terraformation.backend.api

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import java.lang.reflect.Method
import java.util.stream.Stream
import kotlin.streams.asStream
import org.junit.jupiter.api.Assertions.assertNotNull
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
  @MethodSource("findAllControllerClasses")
  @ParameterizedTest(name = "{0}")
  fun `all public controller classes have OpenAPI tags`(clazz: Class<*>) {
    val annotations = MergedAnnotations.from(clazz)

    if (!annotations.isPresent(Hidden::class.java)) {
      assertTrue(annotations.isPresent(Tag::class.java), "Missing Tag annotation")
    }
  }

  @MethodSource("findAllEndpointMethods")
  @ParameterizedTest(name = "{0}")
  fun `all endpoints declare their success responses`(name: String, method: Method) {
    val responseCodes =
        MergedAnnotations.from(method)
            .stream(ApiResponse::class.java)
            .map { it.getString("responseCode").toInt() }
            .toList()

    assertTrue(
        responseCodes.isEmpty() || responseCodes.any { it in 200..399 },
        "No response declared for HTTP 2xx response code on $name",
    )
  }

  @MethodSource("findAllEndpointMethods")
  @ParameterizedTest(name = "{0}")
  fun `all endpoints have summaries`(name: String, method: Method) {
    val operation = MergedAnnotations.from(method).get(Operation::class.java)
    assertTrue(operation.isPresent, "No Operation annotation on $name")
    assertNotNull(operation.getString("summary"), "Operation annotation on $name missing summary")
  }

  companion object {
    private val controllerMethodAnnotations =
        setOf(
            DeleteMapping::class,
            GetMapping::class,
            PatchMapping::class,
            PostMapping::class,
            PutMapping::class,
            RequestMapping::class,
        )

    private fun controllerClasses(): Sequence<Class<*>> {
      val scanner = ClassPathScanningCandidateComponentProvider(false)
      scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
      val controllerClasses = scanner.findCandidateComponents("com.terraformation.backend")

      return controllerClasses
          .asSequence()
          .mapNotNull { it.beanClassName }
          .mapNotNull { Class.forName(it) }
    }

    @JvmStatic
    fun findAllControllerClasses(): Stream<Class<*>> {
      return controllerClasses().asStream()
    }

    @JvmStatic
    fun findAllEndpointMethods(): Stream<Arguments> {
      return controllerClasses()
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
