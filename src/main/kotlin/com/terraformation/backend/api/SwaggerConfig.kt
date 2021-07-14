package com.terraformation.backend.api

import com.terraformation.backend.seedbank.api.AccessionPayload
import com.terraformation.backend.seedbank.api.GerminationTestPayload
import com.terraformation.backend.seedbank.api.UpdateAccessionRequestPayload
import com.terraformation.backend.seedbank.api.WithdrawalPayload
import com.terraformation.backend.seedbank.search.SearchField
import com.terraformation.backend.seedbank.search.SearchFields
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponses
import javax.annotation.ManagedBean
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField
import org.jooq.DSLContext
import org.springdoc.core.SpringDocUtils
import org.springdoc.core.customizers.OpenApiCustomiser
import org.springframework.beans.factory.annotation.Autowired

/**
 * Customizes generation of Swagger API documentation.
 *
 * - SearchField is represented as an enum based on the list of field names in [SearchFields].
 * - The list of endpoints and the list of schemas is alphabetized by tag and then by endpoint path
 * so that the JSON/YAML documentation can be usefully diffed between code versions.
 */
@ManagedBean
class SwaggerConfig(private val searchFields: SearchFields) : OpenApiCustomiser {
  @Autowired(required = false) var dslContext: DSLContext? = null

  init {
    val config = SpringDocUtils.getConfig()

    val schema = StringSchema()
    schema.`$ref` = "#/components/schemas/SearchField"

    config.replaceWithSchema(SearchField::class.java, schema)
  }

  override fun customise(openApi: OpenAPI) {
    renderSearchFieldAsEnum(openApi)
    sortEndpoints(openApi)
    sortResponseCodes(openApi)
    sortSchemas(openApi)
    addDescriptionsToRefs(openApi)
  }

  private fun renderSearchFieldAsEnum(openApi: OpenAPI) {
    val schema = StringSchema()
    schema.enum = searchFields.fieldNames.sorted()
    schema.name = "SearchField"

    openApi.components.addSchemas(schema.name, schema)
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

  private fun addDescriptionsToRefs(openApi: OpenAPI) {
    val payloadClasses =
        listOf(
            AccessionPayload::class,
            GerminationTestPayload::class,
            UpdateAccessionRequestPayload::class,
            WithdrawalPayload::class,
        )

    payloadClasses.forEach { payloadClass ->
      val schemaName = payloadClass.swaggerSchemaName
      val classSchema = openApi.components.schemas[schemaName]

      val constructorParameters =
          payloadClass.primaryConstructor?.parameters?.associateBy { it.name } ?: emptyMap()

      if (classSchema != null) {
        payloadClass.declaredMemberProperties.forEach { property ->
          val propertyAnnotation =
              constructorParameters[property.name]?.findAnnotation()
                  ?: property.findAnnotation() ?: property.getter.findAnnotation()
                      ?: property.javaField?.getAnnotation(Schema::class.java)
          val propertyName = propertyAnnotation?.name.orEmpty().ifEmpty { property.name }
          val propertySchema = classSchema.properties[propertyName]

          if (propertySchema != null &&
              propertySchema.`$ref` != null &&
              propertyAnnotation != null) {
            val composedSchema = ComposedSchema()
            composedSchema.allOf = listOf(propertySchema)
            composedSchema.description = propertyAnnotation.description
            classSchema.properties[propertyName] = composedSchema
          }
        }
      }
    }
  }

  /**
   * The name of this class as it appears in the Swagger schema. By default this is the unqualified
   * class name but an alternate name can be specified in the [Schema] annotation.
   */
  private val KClass<*>.swaggerSchemaName
    get() = findAnnotation<Schema>()?.name.orEmpty().ifEmpty { simpleName }
}
