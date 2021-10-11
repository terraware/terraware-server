package com.terraformation.backend.api

import com.terraformation.backend.device.api.CreateDeviceRequestPayload
import com.terraformation.backend.device.api.DeviceConfig
import com.terraformation.backend.device.api.UpdateDeviceRequestPayload
import com.terraformation.backend.seedbank.api.AccessionPayload
import com.terraformation.backend.seedbank.api.GerminationTestPayload
import com.terraformation.backend.seedbank.api.SearchResponsePayload
import com.terraformation.backend.seedbank.api.UpdateAccessionRequestPayload
import com.terraformation.backend.seedbank.api.WithdrawalPayload
import com.terraformation.backend.seedbank.search.SearchField
import com.terraformation.backend.seedbank.search.SearchFields
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.ObjectSchema
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
 * Customizes generation of OpenAPI documentation.
 *
 * - SearchField is represented as an enum based on the list of field names in [SearchFields].
 * - The list of endpoints and the list of schemas is alphabetized by tag and then by endpoint path
 * so that the JSON/YAML documentation can be usefully diffed between code versions.
 * - PostGIS geometry classes use a schema defined in [GeoJsonOpenApiSchema].
 */
@ManagedBean
class OpenApiConfig(private val searchFields: SearchFields) : OpenApiCustomiser {
  @Autowired(required = false) var dslContext: DSLContext? = null

  init {
    val config = SpringDocUtils.getConfig()

    val schema = StringSchema()
    schema.`$ref` = "#/components/schemas/SearchField"

    config.replaceWithSchema(SearchField::class.java, schema)
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
    renderSearchFieldAsEnum(openApi)
    renderSearchResultProperties(openApi)
    sortEndpoints(openApi)
    sortResponseCodes(openApi)
    sortSchemas(openApi)
    addDescriptionsToRefs(openApi)
    useRefForGeometry(openApi)
    removeAdditionalProperties(openApi)
  }

  /**
   * Removes the additionalProperties value from `Map<String, Any>` properties. By default, the
   * generated schema will say that the values of the map are JSON objects, which is wrong; they
   * could also be strings or numbers.
   */
  private fun removeAdditionalProperties(openApi: OpenAPI) {
    val fieldsToModify =
        listOf(
            CreateDeviceRequestPayload::class.swaggerSchemaName to "settings",
            DeviceConfig::class.swaggerSchemaName to "settings",
            UpdateDeviceRequestPayload::class.swaggerSchemaName to "settings",
        )

    fieldsToModify.forEach { (schemaName, fieldName) ->
      val field =
          openApi.components.schemas[schemaName]?.properties?.get(fieldName)
              ?: throw IllegalStateException("Cannot find field $schemaName.$fieldName")
      field.additionalProperties = null
    }
  }

  private fun renderSearchFieldAsEnum(openApi: OpenAPI) {
    val schema = StringSchema()
    schema.enum = searchFields.fieldNames.sorted()
    schema.name = "SearchField"

    openApi.components.addSchemas(schema.name, schema)
  }

  /**
   * Renders the type of the array of search results as an object with a fixed set of possible
   * property names, one for each search field name.
   */
  private fun renderSearchResultProperties(openApi: OpenAPI) {
    val schemaName = SearchResponsePayload::class.swaggerSchemaName
    val resultsField =
        (openApi.components.schemas[schemaName]?.properties?.get("results")
            ?: throw IllegalStateException("Cannot find search results schema")) as ArraySchema

    resultsField.items =
        ObjectSchema().properties(searchFields.fieldNames.associateWith { StringSchema() })
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
