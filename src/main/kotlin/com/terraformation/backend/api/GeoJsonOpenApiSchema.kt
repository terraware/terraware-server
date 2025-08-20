package com.terraformation.backend.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import org.springdoc.core.utils.SpringDocUtils

/**
 * Defines an OpenAPI schema for the GeoJSON renditions of the JTS geometry classes. These classes
 * are converted to and from JSON by our custom serializer but are implemented in such a way that
 * the OpenAPI schema generator can't turn them into usable parts of our schema document.
 *
 * This is invoked by [OpenApiConfig] which does some additional postprocessing on the schema.
 */
@Suppress("unused")
abstract class GeoJsonOpenApiSchema {
  internal enum class GeoJsonGeometryType {
    Point,
    LineString,
    Polygon,
    MultiPoint,
    MultiLineString,
    MultiPolygon,
    GeometryCollection,
  }

  @Schema(
      description = "GEOMETRY-FIX-TYPE-ON-CLIENT-SIDE",
      discriminatorProperty = "type",
      discriminatorMapping =
          [
              DiscriminatorMapping(
                  value = "GeometryCollection",
                  schema = GeometryCollection::class,
              ),
              DiscriminatorMapping(value = "LineString", schema = LineString::class),
              DiscriminatorMapping(value = "MultiLineString", schema = MultiLineString::class),
              DiscriminatorMapping(value = "MultiPoint", schema = MultiPoint::class),
              DiscriminatorMapping(value = "MultiPolygon", schema = MultiPolygon::class),
              DiscriminatorMapping(value = "Point", schema = Point::class),
              DiscriminatorMapping(value = "Polygon", schema = Polygon::class),
          ],
      subTypes =
          [
              GeometryCollection::class,
              LineString::class,
              MultiLineString::class,
              MultiPoint::class,
              MultiPolygon::class,
              Point::class,
              Polygon::class,
          ],
      type = "object",
  )
  internal interface Geometry {
    val type: GeoJsonGeometryType
    val crs: CRS?
      get() = null
  }

  @Schema(
      // This should happen automatically, but for some reason the docs generator sometimes fails to
      // add Geometry as a $ref and instead inlines Geometry's properties here.
      allOf = [Geometry::class],
      externalDocs =
          ExternalDocumentation(
              url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.2"
          ),
  )
  internal abstract class Point(
      val coordinates: Position,
  ) : Geometry {
    @get:Schema(allowableValues = ["Point"], type = "string")
    override val type: GeoJsonGeometryType
      get() = GeoJsonGeometryType.Point
  }

  @ArraySchema(
      minItems = 3,
      maxItems = 3,
      arraySchema =
          Schema(
              name = "Position",
              description =
                  "A single position consisting of X and Y values in the coordinate system " +
                      "specified by the crs field.",
              example = "[120,-9.53]",
          ),
  )
  @JsonIgnoreProperties("empty")
  internal interface Position : List<Double>

  @ArraySchema(
      minItems = 4,
      arraySchema =
          Schema(
              description =
                  "A LineString with four or more positions. The first and last positions are " +
                      "equivalent, and they MUST contain identical values.",
              externalDocs =
                  ExternalDocumentation(
                      url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.6"
                  ),
          ),
  )
  @JsonIgnoreProperties("empty")
  internal interface LinearRing : List<Position>

  @ArraySchema(minItems = 2)
  @JsonIgnoreProperties("empty")
  internal interface LineStringPositions : List<Position>

  @Schema(
      externalDocs =
          ExternalDocumentation(url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.4")
  )
  internal abstract class LineString(val coordinates: LineStringPositions) : Geometry {
    @get:Schema(allowableValues = ["LineString"], type = "string")
    override val type: GeoJsonGeometryType
      get() = GeoJsonGeometryType.LineString
  }

  @Schema(
      externalDocs =
          ExternalDocumentation(url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.6")
  )
  internal abstract class Polygon(val coordinates: List<LinearRing>) : Geometry {
    @get:Schema(allowableValues = ["Polygon"], type = "string")
    override val type: GeoJsonGeometryType
      get() = GeoJsonGeometryType.Polygon
  }

  @Schema(
      externalDocs =
          ExternalDocumentation(url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.3")
  )
  internal abstract class MultiPoint(val coordinates: List<Position>) : Geometry {
    @get:Schema(allowableValues = ["MultiPoint"], type = "string")
    override val type: GeoJsonGeometryType
      get() = GeoJsonGeometryType.MultiPoint
  }

  @Schema(
      externalDocs =
          ExternalDocumentation(url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.5")
  )
  internal abstract class MultiLineString(val coordinates: List<LineStringPositions>) : Geometry {
    @get:Schema(allowableValues = ["MultiLineString"], type = "string")
    override val type: GeoJsonGeometryType
      get() = GeoJsonGeometryType.MultiLineString
  }

  @Schema(
      externalDocs =
          ExternalDocumentation(url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.7")
  )
  internal abstract class MultiPolygon(val coordinates: List<List<LinearRing>>) : Geometry {
    @get:Schema(allowableValues = ["MultiPolygon"], type = "string")
    override val type: GeoJsonGeometryType
      get() = GeoJsonGeometryType.MultiPolygon
  }

  @Schema(
      externalDocs =
          ExternalDocumentation(url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.8")
  )
  internal abstract class GeometryCollection(val geometries: List<Geometry>) : Geometry {
    @get:Schema(allowableValues = ["GeometryCollection"], type = "string")
    override val type: GeoJsonGeometryType
      get() = GeoJsonGeometryType.GeometryCollection
  }

  @Schema(
      description =
          "Coordinate reference system used for X and Y coordinates in this geometry. By " +
              "default, coordinates are in WGS 84, with longitude and latitude in degrees. " +
              "In that case, this element is not present. Otherwise, it specifies which " +
              "coordinate system to use."
  )
  internal abstract class CRS(
      @Schema(
          allowableValues = ["name"],
      )
      val type: String,
      val properties: CRSProperties,
  )

  internal abstract class CRSProperties(
      @Schema(
          description =
              "Name of the coordinate reference system. This must be in the form EPSG:nnnn where " +
                  "nnnn is the numeric identifier of a coordinate system in the EPSG dataset. " +
                  "The default is Longitude/Latitude EPSG:4326, which is the coordinate system +" +
                  "for GeoJSON.",
          example = "EPSG:4326",
          externalDocs =
              ExternalDocumentation(
                  url = "https://en.wikipedia.org/wiki/EPSG_Geodetic_Parameter_Dataset"
              ),
      )
      val name: String
  )

  companion object {
    fun configureJtsSchemas(config: SpringDocUtils) {
      config.replaceWithClass(org.locationtech.jts.geom.Geometry::class.java, Geometry::class.java)
      config.replaceWithClass(
          org.locationtech.jts.geom.GeometryCollection::class.java,
          GeometryCollection::class.java,
      )
      config.replaceWithClass(
          org.locationtech.jts.geom.LineString::class.java,
          LineString::class.java,
      )
      config.replaceWithClass(
          org.locationtech.jts.geom.MultiLineString::class.java,
          MultiLineString::class.java,
      )
      config.replaceWithClass(
          org.locationtech.jts.geom.MultiPoint::class.java,
          MultiPoint::class.java,
      )
      config.replaceWithClass(
          org.locationtech.jts.geom.MultiPolygon::class.java,
          MultiPolygon::class.java,
      )
      config.replaceWithClass(org.locationtech.jts.geom.Point::class.java, Point::class.java)
      config.replaceWithClass(org.locationtech.jts.geom.Polygon::class.java, Polygon::class.java)
    }
  }
}
