package com.terraformation.backend.gis

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.util.toMultiPolygon
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.geotools.util.ContentFormatException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

class GeometryFileParserTest {
  private val objectMapper = jacksonObjectMapper().registerModule(GeometryModule())
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
  fun `unions overlapping polygons in GeoJSON geometry collection`() {
    val content =
        """
        {
          "type": "GeometryCollection",
          "geometries": [
            {
              "type": "Polygon",
              "coordinates": [[[0, 0], [2, 0], [2, 2], [0, 2], [0, 0]]]
            },
            {
              "type": "Polygon",
              "coordinates": [[[1, 0], [3, 0], [3, 2], [1, 2], [1, 0]]]
            }
          ]
        }
        """
            .trimIndent()
            .encodeToByteArray()
    val expected =
        geometryFactory.createPolygon(
            arrayOf(
                Coordinate(0.0, 0.0),
                Coordinate(1.0, 0.0),
                Coordinate(2.0, 0.0),
                Coordinate(3.0, 0.0),
                Coordinate(3.0, 2.0),
                Coordinate(2.0, 2.0),
                Coordinate(1.0, 2.0),
                Coordinate(0.0, 2.0),
                Coordinate(0.0, 0.0),
            )
        )

    val geometry = parser.parse(content, "overlapping-polygons.geojson")

    assertGeometryEquals(expected.norm(), geometry.norm())
    assertEquals(SRID.LONG_LAT, geometry.srid)
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

  @Test
  fun `can parse zip containing KML`() {
    runTriangleScenario("/gis/triangle.kmz", "triangle.zip")
  }

  @ParameterizedTest
  @ValueSource(strings = ["PlantingSite", "Strata"])
  fun `can parse zipped shapefile and transform coordinates`(basename: String) {
    val geometry = parser.parse(shapefileZip(basename = basename), "boundary.zip")
    val expected =
        javaClass.getResourceAsStream("/gis/$basename.geojson").use {
          objectMapper.readValue<Geometry>(it)
        }

    assertGeometryEquals(expected.toMultiPolygon().norm(), geometry.toMultiPolygon().norm())
    assertEquals(SRID.LONG_LAT, geometry.srid)
  }

  @ParameterizedTest
  @ValueSource(strings = ["shp", "shx", "dbf", "prj"])
  fun `rejects missing shapefile components`(extension: String) {
    assertThrows<ContentFormatException> { parser.parse(shapefileZip(extension), "boundary.zip") }
  }

  @Test
  fun `rejects multiple shapefiles`() {
    val content = javaClass.getResource("/tracking/TwoShapefiles.zip")!!.readBytes()
    assertThrows<ContentFormatException> { parser.parse(content, "boundary.zip") }
  }

  @Test
  fun `rejects zip with no supported geometry file`() {
    val content = javaClass.getResource("/gis/no-kml.kmz")!!.readBytes()
    assertThrows<ContentFormatException> { parser.parse(content, "boundary.zip") }
  }

  @ParameterizedTest
  @ValueSource(strings = ["__MACOSX/._PlantingSite.shp", "._PlantingSite.shp"])
  fun `ignores AppleDouble shapefile metadata`(filename: String) {
    val content = shapefileZip(extraEntries = mapOf(filename to 100))
    assertPlantingSiteGeometry(content)
  }

  @Test
  fun `ignores unrelated archive entries`() {
    val content =
        shapefileZip(extraEntries = mapOf("other/PlantingSite.dbf" to 100 * 1024 * 1024 + 1))
    assertPlantingSiteGeometry(content)
  }

  private fun assertPlantingSiteGeometry(content: ByteArray) {
    val expected =
        javaClass.getResourceAsStream("/gis/PlantingSite.geojson").use {
          objectMapper.readValue<Geometry>(it)
        }
    val actual = parser.parse(content, "boundary.zip")
    assertGeometryEquals(expected.toMultiPolygon().norm(), actual.toMultiPolygon().norm())
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `rejects oversized shapefile component`(understateSizes: Boolean) {
    val content =
        shapefileZip(
            omitExtension = "dbf",
            extraEntries = mapOf("PlantingSite.dbf" to 100 * 1024 * 1024 + 1),
            understateSizes = understateSizes,
        )
    val exception = assertThrows<ContentFormatException> { parser.parse(content, "boundary.zip") }
    assertEquals("Shapefile component exceeds 104857600 uncompressed bytes", exception.message)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `rejects excessive total uncompressed shapefile size`(understateSizes: Boolean) {
    val content =
        shapefileZip(
            extraEntries =
                mapOf(
                    "PlantingSite.shp" to 75 * 1024 * 1024,
                    "PlantingSite.shx" to 75 * 1024 * 1024,
                    "PlantingSite.dbf" to 75 * 1024 * 1024,
                ),
            understateSizes = understateSizes,
        )
    val exception = assertThrows<ContentFormatException> { parser.parse(content, "boundary.zip") }
    assertEquals("Shapefile exceeds 209715200 total uncompressed bytes", exception.message)
  }

  private fun shapefileZip(
      omitExtension: String? = null,
      basename: String = "PlantingSite",
      extraEntries: Map<String, Int> = emptyMap(),
      understateSizes: Boolean = false,
  ): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zipOutput ->
      for ((name, size) in extraEntries) {
        zipOutput.putNextEntry(ZipEntry(name))
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
          val count = minOf(remaining, buffer.size)
          zipOutput.write(buffer, 0, count)
          remaining -= count
        }
        zipOutput.closeEntry()
      }
      ZipInputStream(javaClass.getResourceAsStream("/tracking/TwoShapefiles.zip")!!).use { input ->
        generateSequence { input.nextEntry }
            .forEach { entry ->
              if (
                  entry.name.startsWith("$basename.") &&
                      entry.name !in extraEntries &&
                      entry.name.substringAfterLast('.') != omitExtension
              ) {
                zipOutput.putNextEntry(ZipEntry(entry.name))
                input.copyTo(zipOutput)
                zipOutput.closeEntry()
              }
            }
      }
    }
    val bytes = output.toByteArray()
    if (understateSizes) {
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      // The end-of-central-directory record points to the first central directory entry.
      var offset = buffer.getInt(bytes.size - 6)
      while (buffer.getInt(offset) == 0x02014b50) {
        // Understate the uncompressed size without changing the compressed data or its length.
        buffer.putInt(offset + 24, 1)
        offset +=
            46 +
                (buffer.getShort(offset + 28).toInt() and 0xffff) +
                (buffer.getShort(offset + 30).toInt() and 0xffff) +
                (buffer.getShort(offset + 32).toInt() and 0xffff)
      }
    }
    return bytes
  }

  private fun runTriangleScenario(resourcePath: String, filename: String = resourcePath) {
    javaClass.getResourceAsStream(resourcePath).use { stream ->
      val bytes = stream.readAllBytes()

      val geometry = parser.parse(bytes, filename)

      assertGeometryEquals(triangle, geometry.toMultiPolygon().norm())
    }
  }
}
