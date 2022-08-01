package com.terraformation.backend.api

import com.terraformation.backend.auth.KeycloakInfo
import com.terraformation.backend.customer.api.AutomationPayload
import com.terraformation.backend.customer.api.GetUserPreferencesResponsePayload
import com.terraformation.backend.customer.api.ModifyAutomationRequestPayload
import com.terraformation.backend.customer.api.UpdateUserPreferencesRequestPayload
import com.terraformation.backend.device.api.CreateDeviceRequestPayload
import com.terraformation.backend.device.api.DeviceConfig
import com.terraformation.backend.device.api.UpdateDeviceRequestPayload
import com.terraformation.backend.search.api.SearchResponsePayload
import com.terraformation.backend.seedbank.api.AccessionPayload
import com.terraformation.backend.seedbank.api.UpdateAccessionRequestPayload
import com.terraformation.backend.seedbank.api.ViabilityTestPayload
import com.terraformation.backend.seedbank.api.WithdrawalPayload
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityScheme
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
 * Customizes generation of OpenAPI documentation.
 *
 * - The list of endpoints and the list of schemas is alphabetized by tag and then by endpoint path
 * so that the JSON/YAML documentation can be usefully diffed between code versions.
 * - PostGIS geometry classes use a schema defined in [GeoJsonOpenApiSchema].
 */
@ManagedBean
class OpenApiConfig(private val keycloakInfo: KeycloakInfo) : OpenApiCustomiser {
  @Autowired(required = false) var dslContext: DSLContext? = null

  init {
    val config = SpringDocUtils.getConfig()

    config.replaceWithClass(
        net.postgis.jdbc.geometry.Geometry::class.java, GeoJsonOpenApiSchema.Geometry::class.java)
    config.replaceWithClass(
        net.postgis.jdbc.geometry.GeometryCollection::class.java,
        GeoJsonOpenApiSchema.GeometryCollection::class.java)
    config.replaceWithClass(
        net.postgis.jdbc.geometry.LineString::class.java,
        GeoJsonOpenApiSchema.LineString::class.java)
    config.replaceWithClass(
        net.postgis.jdbc.geometry.MultiLineString::class.java,
        GeoJsonOpenApiSchema.MultiLineString::class.java)
    config.replaceWithClass(
        net.postgis.jdbc.geometry.MultiPoint::class.java,
        GeoJsonOpenApiSchema.MultiPoint::class.java)
    config.replaceWithClass(
        net.postgis.jdbc.geometry.MultiPolygon::class.java,
        GeoJsonOpenApiSchema.MultiPolygon::class.java)
    config.replaceWithClass(
        net.postgis.jdbc.geometry.Point::class.java, GeoJsonOpenApiSchema.Point::class.java)
    config.replaceWithClass(
        net.postgis.jdbc.geometry.Polygon::class.java, GeoJsonOpenApiSchema.Polygon::class.java)
  }

  override fun customise(openApi: OpenAPI) {
    sortEndpoints(openApi)
    sortResponseCodes(openApi)
    sortSchemas(openApi)
    addDescriptionsToRefs(openApi)
    useRefForGeometry(openApi)
    removeAdditionalProperties(openApi)
    addSecurityScheme(openApi)
  }

  private fun addSecurityScheme(openApi: OpenAPI) {
    openApi.components.addSecuritySchemes(
        "openId",
        SecurityScheme().apply {
          type = SecurityScheme.Type.OPENIDCONNECT
          description = "OpenID Connect"
          openIdConnectUrl = "${keycloakInfo.openIdConnectConfigUrl}"
        })
  }

  /**
   * Removes the additionalProperties value from `Map<String, Any>` properties. By default, the
   * generated schema will say that the values of the map are JSON objects, which is wrong; they
   * could also be strings or numbers.
   */
  private fun removeAdditionalProperties(openApi: OpenAPI) {
    val fieldsToModify =
        listOf(
            AutomationPayload::class.swaggerSchemaName to "configuration",
            CreateDeviceRequestPayload::class.swaggerSchemaName to "settings",
            DeviceConfig::class.swaggerSchemaName to "settings",
            GetUserPreferencesResponsePayload::class.swaggerSchemaName to "preferences",
            ModifyAutomationRequestPayload::class.swaggerSchemaName to "configuration",
            UpdateDeviceRequestPayload::class.swaggerSchemaName to "settings",
            UpdateUserPreferencesRequestPayload::class.swaggerSchemaName to "preferences",
        )
    val listsToModify =
        listOf(
            SearchResponsePayload::class.swaggerSchemaName to "results",
        )

    fieldsToModify.forEach { (schemaName, fieldName) ->
      val field =
          openApi.components.schemas[schemaName]?.properties?.get(fieldName)
              ?: throw IllegalStateException("Cannot find field $schemaName.$fieldName")
      field.additionalProperties = null
    }

    listsToModify.forEach { (schemaName, listName) ->
      val field =
          openApi.components.schemas[schemaName]?.properties?.get(listName) as? ArraySchema
              ?: throw IllegalStateException("Could not find array field $schemaName.$listName")
      field.items.additionalProperties = null
    }
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
            ViabilityTestPayload::class,
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
   *
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
        if (property is ComposedSchema &&
            property.oneOf?.any { it.`$ref` == "#/components/schemas/Polygon" } == true) {
          property.`$ref` = "#/components/schemas/Geometry"
          property.oneOf = null
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
