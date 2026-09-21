package com.terraformation.backend.tracking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.GeometryModule
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.gis.GeometryFileParser
import com.terraformation.backend.mockUser
import io.mockk.every
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.geotools.util.ContentFormatException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.access.AccessDeniedException

class DraftPlantingSiteServiceTest : RunsAsUser {
  override val user = mockUser()

  private val parser = GeometryFileParser(jacksonObjectMapper().registerModule(GeometryModule()))
  private val service = DraftPlantingSiteService(parser)
  private val draftId = DraftPlantingSiteId(1)

  @BeforeEach
  fun setUp() {
    every { user.canUpdateDraftPlantingSite(draftId) } returns true
  }

  @ParameterizedTest
  @ValueSource(strings = ["kml", "kmz", "geojson", "json", "GEOJSON"])
  fun `parses supported geometry files`(extension: String) {
    val resourceExtension = if (extension in listOf("json", "GEOJSON")) "geojson" else extension
    val content = javaClass.getResource("/gis/triangle.$resourceExtension")!!.readBytes()
    val geometries = service.uploadBoundaryFile(draftId, content, "boundary.$extension")

    assertEquals(1, geometries.size)
    assertGeometryEquals(parser.parse(content, "triangle.$resourceExtension"), geometries.single())
  }

  @Test
  fun `parses zipped shapefile and transforms coordinates`() {
    val geometries = service.uploadBoundaryFile(draftId, shapefileZip(), "boundary.zip")

    assertEquals(1, geometries.size)
    val geometry = geometries.single()
    assertFalse(geometry.isEmpty)
    assertEquals(SRID.LONG_LAT, geometry.srid)
    assertTrue(geometry.coordinates.all { it.x in -180.0..180.0 && it.y in -90.0..90.0 })
  }

  @ParameterizedTest
  @ValueSource(strings = ["shp", "shx", "dbf", "prj"])
  fun `rejects missing shapefile components`(extension: String) {
    assertThrows<ContentFormatException> {
      service.uploadBoundaryFile(draftId, shapefileZip(extension), "boundary.zip")
    }
  }

  @Test
  fun `rejects multiple shapefiles`() {
    val content = javaClass.getResource("/tracking/TwoShapefiles.zip")!!.readBytes()
    assertThrows<ContentFormatException> {
      service.uploadBoundaryFile(draftId, content, "boundary.zip")
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["kml", "kmz", "geojson", "json", "zip", "txt"])
  fun `rejects malformed or unsupported files`(extension: String) {
    assertThrows<ContentFormatException> {
      service.uploadBoundaryFile(draftId, "not a geometry".toByteArray(), "boundary.$extension")
    }
  }

  @Test
  fun `requires filename`() {
    assertThrows<ContentFormatException> {
      service.uploadBoundaryFile(draftId, byteArrayOf(), null)
    }
  }

  @Test
  fun `requires update permission before parsing`() {
    every { user.canUpdateDraftPlantingSite(draftId) } returns false
    every { user.canReadDraftPlantingSite(draftId) } returns true

    assertThrows<AccessDeniedException> {
      service.uploadBoundaryFile(draftId, byteArrayOf(), "bad.zip")
    }
  }

  private fun shapefileZip(omitExtension: String? = null): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zipOutput ->
      ZipInputStream(javaClass.getResourceAsStream("/tracking/TwoShapefiles.zip")!!).use { input ->
        generateSequence { input.nextEntry }
            .forEach { entry ->
              if (
                  entry.name.startsWith("PlantingSite.") &&
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
