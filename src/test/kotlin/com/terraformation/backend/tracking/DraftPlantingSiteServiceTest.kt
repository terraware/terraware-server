package com.terraformation.backend.tracking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.gis.GeometryFileException
import com.terraformation.backend.gis.GeometryFileFormat
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.gis.InvalidGeometryException
import com.terraformation.backend.gis.InvalidGeometryFileException
import com.terraformation.backend.gis.MultipleShapefilesException
import com.terraformation.backend.gis.NoPolygonsException
import com.terraformation.backend.gis.NoShapefileException
import com.terraformation.backend.gis.TooManyVerticesException
import com.terraformation.backend.gis.UnknownCoordinateSystemException
import com.terraformation.backend.gis.UnsupportedGeometryFileFormatException
import com.terraformation.backend.mockUser
import com.terraformation.backend.util.toMultiPolygon
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKTReader
import org.locationtech.jts.io.geojson.GeoJsonWriter

class DraftPlantingSiteServiceTest : RunsAsUser {
  override val user = mockUser()

  private val objectMapper = jacksonObjectMapper().registerModule(GeometryModule())
  private val parser = GeometryFileParser(objectMapper)
  private val service = DraftPlantingSiteService(parser)
  private val geometryFactory = GeometryFactory()
  private val wktReader = WKTReader()

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

    assertGeometryEquals(
        parser.parse(content, "triangle.$resourceExtension").toMultiPolygon(),
        result.geometry,
    )
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
              "{\"type\":\"MultiLineString\",\"coordinates\":[[[0,0],[1,1]],[[2,2],[3,3]]]}",
              "{\"type\":\"FeatureCollection\",\"features\":[" +
                  "{\"type\":\"Feature\",\"properties\":{}," +
                  "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1]]}}," +
                  "{\"type\":\"Feature\",\"properties\":{}," +
                  "\"geometry\":{\"type\":\"Point\",\"coordinates\":[2,2]}}]}",
          ]
  )
  fun `rejects files with no polygons`(content: String) {
    assertThrows<NoPolygonsException> {
      service.parseBoundaryFile(content.toByteArray(), "boundary.json")
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["shp", "shx", "dbf", "prj"])
  fun `rejects missing shapefile components`(extension: String) {
    val expected =
        when (extension) {
          "shp" -> NoShapefileException::class.java
          "prj" -> UnknownCoordinateSystemException::class.java
          else -> InvalidGeometryFileException::class.java
        }
    assertThrows(expected) {
      service.parseBoundaryFile(shapefileZip(extension), "boundary.zip")
    }
  }

  @Test
  fun `rejects multiple shapefiles`() {
    val content = javaClass.getResource("/tracking/TwoShapefiles.zip")!!.readBytes()
    assertThrows<MultipleShapefilesException> {
      service.parseBoundaryFile(content, "boundary.zip")
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["kml", "kmz", "geojson", "json", "zip", "txt"])
  fun `rejects malformed or unsupported files`(extension: String) {
    assertThrows<GeometryFileException> {
      service.parseBoundaryFile("not a geometry".toByteArray(), "boundary.$extension")
    }
  }

  @Test
  fun `rejects unsupported extensions even when the contents are readable`() {
    val content = javaClass.getResource("/gis/triangle.geojson")!!.readBytes()

    assertThrows<UnsupportedGeometryFileFormatException> {
      service.parseBoundaryFile(content, "boundary.gpx")
    }
  }

  @Test
  fun `requires filename`() {
    assertThrows<UnsupportedGeometryFileFormatException> {
      service.parseBoundaryFile(byteArrayOf(), null)
    }
  }

  @Test
  fun `classifies invalid geometry separately from malformed files`() {
    val cases =
        mapOf(
            "{broken" to InvalidGeometryFileException::class.java,
            """{"type":"Polygon","coordinates":[[[0,0],[2,2],[0,2],[2,0],[0,0]]]}""" to
                InvalidGeometryException::class.java,
            """{"type":"FeatureCollection","features":[]}""" to NoPolygonsException::class.java,
        )
    cases.forEach { (json, expected) ->
      assertThrows(expected) {
        service.parseBoundaryFile(json.toByteArray(), "boundary.json")
      }
    }
  }

  @Test
  fun `unions polygons`() {
    val result =
        parseShapes(
            "GEOMETRYCOLLECTION (POLYGON ((0 0, 2 0, 2 2, 0 2, 0 0)), " +
                "POLYGON ((1 0, 3 0, 3 2, 1 2, 1 0)))"
        )

    assertEquals(1, result.geometry.numGeometries)
    assertEquals(6.0, result.geometry.area)
    assertEquals(SRID.LONG_LAT, result.geometry.srid)
    assertTrue(result.geometry.isValid)
  }

  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "POINT (10 10)",
              "LINESTRING (20 20, 21 21)",
              "MULTIPOINT ((10 10))",
              "MULTILINESTRING ((20 20, 21 21))",
          ]
  )
  fun `ignores nonpolygonal shapes mixed with polygons`(shape: String) {
    val result = parseShapes("GEOMETRYCOLLECTION (POLYGON ((0 0, 2 0, 2 2, 0 2, 0 0)), $shape)")

    assertEquals(1, result.numPolygons)
    assertEquals(4.0, result.geometry.area)
  }

  @Test
  fun `ignores points and lines mixed with polygons in KML`() {
    val content =
        """<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""" +
            "<Placemark><Point><coordinates>5,5</coordinates></Point></Placemark>" +
            "<Placemark><LineString><coordinates>5,5 6,6</coordinates></LineString></Placemark>" +
            "<Placemark><Polygon><outerBoundaryIs><LinearRing>" +
            "<coordinates>0,0 1,0 1,1 0,1 0,0</coordinates>" +
            "</LinearRing></outerBoundaryIs></Polygon></Placemark>" +
            "</Document></kml>"

    val result = service.parseBoundaryFile(content.toByteArray(), "boundary.kml")

    assertEquals(1, result.numPolygons)
    assertEquals(1.0, result.geometry.area)
  }

  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "GEOMETRYCOLLECTION (POLYGON ((0 0, 2 2, 0 2, 2 0, 0 0)), " +
                  "POLYGON ((-1 -1, 3 -1, 3 3, -1 3, -1 -1)))",
              "MULTIPOLYGON (((0 0, 2 0, 2 2, 0 2, 0 0)), ((1 0, 3 0, 3 2, 1 2, 1 0)))",
          ]
  )
  fun `rejects invalid shapes before union`(wkt: String) {
    assertThrows<InvalidGeometryException> { parseShapes(wkt) }
  }

  @Test
  fun `preserves holes and disjoint polygons without enforcing editor area limits`() {
    val result =
        parseShapes(
            "MULTIPOLYGON (((0 0, 30 0, 30 30, 0 30, 0 0),(1 1, 1 2, 2 2, 2 1, 1 1)), " +
                "((40 40, 40.000001 40, 40 40.000001, 40 40)))"
        )

    assertEquals(2, result.numPolygons)
    assertEquals(899.0, result.geometry.area, 0.000001)
    assertEquals(14, result.geometry.numPoints)
  }

  @Test
  fun `strips altitude from coordinates`() {
    val content =
        """<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><Polygon><outerBoundaryIs>""" +
            "<LinearRing><coordinates>0,0,5 1,0,5 0,1,5 0,0,5</coordinates></LinearRing>" +
            "</outerBoundaryIs></Polygon></Placemark></kml>"

    val result = service.parseBoundaryFile(content.toByteArray(), "boundary.kml")

    val polygon = result.geometry.getGeometryN(0) as Polygon
    assertEquals(2, polygon.exteriorRing.coordinateSequence.dimension)
  }

  @Test
  fun `permits exactly fifty thousand vertices`() {
    assertEquals(50000, parseShapes(circle(50000)).geometry.numPoints)
  }

  @Test
  fun `rejects more than fifty thousand vertices`() {
    assertThrows<TooManyVerticesException> { parseShapes(circle(50001)) }
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

    assertThrows<TooManyVerticesException> { parseShapes(withHole) }
  }

  private fun parseShapes(wkt: String) = parseShapes(wktReader.read(wkt))

  private fun parseShapes(geometry: Geometry) =
      service.parseBoundaryFile(toGeoJson(geometry), "boundary.geojson")

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
