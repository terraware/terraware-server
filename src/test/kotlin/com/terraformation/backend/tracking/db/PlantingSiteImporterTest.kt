package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.ShapefileGenerator
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
      val siteBoundary = gen.multiRectangle(0 to 0, 200 to 30)
      val subzoneFeature = gen.subzoneFeature(siteBoundary)

      val expected = PlantingSiteValidationFailure.zoneTooSmall("Z1")

      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))))
        fail("Should have throw exception for validation failure")
      } catch (e: PlantingSiteMapInvalidException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected), e.problems, "Did not find expected problem in problems list")
        }
      }
    }

    @Test
    fun `validates site map with overriden grid origin`() {
      val gen = ShapefileGenerator()

      // a 2x3 rows of 30m squares, with the top left one missing. This geometry has 5 valid plots
      // and 1 valid cluster, but will fail with a grid origin at the SW corne.
      //
      // Note: A perfect 25m by 25m leads to  99.46147461447877 percent of coverage, which is below
      // our tolerance
      val siteBoundary =
          gen.multiPolygon(
              0 to 0,
              30 to 0,
              30 to 30,
              60 to 30,
              60 to 90,
              0 to 90,
          )

      val subzoneFeature = gen.subzoneFeature(siteBoundary)

      val expected = PlantingSiteValidationFailure.zoneTooSmall("Z1")

      // Without grid origin
      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))))
        fail("Should have throw exception for validation failure")
      } catch (e: PlantingSiteMapInvalidException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected), e.problems, "Did not find expected problem in problems list")
        }
      }

      // With grid origin
      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))),
            gridOrigin = gen.point(3 to 33))
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
