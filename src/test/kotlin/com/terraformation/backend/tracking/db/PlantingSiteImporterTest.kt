package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.ShapefileGenerator
import com.terraformation.backend.tracking.db.PlantingSiteImporter.ValidationOption
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.ShapefileFeature
import io.mockk.every
import kotlin.io.path.Path
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(PLANTING_SITES, PLANTING_ZONES, PLANTING_SUBZONES)

  private val clock = TestClock()
  private val importer: PlantingSiteImporter by lazy {
    PlantingSiteImporter(
        clock,
        dslContext,
        plantingSitesDao,
        PlantingSiteStore(
            clock,
            dslContext,
            TestEventPublisher(),
            monitoringPlotsDao,
            ParentStore(dslContext),
            plantingSeasonsDao,
            plantingSitesDao,
            plantingSubzonesDao,
            plantingZonesDao),
        plantingZonesDao,
        plantingSubzonesDao)
  }

  private val resourcesDir = "src/test/resources/tracking"

  @BeforeEach
  fun setUp() {
    every { user.canCreatePlantingSite(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true

    insertUser()
    insertOrganization()
    insertOrganizationUser()
  }

  @Test
  fun `throws exception if no permission to create planting sites`() {
    every { user.canCreatePlantingSite(any()) } returns false
    every { user.canReadOrganization(any()) } returns true

    assertThrows<AccessDeniedException> {
      importer.import(
          "name",
          "description",
          organizationId,
          Shapefile.fromZipFile(Path("$resourcesDir/TooFewShapefiles.zip")),
          emptySet())
    }
  }

  @Nested
  inner class Validation {
    @Test
    fun `detects too few shapefiles`() {
      assertHasProblem(
          "TooFewShapefiles.zip",
          ValidationOption.ZonesContainedInSite,
          "Expected 3 or 4 shapefiles (site, zones, subzones, and optionally exclusions) but found 2")
    }

    @Test
    fun `detects zone that is big enough for 5 plots but too small for a permanent cluster`() {
      val gen = ShapefileGenerator()
      val siteBoundary = gen.multiRectangle(0 to 0, 200 to 30)
      val siteFeature = gen.siteFeature(siteBoundary)
      val zoneFeature = gen.zoneFeature(siteBoundary)
      val subzoneFeature = gen.subzoneFeature(siteBoundary)

      assertHasProblem(
          "Could not create enough monitoring plots in zone Z1 (is the zone at least 150x75 meters?)",
          listOf(siteFeature),
          listOf(zoneFeature),
          listOf(subzoneFeature))
    }

    @Test
    fun `detects zone that is big enough for a permanent cluster but not also for a temporary plot`() {
      val gen = ShapefileGenerator()
      val siteBoundary = gen.multiRectangle(0 to 0, 60 to 60)
      val siteFeature = gen.siteFeature(siteBoundary)
      val zoneFeature = gen.zoneFeature(siteBoundary)
      val subzoneFeature = gen.subzoneFeature(siteBoundary)

      assertHasProblem(
          "Could not create enough monitoring plots in zone Z1 (is the zone at least 150x75 meters?)",
          listOf(siteFeature),
          listOf(zoneFeature),
          listOf(subzoneFeature))
    }

    private fun assertHasProblem(expected: String, importFunc: () -> Unit) {
      try {
        importFunc()
        fail("Should have throw exception for validation failure")
      } catch (e: PlantingSiteUploadProblemsException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected), e.problems, "Did not find expected problem in problems list")
        }
      }
    }

    private fun assertHasProblem(
        zipFile: String,
        validationOption: ValidationOption,
        expected: String
    ) {
      assertHasProblem(expected) {
        importer.import(
            "name",
            "description",
            organizationId,
            Shapefile.fromZipFile(Path("$resourcesDir/$zipFile")),
            setOf(validationOption))
      }
    }

    private fun assertHasProblem(
        expected: String,
        siteFeatures: List<ShapefileFeature>,
        zoneFeatures: List<ShapefileFeature>,
        subzoneFeatures: List<ShapefileFeature>,
        exclusions: List<ShapefileFeature>? = null,
    ) {
      assertHasProblem(expected) {
        importer.importShapefiles(
            name = "Test Site",
            organizationId = organizationId,
            siteFile = Shapefile(siteFeatures),
            zonesFile = Shapefile(zoneFeatures),
            subzonesFile = Shapefile(subzoneFeatures),
            exclusionsFile = exclusions?.let { Shapefile(it) },
        )
      }
    }
  }
}
