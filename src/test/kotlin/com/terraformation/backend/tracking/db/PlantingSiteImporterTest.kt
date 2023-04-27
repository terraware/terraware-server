package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
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
    get() = listOf(PLANTING_SITES, PLANTING_ZONES, PLANTING_SUBZONES)

  private val clock = TestClock()
  private val importer: PlantingSiteImporter by lazy {
    PlantingSiteImporter(clock, dslContext, plantingSitesDao, plantingZonesDao, plantingSubzonesDao)
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
                "-138.10623684196800 26.898306029805767, " +
                "-138.10913809760345 26.894328822253907, " +
                "-138.12211602527543 26.900166127286790, " +
                "-138.12125950590655 26.903044444960674, " +
                "-138.10623684196800 26.898306029805767)))")
    val pz1Geometry =
        parseWkt(
            "MULTIPOLYGON (((" +
                "-138.11206493103197 26.895645300650447, " +
                "-138.11048216034246 26.899645098812265, " +
                "-138.10623684196796 26.898306029805763, " +
                "-138.10911973022100 26.894354001755183, " +
                "-138.10936732324421 26.894431927617507, " +
                "-138.11206493103197 26.895645300650447)))")
    val plotA10Geometry =
        parseWkt(
            "MULTIPOLYGON (((" +
                "-138.11254185284253 26.895971507714233, " +
                "-138.11257937183947 26.895876692557675, " +
                "-138.11309381197373 26.896108083688080, " +
                "-138.11308074229996 26.896141112159366, " +
                "-138.11254185284253 26.895971507714233)))")

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

    val plantingSubzones = plantingSubzonesDao.findAll()
    assertEquals(216, plantingSubzones.size, "Number of planting subzones")
    assertEquals(
        PlantingSubzonesRow(
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
        plantingSubzones.firstOrNull { it.name == "A10" }?.copy(id = null),
        "Example planting subzone")
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
      // percentage to be reported as a problem.
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
