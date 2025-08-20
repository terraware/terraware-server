package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.ShapefileGenerator
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailure
import com.terraformation.backend.tracking.model.Shapefile
import io.mockk.every
import java.math.BigDecimal
import kotlin.io.path.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
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
            IdentifierGenerator(clock, dslContext),
            monitoringPlotsDao,
            ParentStore(dslContext),
            plantingSeasonsDao,
            plantingSitesDao,
            plantingSubzonesDao,
            plantingZonesDao,
        )
    )
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
          Shapefile.fromZipFile(Path("$resourcesDir/TwoShapefiles.zip")),
      )
    }
  }

  @Nested
  inner class PlotCounts {
    // Error, Student's t, variance, permanent plots, temporary plots
    @CsvSource(
        "defaults        ,100,1.645,40000, 8, 3",
        "no students t   ,100,     ,40000, 8, 3",
        "different values, 70,1.7  ,70000,32,10",
        ignoreLeadingAndTrailingWhitespace = true,
    )
    @ParameterizedTest(name = "{0}")
    fun `calculates number of monitoring plots based on statistical properties`(
        name: String,
        errorMargin: BigDecimal,
        studentsT: BigDecimal?,
        variance: BigDecimal,
        expectedPermanent: Int,
        expectedTemporary: Int,
    ) {
      val gen = ShapefileGenerator()
      val siteBoundary = gen.multiRectangle(0 to 0, 1000 to 1000)
      val subzoneFeature =
          gen.subzoneFeature(
              siteBoundary,
              errorMargin = errorMargin,
              studentsT = studentsT,
              variance = variance,
          )

      importer.import("site", null, organizationId, listOf(Shapefile(listOf(subzoneFeature))))

      val plantingZonesRow = plantingZonesDao.findAll().first()
      assertEquals(expectedPermanent, plantingZonesRow.numPermanentPlots, "Permanent plots")
      assertEquals(expectedTemporary, plantingZonesRow.numTemporaryPlots, "Temporary plots")
    }

    @Test
    fun `allows statistical properties to appear on any subzone`() {
      val gen = ShapefileGenerator()
      val subzoneFeatures =
          listOf(
              gen.subzoneFeature(
                  gen.multiRectangle(0 to 0, 100 to 100),
                  errorMargin = BigDecimal(70),
                  studentsT = null,
                  variance = null,
              ),
              gen.subzoneFeature(
                  gen.multiRectangle(100 to 0, 200 to 100),
                  errorMargin = null,
                  studentsT = BigDecimal(1.7),
                  variance = null,
              ),
              gen.subzoneFeature(
                  gen.multiRectangle(200 to 0, 300 to 100),
                  errorMargin = null,
                  studentsT = null,
                  variance = BigDecimal(70000),
              ),
          )

      importer.import("site", null, organizationId, listOf(Shapefile(subzoneFeatures)))

      val plantingZonesRow = plantingZonesDao.findAll().first()
      assertEquals(32, plantingZonesRow.numPermanentPlots, "Permanent plots")
      assertEquals(10, plantingZonesRow.numTemporaryPlots, "Temporary plots")
    }

    @Test
    fun `can override temporary and permanent plot counts`() {
      val gen = ShapefileGenerator()
      val subzoneFeatures =
          listOf(
              gen.subzoneFeature(
                  gen.multiRectangle(0 to 0, 100 to 100),
                  errorMargin = BigDecimal(70),
                  studentsT = BigDecimal(1.7),
                  variance = BigDecimal(70000),
                  permanentPlots = 15,
                  temporaryPlots = 4,
              )
          )

      importer.import("site", null, organizationId, listOf(Shapefile(subzoneFeatures)))

      val plantingZonesRow = plantingZonesDao.findAll().first()
      assertEquals(15, plantingZonesRow.numPermanentPlots, "Permanent plots")
      assertEquals(4, plantingZonesRow.numTemporaryPlots, "Temporary plots")
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
            Shapefile.fromZipFile(Path("$resourcesDir/NoShapefiles.zip")),
        )
      }
    }

    @ParameterizedTest
    @ValueSource(strings = ["error_marg", "variance"])
    fun `detects missing statistical properties`(property: String) {
      val gen = ShapefileGenerator()
      val siteBoundary =
          gen.multiRectangle(0 to 0, MONITORING_PLOT_SIZE_INT - 1 to MONITORING_PLOT_SIZE_INT)
      val subzoneFeature =
          when (property) {
            "error_marg" -> gen.subzoneFeature(siteBoundary, errorMargin = null)
            "variance" -> gen.subzoneFeature(siteBoundary, variance = null)
            else -> throw IllegalArgumentException("Test bug: unknown field $property")
          }

      assertHasProblem("Zone Z1 has no subzone with positive value for properties: $property") {
        importer.import(
            "name",
            "description",
            organizationId,
            listOf(Shapefile(listOf(subzoneFeature))),
        )
      }
    }

    @Test
    fun `can detect missing zone stable IDs`() {
      val gen = ShapefileGenerator()
      val subzoneFeature =
          gen.subzoneFeature(gen.multiRectangle(0 to 0, 500 to 500), subzoneStableId = "x")

      assertHasProblem("Subzone S1 is missing zone stable ID properties: stable_z, stable_zon") {
        importer.shapefilesToModel(
            listOf(Shapefile(listOf(subzoneFeature))),
            "test",
            "description",
            organizationId,
            requireStableIds = true,
        )
      }
    }

    @Test
    fun `can detect missing subzone stable IDs`() {
      val gen = ShapefileGenerator()
      val subzoneFeature =
          gen.subzoneFeature(gen.multiRectangle(0 to 0, 500 to 500), zoneStableId = "x")

      assertHasProblem(
          "Subzone S1 is missing subzone stable ID properties: stable_sz, stable_sub"
      ) {
        importer.shapefilesToModel(
            listOf(Shapefile(listOf(subzoneFeature))),
            "test",
            "description",
            organizationId,
            requireStableIds = true,
        )
      }
    }

    @Test
    fun `detects duplicate subzone stable IDs`() {
      val gen = ShapefileGenerator()
      val subzoneFeatures =
          listOf(
              gen.subzoneFeature(gen.multiRectangle(0 to 0, 500 to 250), subzoneStableId = "Z1-S1"),
              gen.subzoneFeature(
                  gen.multiRectangle(0 to 250, 500 to 500),
                  subzoneStableId = "Z1-S1",
              ),
          )

      assertHasProblem("Duplicate stable ID Z1-S1 on subzones: S1, S2") {
        importer.import(
            "Test Site",
            "description",
            organizationId,
            listOf(Shapefile(subzoneFeatures)),
        )
      }
    }

    @Test
    fun `detects inconsistent zone stable IDs`() {
      val gen = ShapefileGenerator()
      val subzoneFeatures =
          listOf(
              gen.subzoneFeature(
                  gen.multiRectangle(0 to 0, 500 to 250),
                  zone = "A",
                  zoneStableId = "A",
              ),
              gen.subzoneFeature(
                  gen.multiRectangle(0 to 250, 500 to 500),
                  zone = "A",
                  zoneStableId = "B",
              ),
          )

      assertHasProblem("Inconsistent stable IDs for zone A: A, B") {
        importer.import(
            "Test Site",
            "description",
            organizationId,
            listOf(Shapefile(subzoneFeatures)),
        )
      }
    }

    @Test
    fun `detects inconsistent zone names`() {
      val gen = ShapefileGenerator()
      val subzoneFeatures =
          listOf(
              gen.subzoneFeature(
                  gen.multiRectangle(0 to 0, 500 to 250),
                  zone = "A",
                  zoneStableId = "A",
              ),
              gen.subzoneFeature(
                  gen.multiRectangle(0 to 250, 500 to 500),
                  zone = "B",
                  zoneStableId = "A",
              ),
          )

      assertHasProblem("Inconsistent zone names for stable ID A: A, B") {
        importer.import(
            "Test Site",
            "description",
            organizationId,
            listOf(Shapefile(subzoneFeatures)),
        )
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
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))),
        )
        fail("Should have thrown exception for validation failure")
      } catch (e: PlantingSiteMapInvalidException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected),
              e.problems,
              "Did not find expected problem in problems list",
          )
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
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))),
        )
        fail("Should have thrown exception for validation failure")
      } catch (e: PlantingSiteMapInvalidException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected),
              e.problems,
              "Did not find expected problem in problems list",
          )
        }
      }

      // With grid origin 2 meters east, which avoids the notch
      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(subzoneFeature))),
            gridOrigin = gen.point(2 to 0),
        )
      } catch (e: PlantingSiteMapInvalidException) {
        // Display meaningful assertion message for a test failure
        assertEquals(
            emptyList<PlantingSiteValidationFailure>(),
            e.problems,
            "Import should not have any problems",
        )
      }
    }

    private fun assertHasProblem(expected: String, importFunc: () -> Unit) {
      try {
        importFunc()
        fail("Should have thrown exception for validation failure")
      } catch (e: ShapefilesInvalidException) {
        if (e.problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(
              listOf(expected),
              e.problems,
              "Did not find expected problem in problems list",
          )
        }
      }
    }
  }
}
