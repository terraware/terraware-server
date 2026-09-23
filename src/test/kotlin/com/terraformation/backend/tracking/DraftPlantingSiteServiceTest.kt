package com.terraformation.backend.tracking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.gis.GeometryFileErrorCode
import com.terraformation.backend.gis.GeometryFileException
import com.terraformation.backend.gis.GeometryFileFormat
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.mockUser
import com.terraformation.backend.util.toMultiPolygon
import io.mockk.every
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.geotools.util.ContentFormatException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.geojson.GeoJsonWriter
import org.springframework.security.access.AccessDeniedException

class DraftPlantingSiteServiceTest : RunsAsUser {
  override val user = mockUser()

  private val objectMapper = jacksonObjectMapper().registerModule(GeometryModule())
  private val parser = GeometryFileParser(objectMapper)
  private val service = DraftPlantingSiteService(parser)
  private val draftId = DraftPlantingSiteId(1)
  private val geometryFactory = GeometryFactory()

  @BeforeEach
  fun setUp() {
    every { user.canUpdateDraftPlantingSite(draftId) } returns true
  }

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
    val result = service.parseBoundaryFile(draftId, content, "boundary.$extension")

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
    val result =
        service.parseBoundaryFile(draftId, shapefileZip(basename = basename), "boundary.zip")
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

    val result = service.parseBoundaryFile(draftId, content, "boundary.json")

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
    val result = service.parseBoundaryFile(draftId, content.toByteArray(), "boundary.json")

    assertEquals(0, result.numPolygons)
    assertEquals(BigDecimal("0.000"), result.areaHa)
  }

  @ParameterizedTest
  @ValueSource(strings = ["shp", "shx", "dbf", "prj"])
  fun `rejects missing shapefile components`(extension: String) {
    assertThrows<ContentFormatException> {
      service.parseBoundaryFile(draftId, shapefileZip(extension), "boundary.zip")
    }
  }

  @Test
  fun `rejects multiple shapefiles`() {
    val content = javaClass.getResource("/tracking/TwoShapefiles.zip")!!.readBytes()
    assertThrows<ContentFormatException> {
      service.parseBoundaryFile(draftId, content, "boundary.zip")
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["kml", "kmz", "geojson", "json", "zip"])
  fun `rejects malformed files`(extension: String) {
    assertThrows<ContentFormatException> {
      service.parseBoundaryFile(draftId, "not a geometry".toByteArray(), "boundary.$extension")
    }
  }

  @Test
  fun `requires filename`() {
    assertThrows<GeometryFileException> {
      service.parseBoundaryFile(draftId, byteArrayOf(), null)
    }
  }

  @Test
  fun `requires update permission before parsing`() {
    every { user.canUpdateDraftPlantingSite(draftId) } returns false
    every { user.canReadDraftPlantingSite(draftId) } returns true

    assertThrows<AccessDeniedException> {
      service.parseBoundaryFile(draftId, byteArrayOf(), "bad.zip")
    }
  }

  @Test
  fun `rejects unsupported extensions even when the contents are readable`() {
    val content = javaClass.getResource("/gis/triangle.geojson")!!.readBytes()

    assertEquals(
        GeometryFileErrorCode.UnsupportedFormat,
        assertThrows<GeometryFileException> {
              service.parseBoundaryFile(draftId, content, "boundary.gpx")
            }
            .code,
    )
  }

  @Test
  fun `permits exactly fifty thousand vertices`() {
    assertEquals(50000, parseShapes(circle(50000)).geometry.numPoints)
  }

  @Test
  fun `rejects more than fifty thousand vertices`() {
    assertBoundaryCode(GeometryFileErrorCode.TooManyVertices, circle(50001))
  }

  @Test
  fun `counts vertices after union`() {
    val circle = circle(30000)
    val collection = geometryFactory.createGeometryCollection(arrayOf(circle, circle))

    assertEquals(30000, parseShapes(collection).geometry.numPoints)
  }

  @Test
  fun `includes holes in vertex limit`() {
    val withHole =
        geometryFactory.createPolygon(
            circle(30000, 2.0).exteriorRing,
            arrayOf(circle(20001).exteriorRing),
        )

    assertBoundaryCode(GeometryFileErrorCode.TooManyVertices, withHole)
  }

  private fun parseShapes(geometry: Geometry) =
      service.parseBoundaryFile(draftId, toGeoJson(geometry), "boundary.geojson")

  private fun assertBoundaryCode(code: GeometryFileErrorCode, geometry: Geometry) {
    assertEquals(
        code,
        assertThrows<GeometryFileException> { parseShapes(geometry) }.code,
    )
  }

  private fun toGeoJson(geometry: Geometry): ByteArray =
      GeoJsonWriter().apply { setEncodeCRS(false) }.write(geometry).toByteArray()

  /** Builds a polygon approximating a circle with a given total number of coordinates. */
  private fun circle(numPoints: Int, radius: Double = 1.0): Polygon {
    val coordinates =
        (0 until numPoints - 1).map { index ->
          val angle = 2 * PI * index / (numPoints - 1)
          Coordinate(radius * cos(angle), radius * sin(angle))
        }

    return geometryFactory.createPolygon((coordinates + coordinates.first()).toTypedArray())
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
