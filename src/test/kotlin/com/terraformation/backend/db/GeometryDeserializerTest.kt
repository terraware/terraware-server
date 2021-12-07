package com.terraformation.backend.db

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.LineString
import net.postgis.jdbc.geometry.LinearRing
import net.postgis.jdbc.geometry.Point
import net.postgis.jdbc.geometry.Polygon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class GeometryDeserializerTest {
  private val objectMapper = ObjectMapper().registerModule(GeometryModule())

  @Test
  fun `well-formed point`() {
    val actual =
        objectMapper.readValue<Geometry>("""{ "type": "Point", "coordinates": [1.1, 2.2, 3.3] }""")

    assertEquals(Point(1.1, 2.2, 3.3).apply { srid = SRID.LONG_LAT }, actual)
  }

  @Test
  fun `well-formed explicit SRID`() {
    val actual =
        objectMapper.readValue<Geometry>(
            """{
          |"type": "Point",
          |"coordinates": [1.1, 2.2, 3.3],
          |"crs":{
          |  "type": "name",
          |  "properties": {
          |    "name": "EPSG:${SRID.SPHERICAL_MERCATOR}"
          |  }
          |}
          |}""".trimMargin())

    assertEquals(Point(1.1, 2.2, 3.3).apply { srid = SRID.SPHERICAL_MERCATOR }, actual)
  }

  @Test
  fun `well-formed polygon`() {
    val actual =
        objectMapper.readValue<Geometry>(
            """{
      |"type": "Polygon",
      |"coordinates":
      |  [
      |    [
      |      [0.0, 0.0, 0.0],
      |      [1.0, 0.0, 0.0],
      |      [0.0, 1.0, 0.0],
      |      [0.0, 0.0, 0.0]
      |    ]
      |  ]
      |}""".trimMargin())

    assertEquals(
        Polygon(
                arrayOf(
                    LinearRing(
                        arrayOf(
                            Point(0.0, 0.0, 0.0),
                            Point(1.0, 0.0, 0.0),
                            Point(0.0, 1.0, 0.0),
                            Point(0.0, 0.0, 0.0),
                        ))))
            .apply { srid = SRID.LONG_LAT },
        actual)
  }

  @Test
  fun `well-formed linestring`() {
    val actual =
        objectMapper.readValue<Geometry>(
            """{
      |"type": "LineString",
      |"coordinates":
      |  [
      |    [0.0, 0.0, 0.0],
      |    [1.0, 0.0, 0.0],
      |    [0.0, 1.0, 0.0]
      |  ]
      |}""".trimMargin())

    assertEquals(
        LineString(
                arrayOf(
                    Point(0.0, 0.0, 0.0),
                    Point(1.0, 0.0, 0.0),
                    Point(0.0, 1.0, 0.0),
                ))
            .apply { srid = SRID.LONG_LAT },
        actual)
  }

  @Test
  fun `line with too few points`() {
    assertThrows<JsonParseException> {
      objectMapper.readValue<Geometry>(
          """{ "type": "LineString", "coordinates": [ [0.0, 0.0, 0.0] ] }""")
    }
  }

  @Test
  fun `polygon with too few points`() {
    assertThrows<JsonParseException> {
      objectMapper.readValue<Geometry>(
          """{
      |"type": "Polygon",
      |"coordinates":
      |  [
      |    [
      |      [0.0, 0.0, 0.0],
      |      [1.0, 0.0, 0.0],
      |      [0.0, 0.0, 0.0]
      |    ]
      |  ]
      |}""".trimMargin())
    }
  }

  @Test
  fun `geometry values without coordinates`() {
    listOf(
        "GeometryCollection",
        "LineString",
        "MultiLineString",
        "MultiPoint",
        "MultiPolygon",
        "Point",
        "Polygon",
    )
        .forEach { typeName ->
          assertThrows<JsonParseException>(typeName) {
            objectMapper.readValue<Geometry>("""{"type": "$typeName"}""")
          }
        }
  }

  @Test
  fun `bogus SRID`() {
    assertThrows<JsonParseException> {
      objectMapper.readValue<Geometry>(
          """{
          |"type": "Point",
          |"coordinates": [1.1, 2.2, 3.3],
          |"crs":{
          |  "type": "name",
          |  "properties": {
          |    "name": "EPSG:bogus"
          |  }
          |}
          |}""".trimMargin())
    }
  }
}
