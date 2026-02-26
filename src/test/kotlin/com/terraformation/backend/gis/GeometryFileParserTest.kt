package com.terraformation.backend.gis

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.util.toMultiPolygon
import org.geotools.util.ContentFormatException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import tools.jackson.module.kotlin.jacksonMapperBuilder

class GeometryFileParserTest {
  private val objectMapper = jacksonMapperBuilder().addModule(GeometryModule()).build()
  private val parser = GeometryFileParser(objectMapper)

  private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
  private val triangle =
      geometryFactory
          .createMultiPolygon(
              arrayOf(
                  geometryFactory.createPolygon(
                      arrayOf(
                          Coordinate(-155.63200453, 19.16360697),
                          Coordinate(-155.63297767, 19.16570482),
                          Coordinate(-155.63433804, 19.16382232),
                          Coordinate(-155.63200453, 19.16360697),
                      )
                  )
              )
          )
          .norm()

  @Test
  fun `can parse GeoJSON file`() {
    runTriangleScenario("/gis/triangle.geojson")
  }

  @Test
  fun `can parse KML file`() {
    runTriangleScenario("/gis/triangle.kml")
  }

  @Test
  fun `can parse KMZ file`() {
    runTriangleScenario("/gis/triangle.kmz")
  }

  @Test
  fun `throws exception if file type is unrecognized`() {
    assertThrows<ContentFormatException> { parser.parse(byteArrayOf(1, 2, 3, 4), "dummy.bin") }
  }

  @Test
  fun `throws exception if GeoJSON file is malformed`() {
    assertThrows<ContentFormatException> {
      parser.parse("""{"foo":"bar"}""".encodeToByteArray(), "malformed.json")
    }
  }

  @Test
  fun `throws exception if KML file is malformed`() {
    assertThrows<ContentFormatException> {
      parser.parse("""<not-kml />""".encodeToByteArray(), "malformed.kml")
    }
  }

  @Test
  fun `throws exception if KMZ file is malformed`() {
    assertThrows<ContentFormatException> { parser.parse(byteArrayOf(1, 2, 3, 4), "malformed.kmz") }
  }

  @Test
  fun `throws exception if KMZ file has no KML file`() {
    assertThrows<ContentFormatException> { runTriangleScenario("/gis/no-kml.kmz") }
  }

  private fun runTriangleScenario(resourcePath: String) {
    javaClass.getResourceAsStream(resourcePath).use { stream ->
      val bytes = stream.readAllBytes()

      val geometry = parser.parse(bytes, resourcePath)

      assertGeometryEquals(triangle, geometry.toMultiPolygon().norm())
    }
  }
}
