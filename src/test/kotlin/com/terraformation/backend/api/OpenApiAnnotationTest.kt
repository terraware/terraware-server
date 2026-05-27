package com.terraformation.backend.api

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.stream.Stream
import kotlin.streams.asStream
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.MergedAnnotations
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.InputStreamResource
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
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

  @MethodSource("findAllEndpointMethods")
  @ParameterizedTest(name = "{0}")
  fun `all endpoint response payloads implement ResponsePayload`(name: String, method: Method) {
    val payloadClass = responsePayloadClass(method.genericReturnType) ?: return

    assertTrue(
        ResponsePayload::class.java.isAssignableFrom(payloadClass),
        "Response payload ${payloadClass.name} does not implement ResponsePayload",
    )
  }

  @Test
  fun `no two payload classes have the same simple name`() {
    val payloadClasses = buildList {
      val visited = mutableSetOf<String>()
      controllerClasses()
          .flatMap { clazz ->
            clazz.declaredMethods.filter { method: Method ->
              method.annotations.any { it.annotationClass in controllerMethodAnnotations }
            }
          }
          .forEach { method ->
            collectPayloadTypes(method.genericReturnType, this, visited)
            method.parameters
                .filter { param -> param.isAnnotationPresent(RequestBody::class.java) }
                .forEach { param -> collectPayloadTypes(param.parameterizedType, this, visited) }
          }
    }

    val duplicates =
        payloadClasses
            .groupBy { it.simpleName }
            .filter { (name, classes) ->
              name !in DUPLICATE_CLASS_NAME_ALLOWLIST && classes.map { it.name }.distinct().size > 1
            }

    assertTrue(
        duplicates.isEmpty(),
        "Found payload classes with the same simple name:\n" +
            duplicates.entries.joinToString("\n") { (name, classes) ->
              "  $name: ${classes.map { it.name }.distinct().joinToString(", ")}"
            },
    )
  }

  @MethodSource("findAllControllerPackages")
  @ParameterizedTest(name = "{0}")
  fun `all controller packages are in springdoc packages-to-scan`(packageName: String) {
    val classNames =
        controllerClasses()
            .filter { it.packageName == packageName }
            .map { it.simpleName }
            .sorted()
            .joinToString(", ")

    assertTrue(
        packageName in springdocPackagesToScan,
        "Package '$packageName' has @RestController classes [$classNames] but is missing from " +
            "springdoc.packages-to-scan in application.yaml. " +
            "Add it, or add it to SPRINGDOC_PACKAGE_SCAN_BLACKLIST in this test if it should be excluded.",
    )
  }

  companion object {
    /**
     * Packages containing @RestController classes that are intentionally excluded from springdoc
     * scanning (e.g., internal-only controllers that should not appear in the public API docs).
     */
    private val SPRINGDOC_PACKAGE_SCAN_BLACKLIST: Set<String> = setOf()

    /**
     * Classes that are intentionally allowed to share a simple name across packages and should not
     * be flagged as conflicts.
     */
    private val DUPLICATE_CLASS_NAME_ALLOWLIST: Set<String> =
        setOf(
            "WithdrawalId",
            "WithdrawalPurpose",
        )

    private val springdocPackagesToScan: Set<String> by lazy {
      val propertySources =
          YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml"))
      val environment = StandardEnvironment()
      propertySources.forEach { environment.propertySources.addFirst(it) }
      Binder.get(environment)
          .bind("springdoc.packages-to-scan", Bindable.listOf(String::class.java))
          .orElse(emptyList())
          .toSet()
    }

    private val controllerMethodAnnotations =
        setOf(
            DeleteMapping::class,
            GetMapping::class,
            PatchMapping::class,
            PostMapping::class,
            PutMapping::class,
            RequestMapping::class,
        )

    /**
     * Return types that endpoints use for raw (non-JSON) responses such as binary downloads or
     * plain-text webhook acknowledgments. Endpoints with these return types are exempt from the
     * requirement to implement [ResponsePayload].
     */
    private val rawResponseTypes: Set<Class<*>> =
        setOf(
            ByteArray::class.java,
            ByteArrayResource::class.java,
            InputStreamResource::class.java,
            String::class.java,
        )

    /**
     * Returns the class of the response payload for an endpoint method, or null if the endpoint
     * returns raw data that doesn't need to implement a response payload interface.
     */
    private fun responsePayloadClass(returnType: Type): Class<*>? {
      val rawClass =
          when (returnType) {
            is Class<*> -> returnType
            is ParameterizedType -> {
              val rawType = returnType.rawType as? Class<*> ?: return null
              if (ResponseEntity::class.java.isAssignableFrom(rawType)) {
                val typeArg = returnType.actualTypeArguments.firstOrNull() ?: return null
                if (typeArg is WildcardType) {
                  return null
                }
                return responsePayloadClass(typeArg)
              }
              rawType
            }
            else -> return null
          }

      if (rawClass.isPrimitive || rawClass in rawResponseTypes) {
        return null
      }

      return rawClass
    }

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
    fun findAllControllerPackages(): Stream<String> {
      return controllerClasses()
          .map { it.packageName }
          .filter { it !in SPRINGDOC_PACKAGE_SCAN_BLACKLIST }
          .distinct()
          .asStream()
    }

    private fun collectPayloadTypes(
        type: Type,
        result: MutableList<Class<*>>,
        visited: MutableSet<String>,
    ) {
      when (type) {
        is Class<*> -> {
          if (
              type.name.startsWith("com.terraformation.backend") &&
                  type.simpleName != "Companion" &&
                  visited.add(type.name)
          ) {
            result.add(type)
            type.declaredFields.forEach { field ->
              collectPayloadTypes(field.genericType, result, visited)
            }
          }
        }
        is ParameterizedType -> {
          collectPayloadTypes(type.rawType, result, visited)
          type.actualTypeArguments.forEach { collectPayloadTypes(it, result, visited) }
        }
      }
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
