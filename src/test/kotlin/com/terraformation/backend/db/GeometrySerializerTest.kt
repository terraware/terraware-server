package com.terraformation.backend.db

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.stream.Stream
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.GeometryBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource

internal class GeometrySerializerTest {
  private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(GeometryModule())

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("successCases")
  fun `parses all geometry types correctly`(name: String, json: String, expected: String) {
    val geometry = objectMapper.readValue<Geometry>(json)
    val wkt = geometry.toString()
    assertEquals(expected, wkt)
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("successCases")
  fun `renders all geometry types correctly`(name: String, expected: String, wkt: String) {
    val geometry = GeometryBuilder.geomFromString(wkt)
    assertEquals(expected, objectMapper.writeValueAsString(geometry), wkt)
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("malformedCases")
  fun `detects malformed GeoJSON input`(name: String, json: String) {
    assertThrows<JsonParseException> { objectMapper.readValue<Geometry>(json) }
  }

  companion object {
    @JvmStatic
    fun successCases(): Stream<Arguments> {
      val mercatorCrs = """"crs":{"type":"name","properties":{"name":"EPSG:3857"}}"""

      return Stream.of(
          arguments(
              "Point with long/lat SRID",
              """{"type":"Point","coordinates":[1.1,2.2,3.3]}""",
              "SRID=4326;POINT(1.1 2.2 3.3)"),
          arguments(
              "Point with Mercator SRID",
              """{"type":"Point",$mercatorCrs,"coordinates":[1.1,2.2,3.3]}""",
              "SRID=3857;POINT(1.1 2.2 3.3)"),
          arguments(
              "MultiPoint with long/lat SRID",
              """{"type":"MultiPoint","coordinates":[[1,1,1],[2,2,2]]}""",
              "SRID=4326;MULTIPOINT(1 1 1,2 2 2)"),
          arguments(
              "MultiPoint with Mercator SRID",
              """{"type":"MultiPoint",$mercatorCrs,"coordinates":[[1,1,1],[2,2,2]]}""",
              "SRID=3857;MULTIPOINT(1 1 1,2 2 2)"),
          arguments(
              "Polygon with long/lat SRID",
              """{"type":"Polygon","coordinates":[[[1,2,3],[2.1,1.1,0.1],[5,5,5],[1,2,3]]]}""",
              "SRID=4326;POLYGON((1 2 3,2.1 1.1 0.1,5 5 5,1 2 3))"),
          arguments(
              "MultiPolygon with long/lat SRID",
              """{"type":"MultiPolygon","coordinates":[[[[1,2,3],[1.1,2.2,3.3],[0,0,0],[1,2,3]],[[0,-1,1],[1,0,1],[0,1,0],[0,-1,1]]]]}""",
              "SRID=4326;MULTIPOLYGON(((1 2 3,1.1 2.2 3.3,0 0 0,1 2 3),(0 -1 1,1 0 1,0 1 0,0 -1 1)))"),
          arguments(
              "MultiPolygon with Mercator SRID",
              """{"type":"MultiPolygon",$mercatorCrs,"coordinates":[[[[1,2,3],[1.1,2.2,3.3],[0,0,0],[1,2,3]],[[0,-1,1],[1,0,1],[0,1,0],[0,-1,1]]]]}""",
              "SRID=3857;MULTIPOLYGON(((1 2 3,1.1 2.2 3.3,0 0 0,1 2 3),(0 -1 1,1 0 1,0 1 0,0 -1 1)))"),
          arguments(
              "GeometryCollection with long/lat SRID",
              """{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2,3]},{"type":"Point","coordinates":[4,5,6]}]}""",
              "SRID=4326;GEOMETRYCOLLECTION(POINT(1 2 3),POINT(4 5 6))"),
          arguments(
              "GeometryCollection with Mercator SRID",
              """{"type":"GeometryCollection",$mercatorCrs,"geometries":[{"type":"Point","coordinates":[1,2,3]},{"type":"Point","coordinates":[4,5,6]}]}""",
              "SRID=3857;GEOMETRYCOLLECTION(POINT(1 2 3),POINT(4 5 6))"),
          arguments(
              "LineString with long/lat SRID",
              """{"type":"LineString","coordinates":[[1,1,1],[2,2,2],[3,3,3]]}""",
              "SRID=4326;LINESTRING(1 1 1,2 2 2,3 3 3)"),
          arguments(
              "LineString with Mercator SRID",
              """{"type":"LineString",$mercatorCrs,"coordinates":[[1,1,1],[2,2,2],[3,3,3]]}""",
              "SRID=3857;LINESTRING(1 1 1,2 2 2,3 3 3)"),
          arguments(
              "MultiLineString with long/lat SRID",
              """{"type":"MultiLineString","coordinates":[[[1,1,1],[2,2,2]],[[3,3,3],[4,4,4]]]}""",
              "SRID=4326;MULTILINESTRING((1 1 1,2 2 2),(3 3 3,4 4 4))"),
          arguments(
              "MultiLineString with Mercator SRID",
              """{"type":"MultiLineString",$mercatorCrs,"coordinates":[[[1,1,1],[2,2,2]],[[3,3,3],[4,4,4]]]}""",
              "SRID=3857;MULTILINESTRING((1 1 1,2 2 2),(3 3 3,4 4 4))"),
      )
    }

    @JvmStatic
    fun malformedCases(): Stream<Arguments> {
      return Stream.of(
          arguments("Empty object", """{}"""),
          arguments("Invalid type name", """{"type":"foobar"}"""),
          arguments("String", """"foobar""""),
          arguments("Point with no coordinates", """{"type":"Point"}"""),
          arguments(
              "Point with string coordinates value",
              """{"type":"Point","coordinates":"[1,2,3]"}"""),
          arguments(
              "Point with unsupported CRS type",
              """{"type":"Point","coordinates":[1,1,1],"crs":{"type":"link","properties":{}}}"""),
          arguments("GeometryCollection with no geometries", """{"type":"GeometryCollection"}"""))
    }
  }
}
