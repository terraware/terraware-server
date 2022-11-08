package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.mockUser
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon

class TrackingSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(PLANTING_SITES, PLANTING_ZONES, PLOTS)

  private val clock: Clock = mockk()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables: SearchTables by lazy { SearchTables(clock) }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { clock.zone } returns ZoneOffset.UTC

    insertUser()
    insertOrganization()
    insertOrganizationUser()

    every { user.organizationRoles } returns mapOf(organizationId to Role.CONTRIBUTOR)
  }

  @Test
  fun `can search for all fields`() {
    val plantingSiteGeometry = multiPolygon(3.0)
    val plantingZoneGeometry = multiPolygon(2.0)
    val plotGeometry = multiPolygon(1.0)
    val plantingSiteId = insertPlantingSite(boundary = plantingSiteGeometry)
    val plantingZoneId =
        insertPlantingZone(boundary = plantingZoneGeometry, id = 2, plantingSiteId = plantingSiteId)
    insertPlot(boundary = plotGeometry, id = 3, plantingZoneId = plantingZoneId)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "boundary" to postgisRenderGeoJson(plantingSiteGeometry),
                    "createdTime" to "1970-01-01T00:00:00Z",
                    "id" to "1",
                    "modifiedTime" to "1970-01-01T00:00:00Z",
                    "name" to "Site 1",
                    "plantingZones" to
                        listOf(
                            mapOf(
                                "boundary" to postgisRenderGeoJson(plantingZoneGeometry),
                                "createdTime" to "1970-01-01T00:00:00Z",
                                "id" to "2",
                                "modifiedTime" to "1970-01-01T00:00:00Z",
                                "name" to "Z2",
                                "plots" to
                                    listOf(
                                        mapOf(
                                            "boundary" to postgisRenderGeoJson(plotGeometry),
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "fullName" to "Z1-3",
                                            "id" to "3",
                                            "modifiedTime" to "1970-01-01T00:00:00Z",
                                            "name" to "3",
                                        )))))),
            null)

    val prefix = SearchFieldPrefix(searchTables.plantingSites)
    val fields =
        listOf(
                "boundary",
                "createdTime",
                "id",
                "modifiedTime",
                "name",
                "plantingZones.boundary",
                "plantingZones.createdTime",
                "plantingZones.id",
                "plantingZones.modifiedTime",
                "plantingZones.name",
                "plantingZones.plots.boundary",
                "plantingZones.plots.createdTime",
                "plantingZones.plots.fullName",
                "plantingZones.plots.id",
                "plantingZones.plots.modifiedTime",
                "plantingZones.plots.name",
            )
            .map { prefix.resolve(it) }

    val actual = searchService.search(prefix, fields, NoConditionNode())

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `search for plots excludes planting sites in other organizations`() {
    val otherOrganizationId = OrganizationId(2)

    insertOrganization(otherOrganizationId)
    val plantingSiteId = insertPlantingSite(organizationId = otherOrganizationId)
    val plantingZoneId = insertPlantingZone(plantingSiteId = plantingSiteId)
    insertPlot(plantingZoneId = plantingZoneId)

    val prefix = SearchFieldPrefix(root = searchTables.plots)

    val expected = SearchResults(emptyList(), null)
    val actual = searchService.search(prefix, listOf(prefix.resolve("id")), NoConditionNode())

    assertEquals(expected, actual)
  }

  /**
   * Returns PostGIS's GeoJSON rendering of a geometry. This can differ from the JTS rendering
   * (e.g., one might render a value as "1" and the other as "1.0") and we want to compare against
   * the PostGIS version since that's what the search code returns.
   */
  private fun postgisRenderGeoJson(geometry: Geometry): String {
    return dslContext
        .resultQuery(
            "SELECT ST_AsGeoJSON(?::geometry)",
            DSL.value("SRID=${geometry.srid};${geometry.toText()}"))
        .fetchOne()!!
        .get(0)!!
        .toString()
  }

  /** Creates a simple triangular MultiPolygon. */
  private fun multiPolygon(scale: Double): MultiPolygon {
    val geometryFactory = GeometryFactory()
    return geometryFactory
        .createMultiPolygon(
            arrayOf(
                geometryFactory.createPolygon(
                    arrayOf(
                        CoordinateXY(0.0, 0.0),
                        CoordinateXY(scale, 0.0),
                        CoordinateXY(scale, scale),
                        CoordinateXY(0.0, 0.0)))))
        .also { it.srid = SRID.SPHERICAL_MERCATOR }
  }
}
