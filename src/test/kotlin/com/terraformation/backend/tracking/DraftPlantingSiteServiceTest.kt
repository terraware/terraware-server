package com.terraformation.backend.tracking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.gis.GeometryFileFormat
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.mockUser
import com.terraformation.backend.util.toMultiPolygon
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.geotools.util.ContentFormatException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.locationtech.jts.geom.Geometry

class DraftPlantingSiteServiceTest : RunsAsUser {
  override val user = mockUser()

  private val objectMapper = jacksonObjectMapper().registerModule(GeometryModule())
  private val parser = GeometryFileParser(objectMapper)
  private val service = DraftPlantingSiteService(parser)

  @ParameterizedTest
  @ValueSource(strings = ["kml", "kmz", "geojson", "json", "GEOJSON", "zip", "ZIP"])
  fun `parses supported geometry files`(extension: String) {
    val resourceExtension =
        when (extension.lowercase()) {
          "json",
          "geojson" -> "geojson"
          "zip" -> "kmz"
          else -> extension
        }
    val content = javaClass.getResource("/gis/triangle.$resourceExtension")!!.readBytes()
    val result = service.parseBoundaryFile(content, "boundary.$extension")

    assertGeometryEquals(parser.parse(content, "triangle.$resourceExtension"), result.geometry)
    assertEquals("boundary.$extension", result.filename)
    assertEquals(1, result.numPolygons)
    assertEquals(
        when (resourceExtension) {
          "geojson" -> GeometryFileFormat.GeoJSON
          "kml" -> GeometryFileFormat.KML
          else -> GeometryFileFormat.KMZ
        },
        result.format,
    )
  }

  @ParameterizedTest
  @ValueSource(strings = ["PlantingSite", "Strata"])
  fun `parses zipped shapefile and transforms coordinates`(basename: String) {
    val result = service.parseBoundaryFile(shapefileZip(basename = basename), "boundary.zip")
    val expected =
        javaClass.getResourceAsStream("/gis/$basename.geojson").use {
          objectMapper.readValue<Geometry>(it)
        }

    assertGeometryEquals(expected.toMultiPolygon().norm(), result.geometry.toMultiPolygon().norm())
    assertEquals(SRID.LONG_LAT, result.geometry.srid)
    assertEquals(GeometryFileFormat.Shapefile, result.format)
    assertEquals("boundary.zip", result.filename)
  }

  @Test
  fun `counts disjoint polygons and calculates area after union`() {
    val polygon =
        """
        {
          "type": "Polygon",
          "coordinates": [[
            [-76.13567116641384, 5.989357251936355],
            [-76.12762679268639, 5.989357251773201],
            [-76.12762679270281, 5.979015683955292],
            [-76.13567116598097, 5.979015684419131],
            [-76.13567116641384, 5.989357251936355]
          ]]
        }
        """
            .trimIndent()
    val otherPolygon = polygon.replace("-76.", "-77.")
    val content =
        """{"type":"GeometryCollection","geometries":[$polygon,$polygon,$otherPolygon]}"""
            .toByteArray()

    val result = service.parseBoundaryFile(content, "boundary.json")

    assertEquals(2, result.numPolygons)
    assertEquals(BigDecimal("203.715"), result.areaHa)
    assertEquals(GeometryFileFormat.GeoJSON, result.format)
  }

  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "{\"type\":\"Point\",\"coordinates\":[0,0]}",
              "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1]]}",
          ]
  )
  fun `returns zero polygons and area for nonpolygonal geometry`(content: String) {
    val result = service.parseBoundaryFile(content.toByteArray(), "boundary.json")

    assertEquals(0, result.numPolygons)
    assertEquals(BigDecimal("0.000"), result.areaHa)
  }

  @ParameterizedTest
  @ValueSource(strings = ["shp", "shx", "dbf", "prj"])
  fun `rejects missing shapefile components`(extension: String) {
    assertThrows<ContentFormatException> {
      service.parseBoundaryFile(shapefileZip(extension), "boundary.zip")
    }
  }

  @Test
  fun `rejects multiple shapefiles`() {
    val content = javaClass.getResource("/tracking/TwoShapefiles.zip")!!.readBytes()
    assertThrows<ContentFormatException> {
      service.parseBoundaryFile(content, "boundary.zip")
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["kml", "kmz", "geojson", "json", "zip", "txt"])
  fun `rejects malformed or unsupported files`(extension: String) {
    assertThrows<ContentFormatException> {
      service.parseBoundaryFile("not a geometry".toByteArray(), "boundary.$extension")
    }
  }

  @Test
  fun `requires filename`() {
    assertThrows<ContentFormatException> {
      service.parseBoundaryFile(byteArrayOf(), null)
    }
  }

  private fun shapefileZip(
      omitExtension: String? = null,
      basename: String = "PlantingSite",
  ): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zipOutput ->
      ZipInputStream(javaClass.getResourceAsStream("/tracking/TwoShapefiles.zip")!!).use { input ->
        generateSequence { input.nextEntry }
            .forEach { entry ->
              if (
                  entry.name.startsWith("$basename.") &&
                      entry.name.substringAfterLast('.') != omitExtension
              ) {
                zipOutput.putNextEntry(ZipEntry(entry.name))
                input.copyTo(zipOutput)
                zipOutput.closeEntry()
              }
            }
      }
    }
    return output.toByteArray()
  }
}
