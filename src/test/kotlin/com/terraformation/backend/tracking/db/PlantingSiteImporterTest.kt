package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.ShapefileGenerator
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailure
import com.terraformation.backend.tracking.model.Shapefile
import io.mockk.every
import kotlin.io.path.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val importer: PlantingSiteImporter by lazy {
    PlantingSiteImporter(
        PlantingSiteStore(
            clock,
            TestSingletons.countryDetector,
            dslContext,
            TestEventPublisher(),
            monitoringPlotsDao,
            ParentStore(dslContext),
            plantingSeasonsDao,
            plantingSitesDao,
            plantingSubzonesDao,
            plantingZonesDao))
  }

  private val resourcesDir = "src/test/resources/tracking"

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    every { user.canCreatePlantingSite(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true

    organizationId = insertOrganization()
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
          Shapefile.fromZipFile(Path("$resourcesDir/TwoShapefiles.zip")))
    }
  }

  @Nested
  inner class Validation {
    @Test
    fun `detects too few shapefiles`() {
      assertHasProblem("Expected subzones and optionally exclusions but found 0 shapefiles") {
        importer.import(
            "name",
            "description",
            organizationId,
            Shapefile.fromZipFile(Path("$resourcesDir/NoShapefiles.zip")))
      }
    }

    // Site validation logic is tested in PlantingSiteModelTest; this is just to confirm that
    // shapefile import actually validates the site before creating it.
    @Test
    fun `validates site map`() {
      val gen = ShapefileGenerator()
      val siteBoundary =
          gen.multiRectangle(0 to 0, MONITORING_PLOT_SIZE_INT - 1 to MONITORING_PLOT_SIZE_INT)
      val subzoneFeature = gen.subzoneFeature(siteBoundary)

      val expected = PlantingSiteValidationFailure.zoneTooSmall("Z1")

      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))))
        fail("Should have thrown exception for validation failure")
      } catch (e: PlantingSiteMapInvalidException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected), e.problems, "Did not find expected problem in problems list")
        }
      }
    }

    @Test
    fun `validates site map with overridden grid origin`() {
      val gen = ShapefileGenerator()

      // A rectangle with a 2m square notch missing in the southwest corner. This geometry has room
      // for 2 valid plots, but will fail with a grid origin at the SW corner.
      val siteBoundary =
          gen.multiPolygon(
              2 to 0,
              MONITORING_PLOT_SIZE_INT * 2 + 5 to 0,
              MONITORING_PLOT_SIZE_INT * 2 + 5 to MONITORING_PLOT_SIZE_INT + 1,
              0 to MONITORING_PLOT_SIZE_INT + 1,
              0 to 2,
              2 to 2,
              2 to 0,
          )

      val subzoneFeature = gen.subzoneFeature(siteBoundary)

      val expected = PlantingSiteValidationFailure.zoneTooSmall("Z1")

      // Without grid origin
      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))))
        fail("Should have thrown exception for validation failure")
      } catch (e: PlantingSiteMapInvalidException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected), e.problems, "Did not find expected problem in problems list")
        }
      }

      // With grid origin 2 meters east, which avoids the notch
      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))),
            gridOrigin = gen.point(2 to 0))
      } catch (e: PlantingSiteMapInvalidException) {
        // Display meaningful assertion message for a test failure
        assertEquals(
            emptyList<PlantingSiteValidationFailure>(),
            e.problems,
            "Import should not have any problems")
      }
    }

    private fun assertHasProblem(expected: String, importFunc: () -> Unit) {
      try {
        importFunc()
        fail("Should have throw exception for validation failure")
      } catch (e: ShapefilesInvalidException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected), e.problems, "Did not find expected problem in problems list")
        }
      }
    }
  }
}
