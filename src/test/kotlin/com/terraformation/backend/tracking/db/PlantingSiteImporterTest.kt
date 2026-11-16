package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.references.STRATUM_PLOT_SELECTION_PARAMETERS
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.ShapefileGenerator
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailure
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.util.GeometrySimplifier
import io.mockk.every
import io.mockk.mockk
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

  private val eventPublisher = TestEventPublisher()
  private val mockGeometrySimplifier = mockk<GeometrySimplifier>()
  private val importer: PlantingSiteImporter by lazy {
    PlantingSiteImporter(
        PlantingSiteStore(
            clock,
            TestSingletons.countryDetector,
            dslContext,
            EntityLocker(dslContext),
            eventPublisher,
            mockGeometrySimplifier,
            IdentifierGenerator(clock, dslContext),
            monitoringPlotsDao,
            ParentStore(dslContext),
            plantingSitesDao,
            eventPublisher,
            strataDao,
            substrataDao,
        )
    )
  }

  private val resourcesDir = "src/test/resources/tracking"

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    every { mockGeometrySimplifier.simplify(any(), any()) } answers { firstArg() }

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
      val substratumFeature =
          gen.substratumFeature(
              siteBoundary,
              errorMargin = errorMargin,
              studentsT = studentsT,
              variance = variance,
          )

      importer.import("site", null, organizationId, listOf(Shapefile(listOf(substratumFeature))))

      val counts =
          dslContext
              .selectFrom(STRATUM_PLOT_SELECTION_PARAMETERS)
              .fetchMap(STRATUM_PLOT_SELECTION_PARAMETERS.OBSERVATION_TYPE_ID)
      assertEquals(
          expectedPermanent,
          counts.getValue(ObservationType.Monitoring).numPermanentPlots,
          "Permanent plots",
      )
      assertEquals(
          expectedTemporary,
          counts.getValue(ObservationType.Monitoring).numTemporaryPlots,
          "Temporary plots",
      )
      assertEquals(
          expectedPermanent + expectedTemporary,
          counts.getValue(ObservationType.BaselineSurvey).numTemporaryPlots,
          "Baseline temporary plots",
      )
    }

    @CsvSource("15,4,19", "5,,15", "15,,25", ",4,36", ",0,32", ",,42")
    @ParameterizedTest
    fun `baseline plots use total explicit counts or calculated defaults`(
        permanent: Int?,
        temporary: Int?,
        expected: Int,
    ) {
      val gen = ShapefileGenerator()
      val feature =
          gen.substratumFeature(
              gen.multiRectangle(0 to 0, 100 to 100),
              errorMargin = BigDecimal(70),
              studentsT = BigDecimal("1.7"),
              variance = BigDecimal(70000),
              permanentPlots = permanent,
              temporaryPlots = temporary,
          )

      val shapefiles = listOf(Shapefile(listOf(feature)))
      val site = importer.shapefilesToModel(shapefiles, "site", null, organizationId)
      importer.import("site", null, organizationId, shapefiles)

      assertEquals(
          expected,
          dslContext
              .fetchSingle(
                  STRATUM_PLOT_SELECTION_PARAMETERS,
                  STRATUM_PLOT_SELECTION_PARAMETERS.OBSERVATION_TYPE_ID.eq(
                      ObservationType.BaselineSurvey
                  ),
              )
              .numTemporaryPlots,
          "Persisted baseline temporary plots",
      )
      assertEquals(
          0,
          site.strata.single().baselinePlotCounts.numPermanentPlots,
          "Baseline permanent plots",
      )
      assertEquals(
          expected,
          site.strata.single().baselinePlotCounts.numTemporaryPlots,
          "Baseline temporary plots",
      )
    }

    @Test
    fun `allows statistical properties to appear on any substratum`() {
      val gen = ShapefileGenerator()
      val substratumFeatures =
          listOf(
              gen.substratumFeature(
                  gen.multiRectangle(0 to 0, 100 to 100),
                  errorMargin = BigDecimal(70),
                  studentsT = null,
                  variance = null,
              ),
              gen.substratumFeature(
                  gen.multiRectangle(100 to 0, 200 to 100),
                  errorMargin = null,
                  studentsT = BigDecimal(1.7),
                  variance = null,
              ),
              gen.substratumFeature(
                  gen.multiRectangle(200 to 0, 300 to 100),
                  errorMargin = null,
                  studentsT = null,
                  variance = BigDecimal(70000),
              ),
          )

      importer.import("site", null, organizationId, listOf(Shapefile(substratumFeatures)))

      val counts =
          dslContext.fetchSingle(
              STRATUM_PLOT_SELECTION_PARAMETERS,
              STRATUM_PLOT_SELECTION_PARAMETERS.OBSERVATION_TYPE_ID.eq(ObservationType.Monitoring),
          )
      assertEquals(32, counts.numPermanentPlots, "Permanent plots")
      assertEquals(10, counts.numTemporaryPlots, "Temporary plots")
    }

    @Test
    fun `can override temporary and permanent plot counts`() {
      val gen = ShapefileGenerator()
      val substratumFeatures =
          listOf(
              gen.substratumFeature(
                  gen.multiRectangle(0 to 0, 100 to 100),
                  errorMargin = BigDecimal(70),
                  studentsT = BigDecimal(1.7),
                  variance = BigDecimal(70000),
                  permanentPlots = 15,
                  temporaryPlots = 4,
              )
          )

      importer.import("site", null, organizationId, listOf(Shapefile(substratumFeatures)))

      val counts =
          dslContext
              .selectFrom(STRATUM_PLOT_SELECTION_PARAMETERS)
              .fetchMap(STRATUM_PLOT_SELECTION_PARAMETERS.OBSERVATION_TYPE_ID)
      val plantCounts = counts.getValue(ObservationType.Monitoring)
      val baselineCounts = counts.getValue(ObservationType.BaselineSurvey)
      val biomassCounts = counts.getValue(ObservationType.BiomassMeasurements)
      assertEquals(19, baselineCounts.numTemporaryPlots, "Baseline temporary plots")
      assertEquals(
          plantCounts.errorMargin,
          baselineCounts.errorMargin,
          "Baseline error margin",
      )
      assertEquals(plantCounts.studentsT, baselineCounts.studentsT, "Baseline Student's t")
      assertEquals(plantCounts.variance, baselineCounts.variance, "Baseline variance")
      assertEquals(15, biomassCounts.numPermanentPlots, "Biomass permanent plots")
      assertEquals(4, biomassCounts.numTemporaryPlots, "Biomass temporary plots")
      assertEquals(15, plantCounts.numPermanentPlots, "Plant permanent plots")
      assertEquals(4, plantCounts.numTemporaryPlots, "Plant temporary plots")
    }
  }

  @Nested
  inner class Validation {
    @Test
    fun `detects too few shapefiles`() {
      assertHasProblem("Expected substrata and optionally exclusions but found 0 shapefiles") {
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
      val substratumFeature =
          when (property) {
            "error_marg" -> gen.substratumFeature(siteBoundary, errorMargin = null)
            "variance" -> gen.substratumFeature(siteBoundary, variance = null)
            else -> throw IllegalArgumentException("Test bug: unknown field $property")
          }

      assertHasProblem(
          "Stratum S1 has no substratum with positive value for properties: $property"
      ) {
        importer.import(
            "name",
            "description",
            organizationId,
            listOf(Shapefile(listOf(substratumFeature))),
        )
      }
    }

    @Test
    fun `can detect missing stratum stable IDs`() {
      val gen = ShapefileGenerator()
      val substratumFeature =
          gen.substratumFeature(gen.multiRectangle(0 to 0, 500 to 500), substratumStableId = "x")

      assertHasProblem(
          "Substratum Sub1 is missing stratum stable ID properties: stable_z, stable_zon, stable_s, stable_str"
      ) {
        importer.shapefilesToModel(
            listOf(Shapefile(listOf(substratumFeature))),
            "test",
            "description",
            organizationId,
            requireStableIds = true,
        )
      }
    }

    @Test
    fun `can detect missing substratum stable IDs`() {
      val gen = ShapefileGenerator()
      val substratumFeature =
          gen.substratumFeature(gen.multiRectangle(0 to 0, 500 to 500), stratumStableId = "x")

      assertHasProblem(
          "Substratum Sub1 is missing substratum stable ID properties: stable_sz, stable_sub, stable_ss"
      ) {
        importer.shapefilesToModel(
            listOf(Shapefile(listOf(substratumFeature))),
            "test",
            "description",
            organizationId,
            requireStableIds = true,
        )
      }
    }

    @Test
    fun `detects duplicate substratum stable IDs`() {
      val gen = ShapefileGenerator()
      val substratumFeatures =
          listOf(
              gen.substratumFeature(
                  gen.multiRectangle(0 to 0, 500 to 250),
                  substratumStableId = "S1-Sub1",
              ),
              gen.substratumFeature(
                  gen.multiRectangle(0 to 250, 500 to 500),
                  substratumStableId = "S1-Sub1",
              ),
          )

      assertHasProblem("Duplicate stable ID S1-Sub1 on substrata: Sub1, Sub2") {
        importer.import(
            "Test Site",
            "description",
            organizationId,
            listOf(Shapefile(substratumFeatures)),
        )
      }
    }

    @Test
    fun `detects inconsistent stratum stable IDs`() {
      val gen = ShapefileGenerator()
      val substratumFeatures =
          listOf(
              gen.substratumFeature(
                  gen.multiRectangle(0 to 0, 500 to 250),
                  stratum = "A",
                  stratumStableId = "A",
              ),
              gen.substratumFeature(
                  gen.multiRectangle(0 to 250, 500 to 500),
                  stratum = "A",
                  stratumStableId = "B",
              ),
          )

      assertHasProblem("Inconsistent stable IDs for stratum A: A, B") {
        importer.import(
            "Test Site",
            "description",
            organizationId,
            listOf(Shapefile(substratumFeatures)),
        )
      }
    }

    @Test
    fun `detects inconsistent stratum names`() {
      val gen = ShapefileGenerator()
      val substratumFeatures =
          listOf(
              gen.substratumFeature(
                  gen.multiRectangle(0 to 0, 500 to 250),
                  stratum = "A",
                  stratumStableId = "A",
              ),
              gen.substratumFeature(
                  gen.multiRectangle(0 to 250, 500 to 500),
                  stratum = "B",
                  stratumStableId = "A",
              ),
          )

      assertHasProblem("Inconsistent stratum names for stable ID A: A, B") {
        importer.import(
            "Test Site",
            "description",
            organizationId,
            listOf(Shapefile(substratumFeatures)),
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
      val substratumFeature = gen.substratumFeature(siteBoundary)

      val expected = PlantingSiteValidationFailure.stratumTooSmall("S1")

      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(substratumFeature))),
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

      val substratumFeature = gen.substratumFeature(siteBoundary)

      val expected = PlantingSiteValidationFailure.stratumTooSmall("S1")

      // Without grid origin
      try {
        importer.import(
            name = "Test Site",
            organizationId = organizationId,
            shapefiles = listOf(Shapefile(listOf(substratumFeature))),
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
            shapefiles = listOf(Shapefile(listOf(substratumFeature))),
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
