package com.terraformation.backend.api

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GeoJsonOpenApiSchemaTest {
  @Test
  fun `replaces generic GeometryCollection items with geometry references`() {
    val openApi = openApiWithGeometryCollectionItems(ObjectSchema())

    GeoJsonOpenApiSchema.fixGeometryCollectionSchema(openApi)

    assertEquals(
        expectedGeometryRefs,
        geometryCollectionItems(openApi).oneOf.map { it.`$ref` },
    )
  }

  @Test
  fun `preserves GeometryCollection items already containing geometry references`() {
    val existingItems =
        ComposedSchema().apply {
          oneOf = expectedGeometryRefs.map { ref ->
            ComposedSchema().apply { `$ref` = ref }
          }
        }
    val openApi = openApiWithGeometryCollectionItems(existingItems)

    GeoJsonOpenApiSchema.fixGeometryCollectionSchema(openApi)

    assertSame(existingItems, geometryCollectionItems(openApi))
  }

  private fun openApiWithGeometryCollectionItems(items: Schema<*>): OpenAPI {
    val geometryCollection =
        ComposedSchema().apply {
          allOf =
              listOf(
                  ComposedSchema().apply { `$ref` = "#/components/schemas/Geometry" },
                  ObjectSchema().apply {
                    addProperty("geometries", ArraySchema().items(items))
                    addProperty("type", StringSchema())
                  },
              )
        }

    return OpenAPI().components(Components().addSchemas("GeometryCollection", geometryCollection))
  }

  private fun geometryCollectionItems(openApi: OpenAPI): Schema<*> {
    val geometryCollection = openApi.components.schemas["GeometryCollection"] as ComposedSchema
    val geometryProperties = geometryCollection.allOf[1].properties
    return (geometryProperties["geometries"] as ArraySchema).items
  }

  private companion object {
    val expectedGeometryRefs =
        listOf(
            "#/components/schemas/GeometryCollection",
            "#/components/schemas/LineString",
            "#/components/schemas/MultiLineString",
            "#/components/schemas/MultiPoint",
            "#/components/schemas/MultiPolygon",
            "#/components/schemas/Point",
            "#/components/schemas/Polygon",
        )
  }
}
