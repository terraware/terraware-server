package com.terraformation.seedbank.api

import com.terraformation.seedbank.api.seedbank.AccessionPayload
import com.terraformation.seedbank.api.seedbank.CreateAccessionRequestPayload
import com.terraformation.seedbank.api.seedbank.GerminationPayload
import com.terraformation.seedbank.api.seedbank.GerminationTestPayload
import com.terraformation.seedbank.api.seedbank.UpdateAccessionRequestPayload
import com.terraformation.seedbank.services.perClassLogger
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.responses.ApiResponses
import javax.annotation.ManagedBean
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import org.jooq.DSLContext
import org.springdoc.core.customizers.OpenApiCustomiser
import org.springframework.beans.factory.annotation.Autowired

/**
 * Customizes generation of Swagger API documentation.
 *
 * - The list of endpoints and the list of schemas is alphabetized by tag and then by endpoint path
 * so that the JSON/YAML documentation can be usefully diffed between code versions.
 */
@ManagedBean
class SwaggerConfig : OpenApiCustomiser {
  @Autowired(required = false) var dslContext: DSLContext? = null

  private val log = perClassLogger()

  override fun customise(openApi: OpenAPI) {
    sortEndpoints(openApi)
    sortResponseCodes(openApi)
    sortSchemas(openApi)
    removeInheritedPropertiesFromPayloads(openApi)
  }

  private fun sortEndpoints(openApi: OpenAPI) {
    val paths = Paths()
    openApi.paths.entries.sortedBy { it.key }.forEach { paths.addPathItem(it.key, it.value) }
    openApi.paths = paths
  }

  private fun sortSchemas(openApi: OpenAPI) {
    openApi.components.schemas = openApi.components.schemas.toSortedMap()
  }

  private fun sortResponseCodes(openApi: OpenAPI) {
    openApi.paths.values.forEach { pathItem ->
      pathItem.readOperations().forEach { operation ->
        val responses = ApiResponses()
        responses.putAll(operation.responses.toSortedMap())
        operation.responses = responses
      }
    }
  }

  /**
   * Updates a schema so that payload classes only include properties that are declared directly on
   * those classes. Some payload classes implement interfaces with default property getters, and we
   * don't want those read-only properties to show up as valid inputs in the API docs.
   */
  private fun removeInheritedPropertiesFromPayloads(openApi: OpenAPI) {
    val payloadClasses =
        listOf(
            AccessionPayload::class,
            CreateAccessionRequestPayload::class,
            GerminationPayload::class,
            GerminationTestPayload::class,
            UpdateAccessionRequestPayload::class,
        )

    payloadClasses.forEach { payloadClass ->
      val schemaName = payloadClass.swaggerSchemaName
      val schema = openApi.components.schemas[schemaName]

      if (schema != null) {
        val includedPropertyNames =
            payloadClass.declaredMemberProperties.map { it.swaggerSchemaName }.toSet()
        val allPropertyNames = payloadClass.memberProperties.map { it.swaggerSchemaName }.toSet()
        val propertiesToRemove = allPropertyNames.minus(includedPropertyNames)

        log.debug("Removing properties from $schemaName: $propertiesToRemove")
        propertiesToRemove.forEach { schema.properties.remove(it) }
      } else {
        throw RuntimeException(
            "Schema $schemaName not found for class ${payloadClass.qualifiedName}")
      }
    }
  }

  /**
   * The name of this property as it appears in the Swagger schema. By default this is just the
   * property name but an alternate name can be specified in the [Schema] annotation.
   */
  private val KProperty<*>.swaggerSchemaName
    get() = findAnnotation<Schema>()?.name ?: name

  /**
   * The name of this class as it appears in the Swagger schema. By default this is the unqualified
   * class name but an alternate name can be specified in the [Schema] annotation.
   */
  private val KClass<*>.swaggerSchemaName
    get() = findAnnotation<Schema>()?.name ?: simpleName
}
