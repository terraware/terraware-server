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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

  @Test
  fun `parses GeoJSON when the filename has no usable extension`() {
    val content = javaClass.getResource("/gis/triangle.geojson")!!.readBytes()

    // Tika reports GeoJSON as text/plain unless the name ends in .json, and callers don't always
    // have a real filename to give us.
    assertGeometryEquals(triangle, parser.parse(content, "file").toMultiPolygon().norm())
    assertGeometryEquals(triangle, parser.parse(content, null).toMultiPolygon().norm())
  }

  @Test
  fun `parse rejects a file with no geometries`() {
    val content = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document/></kml>"""

    assertThrows<ContentFormatException> { parser.parse(content.toByteArray(), "empty.kml") }
  }

  @Test
  fun `parse dissolves the parts of a single multi-part shape`() {
    val content =
        """{"type":"MultiPolygon","coordinates":[[[[0,0],[2,0],[2,2],[0,2],[0,0]]],[[[1,0],[3,0],[3,2],[1,2],[1,0]]]]}"""

    val geometry = parser.parse(content.toByteArray(), "overlapping.geojson")

    assertEquals("Polygon", geometry.geometryType)
    assertEquals(6.0, geometry.area)
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

  @ParameterizedTest
  @CsvSource(
      "triangle.geojson, triangle.geojson, GeoJSON",
      "triangle.kml, triangle.kml, KML",
      "triangle.kmz, triangle.kmz, KMZ",
      "triangle.kmz, triangle.zip, KMZ",
  )
  fun `parseWithFormat returns geometry and detected format`(
      resourceName: String,
      filename: String,
      expectedFormat: GeometryFileFormat,
  ) {
    val content = javaClass.getResource("/gis/$resourceName")!!.readBytes()
    val parsed = parser.parseWithFormat(content, filename)
    assertEquals(expectedFormat, parsed.format)
    assertGeometryEquals(triangle, parsed.geometry.toMultiPolygon().norm())
  }

  @Test
  fun `parseWithFormat returns geometry and shapefile format for zipped shapefile`() {
    val expected =
        javaClass.getResourceAsStream("/gis/PlantingSite.geojson").use {
          objectMapper.readValue<Geometry>(it)
        }
    val parsed = parser.parseWithFormat(shapefileZip(), "boundary.zip")
    assertEquals(GeometryFileFormat.Shapefile, parsed.format)
    assertGeometryEquals(expected.toMultiPolygon().norm(), parsed.geometry.toMultiPolygon().norm())
  }

  @ParameterizedTest
  @ValueSource(strings = ["{broken", "{}", "null", "", "{\"type\":\"FeatureCollection\"}"])
  fun `rejects malformed GeoJSON`(json: String) {
    assertCode(GeometryFileErrorCode.InvalidFile, json.toByteArray(), "boundary.json")
  }

  @Test
  fun `readWithFormat preserves invalid geometry for boundary validation`() {
    val shapes =
        readShapes(
            """{"type":"Polygon","coordinates":[[[0,0],[2,2],[0,2],[2,0],[0,0]]]}""".toByteArray(),
            "boundary.geojson",
        )

    assertFalse(shapes.single().isValid)
  }

  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "{\"type\":\"FeatureCollection\",\"features\":[]}",
              "{\"type\":\"GeometryCollection\",\"geometries\":[]}",
              "{\"type\":\"Feature\",\"geometry\":null}",
          ]
  )
  fun `readWithFormat accepts empty collections`(json: String) {
    assertTrue(readShapes(json.toByteArray(), "boundary.json").isEmpty())
  }

  @Test
  fun `readWithFormat unwraps features and geometry collections without union`() {
    val parsed =
        parser.readWithFormat(
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"GeometryCollection","geometries":[{"type":"Point","coordinates":[1,2]},{"type":"Polygon","coordinates":[[[0,0],[1,0],[0,1],[0,0]]]}]}}]}"""
                .toByteArray(),
            "boundary.geojson",
        )

    assertEquals(listOf("Point", "Polygon"), parsed.geometries.map { it.geometryType })
    assertEquals(GeometryFileFormat.GeoJSON, parsed.format)
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "<Folder>", "<Document><Folder>"])
  fun `reads KML placemarks at any depth`(containers: String) {
    val closing =
        when (containers) {
          "<Folder>" -> "</Folder>"
          "<Document><Folder>" -> "</Folder></Document>"
          else -> ""
        }
    val content =
        """<kml xmlns="http://www.opengis.net/kml/2.2">$containers<Placemark><Polygon><outerBoundaryIs><LinearRing><coordinates>0,0,5 1,0,5 0,1,5 0,0,5</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>$closing</kml>"""

    val parsed = parser.readWithFormat(content.toByteArray(), "boundary.kml")

    assertEquals(1, parsed.geometries.size)
    assertEquals(GeometryFileFormat.KML, parsed.format)
  }

  @Test
  fun `empty KML has no geometries`() {
    val content = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document/></kml>"""

    assertTrue(readShapes(content.toByteArray(), "empty.kml").isEmpty())
  }

  @Test
  fun `malformed KML is invalid file`() {
    assertCode(GeometryFileErrorCode.InvalidFile, "<kml><broken".toByteArray(), "boundary.kml")
  }

  @ParameterizedTest
  @ValueSource(strings = ["a,b 1,0 0,1 0,0", "0,0 1,0 0,1"])
  fun `rejects malformed KML rings instead of repairing them`(coordinates: String) {
    val content =
        """<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><Polygon><outerBoundaryIs><LinearRing><coordinates>$coordinates</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></kml>"""

    assertCode(GeometryFileErrorCode.InvalidFile, content.toByteArray(), "boundary.kml")
  }

  @Test
  fun `malformed KML point is invalid file`() {
    val content =
        """<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><Point><coordinates>a,b</coordinates></Point></Placemark></kml>"""

    assertCode(GeometryFileErrorCode.InvalidFile, content.toByteArray(), "boundary.kml")
  }

  @Test
  fun `renamed GPX is unsupported`() {
    assertCode(
        GeometryFileErrorCode.UnsupportedFormat,
        """<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1"/>"""
            .toByteArray(),
        "boundary.kml",
    )
  }

  @Test
  fun `unsupported content is rejected`() {
    assertCode(
        GeometryFileErrorCode.UnsupportedFormat,
        """<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1"/>"""
            .toByteArray(),
        "boundary.gpx",
    )
  }

  private fun assertCode(code: GeometryFileErrorCode, content: ByteArray, filename: String?) {
    assertEquals(
        code,
        assertThrows<GeometryFileException> { parser.readWithFormat(content, filename) }.code,
    )
  }

  private fun readShapes(content: ByteArray, filename: String?): List<Geometry> =
      parser.readWithFormat(content, filename).geometries

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
