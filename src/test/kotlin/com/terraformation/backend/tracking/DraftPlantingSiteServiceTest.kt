package com.terraformation.backend.tracking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.mockUser
import com.terraformation.backend.util.toMultiPolygon
import io.mockk.every
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.geotools.util.ContentFormatException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.locationtech.jts.geom.Geometry
import org.springframework.security.access.AccessDeniedException

class DraftPlantingSiteServiceTest : RunsAsUser {
  override val user = mockUser()

  private val objectMapper = jacksonObjectMapper().registerModule(GeometryModule())
  private val parser = GeometryFileParser(objectMapper)
  private val service = DraftPlantingSiteService(parser)
  private val draftId = DraftPlantingSiteId(1)

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
    val geometry = service.parseBoundaryFile(draftId, content, "boundary.$extension")

    assertGeometryEquals(parser.parse(content, "triangle.$resourceExtension"), geometry)
  }

  @ParameterizedTest
  @ValueSource(strings = ["PlantingSite", "PlantingZones"])
  fun `parses zipped shapefile and transforms coordinates`(basename: String) {
    val geometry =
        service.parseBoundaryFile(draftId, shapefileZip(basename = basename), "boundary.zip")
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
  @ValueSource(strings = ["kml", "kmz", "geojson", "json", "zip", "txt"])
  fun `rejects malformed or unsupported files`(extension: String) {
    assertThrows<ContentFormatException> {
      service.parseBoundaryFile(draftId, "not a geometry".toByteArray(), "boundary.$extension")
    }
  }

  @Test
  fun `requires filename`() {
    assertThrows<ContentFormatException> {
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
