package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.ShapefileGenerator
import com.terraformation.backend.tracking.db.PlantingSiteImporter.ValidationOption
import com.terraformation.backend.tracking.model.Shapefile
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
        monitoringPlotsDao,
        plantingSitesDao,
        plantingZonesDao,
        plantingSubzonesDao)
  }

  private val resourcesDir = "src/test/resources/tracking"

  @BeforeEach
  fun setUp() {
    every { user.canCreatePlantingSite(any()) } returns true

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
  inner class PlotCreation {
    private val gen = ShapefileGenerator()

    @Test
    fun `fills zone boundary with monitoring plots`() {
      val siteBoundary = gen.multiRectangle(0 to 0, 176 to 151)
      val siteFeature = gen.siteFeature(siteBoundary)
      val zoneFeature = gen.zoneFeature(siteBoundary)
      val subzoneFeature = gen.subzoneFeature(siteBoundary)

      importer.importShapefiles(
          name = "Test Site",
          organizationId = organizationId,
          siteFile = Shapefile("site", listOf(siteFeature)),
          zonesFile = Shapefile("zone", listOf(zoneFeature)),
          subzonesFile = Shapefile("subzone", listOf(subzoneFeature)),
          exclusionsFile = null,
      )

      assertPlotCounts(permanent = 36, temporary = 6)
    }

    @Test
    fun `honors exclusion area`() {
      val siteBoundary = gen.multiRectangle(0 to 0, 176 to 151)
      val exclusionBoundary = gen.multiRectangle(26 to 0, 30 to 99)
      val siteFeature = gen.siteFeature(siteBoundary)
      val zoneFeature = gen.zoneFeature(siteBoundary)
      val subzoneFeature = gen.subzoneFeature(siteBoundary)
      val exclusionFeature = gen.exclusionFeature(exclusionBoundary)

      importer.importShapefiles(
          name = "Test Site",
          organizationId = organizationId,
          siteFile = Shapefile("site", listOf(siteFeature)),
          zonesFile = Shapefile("zone", listOf(zoneFeature)),
          subzonesFile = Shapefile("subzone", listOf(subzoneFeature)),
          exclusionsFile = Shapefile("exclusion", listOf(exclusionFeature)),
      )

      assertPlotCounts(permanent = 28, temporary = 10)
    }

    private fun assertPlotCounts(permanent: Int, temporary: Int) {
      val plots = monitoringPlotsDao.findAll()

      assertEquals(
          permanent,
          plots.count { it.permanentCluster != null },
          "Number of plots in permanent clusters")
      assertEquals(
          temporary, plots.count { it.permanentCluster == null }, "Number of temporary-only plots")
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

    private fun assertProblems(
        zipFile: String,
        validationOptions: Set<ValidationOption>,
        func: (List<String>) -> Unit
    ) {
      val shapefiles = Shapefile.fromZipFile(Path("$resourcesDir/$zipFile"))

      try {
        importer.import("name", "description", organizationId, shapefiles, validationOptions)
        fail("Should have throw exception for validation failure")
      } catch (e: PlantingSiteUploadProblemsException) {
        func(e.problems)
      }
    }

    private fun assertHasProblem(
        zipFile: String,
        validationOption: ValidationOption,
        expected: String
    ) {
      assertProblems(zipFile, setOf(validationOption)) { problems ->
        if (problems.none { it == expected }) {
          // Assertion failure message will include the list of problems we actually got back.
          assertEquals(listOf(expected), problems, "Did not find expected problem in problems list")
        }
      }
    }
  }
}
