package com.terraformation.backend.api

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.table.SearchTables
import io.swagger.v3.core.util.RefUtils
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.MapSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.security.SecurityScheme
import jakarta.inject.Named
import java.time.ZoneId
import kotlin.reflect.KClass
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.superclasses
import kotlin.reflect.jvm.javaField
import kotlin.reflect.typeOf
import org.jooq.DSLContext
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider

/**
 * Customizes generation of OpenAPI documentation.
 * - JTS geometry classes use a schema defined in [GeoJsonOpenApiSchema].
 * - Descriptions from annotations are added to model fields that are references to other model
 *   classes.
 */
@Named
class OpenApiConfig(private val keycloakInfo: KeycloakInfo) : OpenApiCustomizer {
  @Autowired(required = false) var dslContext: DSLContext? = null

  init {
    val config = SpringDocUtils.getConfig()

    GeoJsonOpenApiSchema.configureJtsSchemas(config)

    config.replaceWithSchema(ArbitraryJsonObject::class.java, ObjectSchema())
    config.replaceWithSchema(
        ZoneId::class.java,
        StringSchema()
            .description("Time zone name in IANA tz database format")
            .example("America/New_York"),
    )
  }

  override fun customise(openApi: OpenAPI) {
    useRefForGeometry(openApi)
    fixFieldSchemas(openApi)
    addSecurityScheme(openApi)
    addDiscriminatorsForSealedInterfaces(openApi)
    enumerateSearchPrefixes(openApi)
  }

  private fun addSecurityScheme(openApi: OpenAPI) {
    openApi.components.addSecuritySchemes(
        "openId",
        SecurityScheme().apply {
          type = SecurityScheme.Type.OPENIDCONNECT
          description = "OpenID Connect"
          openIdConnectUrl = keycloakInfo.openIdConnectConfigUrl
        },
    )
  }

  /**
   * Workaround for a [limitation](https://github.com/swagger-api/swagger-core/issues/3290) of the
   * Swagger core library. By default, there is no way to put a description on a payload field that
   * is a reference to another payload class. That is, the description here will be thrown away:
   * ```
   * data class ParentPayload(
   *   @Schema(description = "My description")
   *   val child: ChildPayload
   * )
   *
   * data class ChildPayload(val field: String)
   * ```
   *
   * This is a limitation in the logic that converts annotations to the Swagger library's internal
   * representation of an API schema. Work around it by programmatically constructing the correct
   * data structures for payload classes that have child payload objects where the parent fields
   * have descriptions.
   */
  private fun fixFieldSchemas(openApi: OpenAPI) {
    allPayloadClasses.forEach { payloadClass ->
      val schemaName = payloadClass.swaggerSchemaName
      val classSchema = openApi.components.schemas[schemaName]

      val constructorParameters =
          payloadClass.primaryConstructor?.parameters?.associateBy { it.name } ?: emptyMap()

      if (classSchema != null) {
        payloadClass.declaredMemberProperties.forEach { property ->
          val propertyAnnotation =
              constructorParameters[property.name]?.findAnnotation()
                  ?: property.findAnnotation()
                  ?: property.getter.findAnnotation()
                  ?: property.javaField?.getAnnotation(Schema::class.java)
          val propertyName = propertyAnnotation?.name.orEmpty().ifEmpty { property.name }
          val propertySchema = classSchema.properties?.get(propertyName)

          if (
              propertySchema != null && propertySchema.`$ref` != null && propertyAnnotation != null
          ) {
            val composedSchema = ComposedSchema()

            if (propertyAnnotation.oneOf.isEmpty()) {
              composedSchema.allOf = listOf(propertySchema)
            } else {
              // The property is annotated with an explicit "oneOf" list. Turn that into a list of
              // schema references.
              composedSchema.oneOf =
                  propertyAnnotation.oneOf.map { oneOfClass ->
                    val oneOfSchema = ComposedSchema()
                    oneOfSchema.`$ref` = "#/components/schemas/${oneOfClass.swaggerSchemaName}"
                    oneOfSchema
                  }
            }

            composedSchema.description = propertyAnnotation.description.ifEmpty { null }
            classSchema.properties[propertyName] = composedSchema
          }

          // ArbitraryJsonObject and Map<String, Any?> fields are also missing descriptions, but
          // we can modify their schemas in place.
          if (
              propertySchema is ObjectSchema &&
                  propertyAnnotation != null &&
                  propertySchema.description == null
          ) {
            propertySchema.description = propertyAnnotation.description
          }

          // ArbitraryJsonObject should always allow additional properties.
          if (
              propertySchema is ObjectSchema &&
                  property.returnType.isSubtypeOf(typeOf<ArbitraryJsonObject?>())
          ) {
            propertySchema.additionalProperties = true
          }

          // Map<*,*> fields default to additionalProperties values that say the values are all
          // objects, which is wrong; values could be other JSON types too.
          if (propertySchema is ArraySchema && propertySchema.items is MapSchema) {
            propertySchema.items.additionalProperties = true
          }
        }
      }
    }
  }

  /**
   * Adds discriminator mappings for polymorphic payload classes that are subclasses of a sealed
   * interface. The mappings are based on the Jackson annotations ([JsonTypeInfo], [JsonTypeName],
   * [JsonSubTypes]) that control actual serialization of payloads.
   */
  private fun addDiscriminatorsForSealedInterfaces(openApi: OpenAPI) {
    allPayloadClasses
        .flatMap { payloadClass -> payloadClass.superclasses.filter { it.isSealed } }
        .forEach { sealedInterface ->
          val jsonTypeInfo = sealedInterface.findAnnotation<JsonTypeInfo>()
          if (jsonTypeInfo?.use == JsonTypeInfo.Id.NAME) {
            val classSchema = openApi.components.schemas[sealedInterface.swaggerSchemaName]

            if (classSchema != null) {
              val discriminator =
                  classSchema.discriminator
                      ?: Discriminator().also { classSchema.discriminator = it }

              if (discriminator.propertyName == null) {
                discriminator.propertyName = jsonTypeInfo.property
              }

              if (discriminator.mapping == null || discriminator.mapping.isEmpty()) {
                sealedInterface.sealedSubclasses.forEach { subclass ->
                  discriminator.mapping(
                      getDiscriminatorValue(sealedInterface, subclass),
                      RefUtils.constructRef(subclass.swaggerSchemaName),
                  )
                }
              }
            }
          }
        }
  }

  /**
   * Returns the value that Jackson will use in the discriminator property for a given subclass of a
   * superclass. Discriminator values can be specified either on the subclass with [JsonTypeName] or
   * on the superclass with [JsonSubTypes].
   */
  private fun getDiscriminatorValue(superclass: KClass<*>, subclass: KClass<*>): String {
    val jsonTypeName = subclass.findAnnotation<JsonTypeName>()
    if (jsonTypeName != null) {
      return jsonTypeName.value
    }

    val jsonSubTypes = superclass.findAnnotation<JsonSubTypes>()
    if (jsonSubTypes != null) {
      return jsonSubTypes.value.first { it.value == subclass }.name
    }

    throw IllegalStateException(
        "Unable to determine discriminator value for subclass ${subclass.qualifiedName} of ${superclass.qualifiedName}"
    )
  }

  /**
   * All the API payload classes. A "payload class" is defined here as a data class in the
   * `com.terraformation` package whose name contains the word "Payload". Classes whose names
   * contain dollar signs are excluded; the Kotlin compiler generates helper classes that we don't
   * want to treat as payload classes.
   *
   * This uses Spring's classpath scanning utilities.
   */
  private val allPayloadClasses: List<KClass<out Any>> by lazy {
    val provider = ClassPathScanningCandidateComponentProvider(false)

    provider.addIncludeFilter { metadataReader, _ ->
      val className = metadataReader.classMetadata.className
      "Payload" in className && '$' !in className
    }

    provider
        .findCandidateComponents("com.terraformation")
        .map { beanDefinition -> beanDefinition.beanClassName }
        .map { Class.forName(it).kotlin }
        .filter { it.isData }
  }

  /**
   * Uses a reference to the abstract Geometry class rather than a list of its subclasses.
   *
   * For some reason, the default behavior of the OpenAPI schema generator is to render Geometry
   * properties like
   *
   * ```
   * geom:
   *   oneOf:
   *   - $ref: '#/components/schemas/GeometryCollection'
   *   - $ref: '#/components/schemas/LineString'
   *   - $ref: '#/components/schemas/MultiLineString'
   *   - $ref: '#/components/schemas/MultiPoint'
   *   - $ref: '#/components/schemas/MultiPolygon'
   *   - $ref: '#/components/schemas/Point'
   *   - $ref: '#/components/schemas/Polygon'
   * ```
   *
   * But in GeometryCollection, it refers to the superclass:
   * ```
   * properties:
   *   geometries:
   *     type: array
   *     items:
   *       $ref: '#/components/schemas/Geometry'
   * ```
   *
   * This method updates the schema definitions to always use the latter style.
   */
  private fun useRefForGeometry(openApi: OpenAPI) {
    openApi.components?.schemas?.values?.forEach { schema ->
      schema.properties?.values?.forEach { property ->
        if (
            property is ComposedSchema &&
                property.oneOf?.any { it.`$ref` == "#/components/schemas/Polygon" } == true
        ) {
          property.`$ref` = "#/components/schemas/Geometry"
          property.oneOf = null
        }
      }
    }
  }

  /** Adds a list of valid values to the "prefix" field of SearchRequestPayload. */
  private fun enumerateSearchPrefixes(openApi: OpenAPI) {
    val searchTableType = SearchTable::class.createType()
    val searchTableNames =
        SearchTables::class
            .declaredMemberProperties
            .filter { it.returnType.isSubtypeOf(searchTableType) }
            .map { it.name }

    val searchPayloadPrefix =
        openApi.components.schemas["SearchRequestPayload"]!!.properties["prefix"] as StringSchema
    searchTableNames.forEach { searchPayloadPrefix.addEnumItem(it) }
  }

  /**
   * The name of this class as it appears in the Swagger schema. By default, this is the unqualified
   * class name but an alternate name can be specified in the [Schema] annotation.
   */
  private val KClass<*>.swaggerSchemaName
    get() = findAnnotation<Schema>()?.name.orEmpty().ifEmpty { simpleName }
}
