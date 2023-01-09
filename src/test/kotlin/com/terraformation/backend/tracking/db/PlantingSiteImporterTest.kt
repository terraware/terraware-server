package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlotsRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.PlantingSiteImporter.ValidationOption
import com.terraformation.backend.tracking.model.Shapefile
import io.mockk.every
import java.time.Instant
import kotlin.io.path.Path
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.WKTReader
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteImporterTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences: List<Table<out Record>>
    get() = listOf(PLANTING_SITES, PLANTING_ZONES, PLOTS)

  private val clock = TestClock()
  private val importer: PlantingSiteImporter by lazy {
    PlantingSiteImporter(clock, dslContext, plantingSitesDao, plantingZonesDao, plotsDao)
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
  fun `populates database tables`() {
    importer.import(
        "name",
        "description",
        organizationId,
        Shapefile.fromZipFile(Path("$resourcesDir/PlotsWithOverlap.zip")),
        emptySet())

    val siteGeometry =
        parseWkt(
            "MULTIPOLYGON (((" +
                "-15373915.960623115 3110772.166314043, " +
                "-15374238.926923115 3110275.7237140425, " +
                "-15375683.623223115 3111004.353214044, " +
                "-15375588.275923114 3111363.647114042, " +
                "-15373915.960623115 3110772.166314043)))")
    val pz1Geometry =
        parseWkt(
            "MULTIPOLYGON (((" +
                "-15374564.740530016 3110440.047112322, " +
                "-15374388.547302816 3110939.315384185, " +
                "-15373915.960623112 3110772.1663140426, " +
                "-15374236.882275457 3110278.866612232, " +
                "-15374264.444204723 3110288.5933006443," +
                " -15374564.740530016 3110440.047112322)))")
    val plotA10Geometry =
        parseWkt(
            "MULTIPOLYGON (((" +
                "-15374617.831223117 3110480.764714042, " +
                "-15374622.00781875 3110468.9297473463, " +
                "-15374679.275032539 3110497.8123445725, " +
                "-15374677.820123112 3110501.9350140453, " +
                "-15374617.831223117 3110480.764714042)))")

    assertEquals(
        listOf(
            PlantingSitesRow(
                boundary = siteGeometry,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                id = PlantingSiteId(1),
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                name = "name",
                organizationId = organizationId,
                description = "description",
            )),
        plantingSitesDao.findAll(),
        "Planting site")

    val zones = plantingZonesDao.findAll()
    assertEquals(4, zones.size, "Number of zones")
    assertEquals(setOf("PZ1", "PZ2", "PZ3", "PZ4"), zones.map { it.name }.toSet(), "Zone names")
    assertEquals(
        PlantingZonesRow(
            boundary = pz1Geometry,
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            id = null,
            modifiedBy = user.userId,
            modifiedTime = Instant.EPOCH,
            name = "PZ1",
            plantingSiteId = PlantingSiteId(1),
        ),
        zones.firstOrNull { it.name == "PZ1" }?.copy(id = null),
        "Example zone")

    val plots = plotsDao.findAll()
    assertEquals(216, plots.size, "Number of plots")
    assertEquals(
        PlotsRow(
            boundary = plotA10Geometry,
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            id = null,
            plantingSiteId = PlantingSiteId(1),
            plantingZoneId = zones.first { it.name == "PZ2" }.id,
            name = "A10",
            fullName = "PZ2-A10",
            modifiedBy = user.userId,
            modifiedTime = Instant.EPOCH,
        ),
        plots.firstOrNull { it.name == "A10" }?.copy(id = null),
        "Example plot")
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
          Shapefile.fromZipFile(Path("$resourcesDir/PlotsWithOverlap.zip")),
          emptySet())
    }
  }

  private fun parseWkt(wkt: String): Geometry {
    val geometry = WKTReader().read(wkt)
    geometry.srid = SRID.SPHERICAL_MERCATOR
    return geometry
  }

  @Nested
  inner class Validation {
    @Test
    fun `detects too few shapefiles`() {
      assertHasProblem(
          "TooFewShapefiles.zip",
          ValidationOption.PlotsHaveZones,
          "Expected 3 shapefiles (site, zones, plots) but found 2")
    }

    @Test
    fun `detects planting zones with non-compliant names`() {
      assertHasProblem(
          "PlotsWithOverlap.zip",
          ValidationOption.ZonesHaveSingleLetterNames,
          "Planting zone name PZ1 is not a single upper-case letter")
    }

    @Test
    fun `detects planting zones not contained in site`() {
      assertHasProblem(
          "PlotsInWrongZones.zip",
          ValidationOption.ZonesContainedInSite,
          "10.12% of planting zone PZ1 is not contained within planting site")
    }

    @Test
    fun `detects plots with non-compliant names`() {
      assertHasProblem(
          "PlotsWithOverlap.zip",
          ValidationOption.PlotsHaveNumericNames,
          "Plot A10 does not have a numeric name")
    }

    @Test
    fun `detects plots that are missing zone names`() {
      assertHasProblem(
          "PlotsMissingZoneName.zip",
          ValidationOption.PlotsHaveZones,
          "Plot A10 is missing Zone property")
    }

    @Test
    fun `detects plots not contained in planting zones`() {
      assertHasProblem(
          "PlotsInWrongZones.zip",
          ValidationOption.PlotsContainedInZone,
          "100.00% of plot A10 is not contained within zone PZ3")
    }

    @Test
    fun `detects overlapping plots`() {
      // This file has several overlapping plots, but only one of them exceeds the minimum
      // percentage
      // to be reported as a problem.
      assertProblems("PlotsWithOverlap.zip", setOf(ValidationOption.PlotsDoNotOverlap)) { problems
        ->
        assertEquals(listOf("0.53% of plot A4 overlaps with plot B4"), problems)
      }
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
