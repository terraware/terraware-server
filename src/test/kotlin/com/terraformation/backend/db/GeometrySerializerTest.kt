package com.terraformation.backend.db

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.point
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.locationtech.jts.io.WKTReader

internal class GeometrySerializerTest {
  private val objectMapper = jacksonObjectMapper().registerModule(GeometryModule())

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("successCases")
  fun `parses all geometry types correctly`(
      name: String,
      json: String,
      expectedSrid: Int,
      expectedWkt: String,
  ) {
    val expected = WKTReader().read(expectedWkt)
    expected.srid = expectedSrid

    val geometry = objectMapper.readValue<Geometry>(json)
    assertEquals(expected, geometry, name)
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("successCases")
  fun `renders all geometry types correctly`(
      name: String,
      expected: String,
      srid: Int,
      wkt: String,
  ) {
    val geometry = WKTReader().read(wkt)
    geometry.srid = srid
    assertJsonIsEquivalent(expected, objectMapper.writeValueAsString(geometry), "$name: $wkt")
  }

  @ParameterizedTest(name = "{index}: {0}")
  @MethodSource("malformedCases")
  fun `detects malformed GeoJSON input`(name: String, json: String) {
    assertThrows<JsonParseException>(name) { objectMapper.readValue<Geometry>(json) }
  }

  @Test
  fun `parses specific geometry type`() {
    val json = """{"point":{"type":"Point","coordinates":[1.1,2.2,3.3]}}"""
    val point = point(1.1, 2.2, 3.3, SRID.LONG_LAT)
    val expected = PointPayload(point)
    val actual = objectMapper.readValue<PointPayload>(json)

    assertEquals(expected, actual)
  }

  @Test
  fun `rejects incorrect geometry type`() {
    val json = """{"point":{"type":"LineString","coordinates":[[1.1,2.2,3.3],[4,5,6]]}}"""

    assertThrows<JsonProcessingException> { objectMapper.readValue<PointPayload>(json) }
  }

  @Test
  fun `renders specific geometry type`() {
    val payload = PointPayload(point(1.1, 2.2, 3.3, SRID.LONG_LAT))
    val expected = """{"point":{"type":"Point","coordinates":[1.1,2.2,3.3]}}"""
    val actual = objectMapper.writeValueAsString(payload)

    assertEquals(expected, actual)
  }

  private fun assertJsonIsEquivalent(
      expectedJson: String,
      actualJson: String,
      message: String? = null,
  ) {
    val expectedMap = objectMapper.readValue<Map<*, *>>(expectedJson)
    val actualMap = objectMapper.readValue<Map<*, *>>(actualJson)
    assertEquals(expectedMap, actualMap, message)
  }

  companion object {
    @JvmStatic
    fun successCases(): Stream<Arguments> {
      val mercatorCrs = """"crs":{"type":"name","properties":{"name":"EPSG:3857"}}"""

      return Stream.of(
          arguments(
              "Point with long/lat SRID",
              """{"type":"Point","coordinates":[1.1,-2.2,3.3]}""",
              SRID.LONG_LAT,
              "POINT(1.1 -2.2 3.3)",
          ),
          arguments(
              "Point with Mercator SRID",
              """{"type":"Point",$mercatorCrs,"coordinates":[1.1,2.2,3.3]}""",
              SRID.SPHERICAL_MERCATOR,
              "POINT(1.1 2.2 3.3)",
          ),
          arguments(
              "MultiPoint with long/lat SRID",
              """{"type":"MultiPoint","coordinates":[[1,1,1],[2,2,2]]}""",
              SRID.LONG_LAT,
              "MULTIPOINT(1 1 1,2 2 2)",
          ),
          arguments(
              "MultiPoint with Mercator SRID",
              """{"type":"MultiPoint",$mercatorCrs,"coordinates":[[1,1,1],[2,2,2]]}""",
              SRID.SPHERICAL_MERCATOR,
              "MULTIPOINT(1 1 1,2 2 2)",
          ),
          arguments(
              "Polygon with long/lat SRID",
              """{"type":"Polygon","coordinates":[[[1,2,3],[2.1,1.1,0.1],[5,5,5],[1,2,3]]]}""",
              SRID.LONG_LAT,
              "POLYGON((1 2 3,2.1 1.1 0.1,5 5 5,1 2 3))",
          ),
          arguments(
              "MultiPolygon with long/lat SRID",
              """{"type":"MultiPolygon","coordinates":[[[[1,2,3],[1,2.5,3],[0.0,2.5,3],[1,2,3]]],[[[11,2,13],[11,2.5,13],[10,2.5,13],[11,2,13]]]]}""",
              SRID.LONG_LAT,
              "MULTIPOLYGON(((1 2 3,1 2.5 3,0 2.5 3,1 2 3)),((11 2 13,11 2.5 13,10 2.5 13,11 2 13)))",
          ),
          arguments(
              "MultiPolygon with Mercator SRID",
              """{"type":"MultiPolygon",$mercatorCrs,"coordinates":[[[[1,2,3],[1,2.5,3],[0.0,2.5,3],[1,2,3]]],[[[11,2,13],[11,2.5,13],[10,2.5,13],[11,2,13]]]]}""",
              SRID.SPHERICAL_MERCATOR,
              "MULTIPOLYGON(((1 2 3,1 2.5 3,0 2.5 3,1 2 3)),((11 2 13,11 2.5 13,10 2.5 13,11 2 13)))",
          ),
          arguments(
              "GeometryCollection with long/lat SRID",
              """{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2,3]},{"type":"Point","coordinates":[4,5,6]}]}""",
              SRID.LONG_LAT,
              "GEOMETRYCOLLECTION(POINT(1 2 3),POINT(4 5 6))",
          ),
          arguments(
              "GeometryCollection with Mercator SRID",
              """{"type":"GeometryCollection",$mercatorCrs,"geometries":[{"type":"Point","coordinates":[1,2,3]},{"type":"Point","coordinates":[4,5,6]}]}""",
              SRID.SPHERICAL_MERCATOR,
              "GEOMETRYCOLLECTION(POINT(1 2 3),POINT(4 5 6))",
          ),
          arguments(
              "LineString with long/lat SRID",
              """{"type":"LineString","coordinates":[[1,1,1],[2,2,2],[3,3,3]]}""",
              SRID.LONG_LAT,
              "LINESTRING(1 1 1,2 2 2,3 3 3)",
          ),
          arguments(
              "LineString with Mercator SRID",
              """{"type":"LineString",$mercatorCrs,"coordinates":[[1,1,1],[2,2,2],[3,3,3]]}""",
              SRID.SPHERICAL_MERCATOR,
              "LINESTRING(1 1 1,2 2 2,3 3 3)",
          ),
          arguments(
              "MultiLineString with long/lat SRID",
              """{"type":"MultiLineString","coordinates":[[[1,1,1],[2,2,2]],[[3,3,3],[4,4,4]]]}""",
              SRID.LONG_LAT,
              "MULTILINESTRING((1 1 1,2 2 2),(3 3 3,4 4 4))",
          ),
          arguments(
              "MultiLineString with Mercator SRID",
              """{"type":"MultiLineString",$mercatorCrs,"coordinates":[[[1,1,1],[2,2,2]],[[3,3,3],[4,4,4]]]}""",
              SRID.SPHERICAL_MERCATOR,
              "MULTILINESTRING((1 1 1,2 2 2),(3 3 3,4 4 4))",
          ),
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
              """{"type":"Point","coordinates":"[1,2,3]"}""",
          ),
          arguments("GeometryCollection with no geometries", """{"type":"GeometryCollection"}"""),
          arguments(
              "Line with too few points",
              """{"type":"LineString","coordinates":[[1,2,3]]}""",
          ),
          arguments(
              "Polygon with too few points",
              """{"type":"Polygon","coordinates":[[[1,2,3],[4,5,6],[1,2,3]]]}""",
          ),
      )
    }
  }

  // Dummy class to test serializing geometry fields of payload classes.
  data class PointPayload(val point: Point)
}
