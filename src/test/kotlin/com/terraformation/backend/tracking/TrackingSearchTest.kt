package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.mockUser
import com.terraformation.backend.multiPolygon
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
import org.locationtech.jts.geom.Geometry

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
    val plotGeometry3 = multiPolygon(1.0)
    val plotGeometry4 = multiPolygon(1.0)
    val plantingSiteId = insertPlantingSite(boundary = plantingSiteGeometry)
    val plantingZoneId =
        insertPlantingZone(boundary = plantingZoneGeometry, id = 2, plantingSiteId = plantingSiteId)
    insertPlot(
        boundary = plotGeometry3,
        id = 3,
        plantingSiteId = plantingSiteId,
        plantingZoneId = plantingZoneId)
    insertPlot(
        boundary = plotGeometry4,
        id = 4,
        plantingSiteId = plantingSiteId,
        plantingZoneId = plantingZoneId)

    val speciesId1 = insertSpecies(1)
    val speciesId2 = insertSpecies(2)

    insertFacility(type = FacilityType.Nursery)

    val withdrawalId1 = insertWithdrawal()
    val withdrawalId2 = insertWithdrawal()
    val deliveryId1 = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId1)
    val deliveryId2 = insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId2)

    val plantingId1 =
        insertPlanting(deliveryId = deliveryId1, numPlants = 1, plotId = 3, speciesId = speciesId1)
    val plantingId2 =
        insertPlanting(deliveryId = deliveryId2, numPlants = 4, plotId = 3, speciesId = speciesId1)
    val plantingId3 =
        insertPlanting(
            deliveryId = deliveryId1,
            numPlants = -2,
            plantingTypeId = PlantingType.ReassignmentFrom,
            plotId = 3,
            speciesId = speciesId1)
    val plantingId4 =
        insertPlanting(
            deliveryId = deliveryId1,
            numPlants = 2,
            plantingTypeId = PlantingType.ReassignmentTo,
            plotId = 4,
            speciesId = speciesId1)
    val plantingId5 =
        insertPlanting(deliveryId = deliveryId1, numPlants = 8, plotId = 4, speciesId = speciesId2)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "boundary" to postgisRenderGeoJson(plantingSiteGeometry),
                    "createdTime" to "1970-01-01T00:00:00Z",
                    "deliveries" to
                        listOf(
                            mapOf(
                                "createdTime" to "1970-01-01T00:00:00Z",
                                "id" to "$deliveryId1",
                                "plantings" to
                                    listOf(
                                        mapOf(
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "id" to "$plantingId1",
                                            "numPlants" to "1",
                                            "plot_fullName" to "Z1-3",
                                            "species_scientificName" to "Species 1",
                                            "type" to "Delivery",
                                        ),
                                        mapOf(
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "id" to "$plantingId3",
                                            "numPlants" to "-2",
                                            "plot_fullName" to "Z1-3",
                                            "species_scientificName" to "Species 1",
                                            "type" to "Reassignment From",
                                        ),
                                        mapOf(
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "id" to "$plantingId4",
                                            "numPlants" to "2",
                                            "plot_fullName" to "Z1-4",
                                            "species_scientificName" to "Species 1",
                                            "type" to "Reassignment To",
                                        ),
                                        mapOf(
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "id" to "$plantingId5",
                                            "numPlants" to "8",
                                            "plot_fullName" to "Z1-4",
                                            "species_scientificName" to "Species 2",
                                            "type" to "Delivery",
                                        ),
                                    ),
                            ),
                            mapOf(
                                "createdTime" to "1970-01-01T00:00:00Z",
                                "id" to "$deliveryId2",
                                "plantings" to
                                    listOf(
                                        mapOf(
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "id" to "$plantingId2",
                                            "numPlants" to "4",
                                            "plot_fullName" to "Z1-3",
                                            "species_scientificName" to "Species 1",
                                            "type" to "Delivery",
                                        ),
                                    ),
                            ),
                        ),
                    "id" to "1",
                    "modifiedTime" to "1970-01-01T00:00:00Z",
                    "name" to "Site 1",
                    "numPlantingZones" to "1",
                    "numPlots" to "2",
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
                                            "boundary" to postgisRenderGeoJson(plotGeometry3),
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "fullName" to "Z1-3",
                                            "id" to "3",
                                            "modifiedTime" to "1970-01-01T00:00:00Z",
                                            "name" to "3",
                                            "populations" to
                                                listOf(
                                                    mapOf(
                                                        "species_id" to "1",
                                                        "totalPlants" to "3"))),
                                        mapOf(
                                            "boundary" to postgisRenderGeoJson(plotGeometry4),
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "fullName" to "Z1-4",
                                            "id" to "4",
                                            "modifiedTime" to "1970-01-01T00:00:00Z",
                                            "name" to "4",
                                            "populations" to
                                                listOf(
                                                    mapOf(
                                                        "species_id" to "1",
                                                        "totalPlants" to "2",
                                                    ),
                                                    mapOf(
                                                        "species_id" to "2",
                                                        "totalPlants" to "8")))))),
                    "populations" to
                        listOf(
                            mapOf(
                                "species_id" to "1",
                                "totalPlants" to "5",
                            ),
                            mapOf("species_id" to "2", "totalPlants" to "8")))),
            null)

    val prefix = SearchFieldPrefix(searchTables.plantingSites)
    val fields =
        listOf(
                "boundary",
                "createdTime",
                "deliveries.createdTime",
                "deliveries.id",
                "deliveries.plantings.createdTime",
                "deliveries.plantings.id",
                "deliveries.plantings.notes",
                "deliveries.plantings.numPlants",
                "deliveries.plantings.plot_fullName",
                "deliveries.plantings.species_scientificName",
                "deliveries.plantings.type",
                "deliveries.reassignedTime",
                "deliveries.withdrawal_facility_name",
                "id",
                "modifiedTime",
                "name",
                "numPlantingZones",
                "numPlots",
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
                "plantingZones.plots.populations.species_id",
                "plantingZones.plots.populations.totalPlants",
                "populations.species_id",
                "populations.totalPlants",
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
    insertPlot(plantingSiteId = plantingSiteId, plantingZoneId = plantingZoneId)

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
}
