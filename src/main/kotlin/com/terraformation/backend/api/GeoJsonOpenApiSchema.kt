package com.terraformation.backend.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Defines an OpenAPI schema for the GeoJSON renditions of the PostGIS geometry classes. These
 * classes are converted to and from JSON by our custom serializer but are implemented in such a way
 * that the OpenAPI schema generator can't turn them into usable parts of our schema document.
 *
 * This is invoked by [OpenApiConfig] which does some additional postprocessing on the schema.
 */
abstract class GeoJsonOpenApiSchema {
  @Suppress("unused")
  internal enum class GeoJsonGeometryType {
    Point,
    LineString,
    Polygon,
    MultiPoint,
    MultiLineString,
    MultiPolygon,
    GeometryCollection
  }

  @Schema(
      description = "GEOMETRY-FIX-TYPE-ON-CLIENT-SIDE",
      discriminatorProperty = "type",
      discriminatorMapping =
          [
              DiscriminatorMapping(
                  value = "GeometryCollection", schema = GeometryCollection::class),
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
      type = "object")
  internal interface Geometry {
    val type: GeoJsonGeometryType
      get() = GeoJsonGeometryType.Point
    val crs: CRS?
      get() = null
  }

  @Schema(
      externalDocs =
          ExternalDocumentation(
              url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.2"))
  internal abstract class Point(
      val coordinates: Position,
  ) : Geometry

  @ArraySchema(
      minItems = 3,
      maxItems = 3,
      arraySchema =
          Schema(
              name = "Position",
              description =
                  "A single position. In the terraware-server API, positions must always include " +
                      "3 dimensions. The X and Y dimensions use the coordinate system specified " +
                      "by the crs field, and the Z dimension is in meters.",
              example = "[120,-9.53,16]"))
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
                      url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.6")))
  @JsonIgnoreProperties("empty")
  internal interface LinearRing : List<Position>

  @ArraySchema(minItems = 2)
  @JsonIgnoreProperties("empty")
  internal interface LineStringPositions : List<Position>

  @Schema(
      externalDocs =
          ExternalDocumentation(
              url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.4"))
  internal abstract class LineString(val coordinates: LineStringPositions) : Geometry

  @Schema(
      externalDocs =
          ExternalDocumentation(
              url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.6"))
  internal abstract class Polygon(val coordinates: List<LinearRing>) : Geometry

  @Schema(
      externalDocs =
          ExternalDocumentation(
              url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.3"))
  internal abstract class MultiPoint(val coordinates: List<Position>) : Geometry

  @Schema(
      externalDocs =
          ExternalDocumentation(
              url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.5"))
  internal abstract class MultiLineString(val coordinates: List<LineStringPositions>) : Geometry

  @Schema(
      externalDocs =
          ExternalDocumentation(
              url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.7"))
  internal abstract class MultiPolygon(val coordinates: List<List<LinearRing>>) : Geometry

  @Schema(
      externalDocs =
          ExternalDocumentation(
              url = "https://datatracker.ietf.org/doc/html/rfc7946#section-3.1.8"))
  internal abstract class GeometryCollection(val geometries: List<Geometry>) : Geometry

  @Schema(
      description =
          "Coordinate reference system used for X and Y coordinates in this geometry. By " +
              "default, coordinates are in WGS 84, with longitude and latitude in degrees. " +
              "In that case, this element is not present. Otherwise, it specifies which " +
              "coordinate system to use.")
  internal abstract class CRS(
      @Schema(allowableValues = ["name"]) val type: String,
      val properties: CRSProperties
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
                  url = "https://en.wikipedia.org/wiki/EPSG_Geodetic_Parameter_Dataset"))
      val name: String
  )
}
