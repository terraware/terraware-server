package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.mockUser
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Geometry

class TrackingSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(PLANTING_SITES, PLANTING_ZONES, PLANTING_SUBZONES)

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables: SearchTables by lazy { SearchTables(clock) }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    insertOrganizationUser()

    every { user.organizationRoles } returns mapOf(organizationId to Role.Contributor)
  }

  @Test
  fun `can search for all fields`() {
    val projectId = insertProject(name = "Project 1", description = "Project 1 description")
    val plantingSiteGeometry = multiPolygon(3.0)
    val exclusionGeometry = multiPolygon(0.5)
    val plantingZoneGeometry = multiPolygon(2.0)
    val plantingSubzoneGeometry3 = multiPolygon(1.0)
    val plantingSubzoneGeometry4 = multiPolygon(1.0)
    val monitoringPlotGeometry5 = polygon(0.1)
    val monitoringPlotGeometry6 = polygon(0.1)
    val monitoringPlotGeometry7 = polygon(0.1)
    val monitoringPlotGeometry8 = polygon(0.1)
    val plantingSiteId =
        insertPlantingSite(
            boundary = plantingSiteGeometry, exclusion = exclusionGeometry, projectId = projectId)

    insertPlantingSeason(
        id = 1,
        startDate = LocalDate.of(1970, 1, 1),
        endDate = LocalDate.of(1970, 2, 15),
        isActive = true)
    insertPlantingSeason(
        id = 2, startDate = LocalDate.of(1970, 3, 1), endDate = LocalDate.of(1970, 6, 5))

    insertPlantingZone(boundary = plantingZoneGeometry, id = 2)

    insertPlantingSubzone(boundary = plantingSubzoneGeometry3, id = 3)
    insertMonitoringPlot(boundary = monitoringPlotGeometry5, id = 5)
    insertMonitoringPlot(boundary = monitoringPlotGeometry6, id = 6)

    insertPlantingSubzone(
        boundary = plantingSubzoneGeometry4,
        plantingCompletedTime = Instant.ofEpochSecond(1),
        id = 4)
    insertMonitoringPlot(boundary = monitoringPlotGeometry7, id = 7)
    insertMonitoringPlot(boundary = monitoringPlotGeometry8, id = 8)

    val speciesId1 = insertSpecies(1)
    val speciesId2 = insertSpecies(2)

    insertFacility(type = FacilityType.Nursery)

    insertWithdrawal()
    val deliveryId1 = insertDelivery(plantingSiteId = plantingSiteId)
    insertWithdrawal()

    val plantingId1 = insertPlanting(numPlants = 1, plantingSubzoneId = 3, speciesId = speciesId1)

    val plantingId3 =
        insertPlanting(
            numPlants = -2,
            plantingTypeId = PlantingType.ReassignmentFrom,
            plantingSubzoneId = 3,
            speciesId = speciesId1)
    val plantingId4 =
        insertPlanting(
            numPlants = 2,
            plantingTypeId = PlantingType.ReassignmentTo,
            plantingSubzoneId = 4,
            speciesId = speciesId1)
    val plantingId5 = insertPlanting(numPlants = 8, plantingSubzoneId = 4, speciesId = speciesId2)
    val deliveryId2 = insertDelivery(plantingSiteId = plantingSiteId)
    val plantingId2 = insertPlanting(numPlants = 4, plantingSubzoneId = 3, speciesId = speciesId1)

    // These population numbers are arbitrary; we just need them to be unique to test that the
    // searches are querying the right columns from the right tables.
    insertPlantingSitePopulation(plantingSiteId, speciesId1, 2, 1)
    insertPlantingSitePopulation(plantingSiteId, speciesId2, 4, 3)
    insertPlantingZonePopulation(2, speciesId1, 6, 5)
    insertPlantingZonePopulation(2, speciesId2, 8, 7)
    insertPlantingSubzonePopulation(3, speciesId1, 10, 9)
    insertPlantingSubzonePopulation(4, speciesId1, 12, 11)
    insertPlantingSubzonePopulation(4, speciesId2, 14, 13)

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
                                            "plantingSubzone_fullName" to "Z1-3",
                                            "species_scientificName" to "Species 1",
                                            "type" to "Delivery",
                                        ),
                                        mapOf(
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "id" to "$plantingId3",
                                            "numPlants" to "-2",
                                            "plantingSubzone_fullName" to "Z1-3",
                                            "species_scientificName" to "Species 1",
                                            "type" to "Reassignment From",
                                        ),
                                        mapOf(
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "id" to "$plantingId4",
                                            "numPlants" to "2",
                                            "plantingSubzone_fullName" to "Z1-4",
                                            "species_scientificName" to "Species 1",
                                            "type" to "Reassignment To",
                                        ),
                                        mapOf(
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "id" to "$plantingId5",
                                            "numPlants" to "8",
                                            "plantingSubzone_fullName" to "Z1-4",
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
                                            "plantingSubzone_fullName" to "Z1-3",
                                            "species_scientificName" to "Species 1",
                                            "type" to "Delivery",
                                        ),
                                    ),
                            ),
                        ),
                    "exclusion" to postgisRenderGeoJson(exclusionGeometry),
                    "id" to "1",
                    "modifiedTime" to "1970-01-01T00:00:00Z",
                    "name" to "Site 1",
                    "numPlantingZones" to "1",
                    "numPlantingSubzones" to "2",
                    "plantingSeasons" to
                        listOf(
                            mapOf(
                                "endDate" to "1970-02-15",
                                "id" to "1",
                                "isActive" to "true",
                                "startDate" to "1970-01-01",
                            ),
                            mapOf(
                                "endDate" to "1970-06-05",
                                "id" to "2",
                                "isActive" to "false",
                                "startDate" to "1970-03-01",
                            ),
                        ),
                    "plantingZones" to
                        listOf(
                            mapOf(
                                "boundary" to postgisRenderGeoJson(plantingZoneGeometry),
                                "createdTime" to "1970-01-01T00:00:00Z",
                                "id" to "2",
                                "modifiedTime" to "1970-01-01T00:00:00Z",
                                "name" to "Z2",
                                "plantingSubzones" to
                                    listOf(
                                        mapOf(
                                            "boundary" to
                                                postgisRenderGeoJson(plantingSubzoneGeometry3),
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "fullName" to "Z1-3",
                                            "id" to "3",
                                            "modifiedTime" to "1970-01-01T00:00:00Z",
                                            "name" to "3",
                                            "totalPlants" to "10",
                                            "populations" to
                                                listOf(
                                                    mapOf(
                                                        "plantsSinceLastObservation" to "9",
                                                        "species_id" to "1",
                                                        "totalPlants" to "10",
                                                    )),
                                            "monitoringPlots" to
                                                listOf(mapOf("id" to "5"), mapOf("id" to "6"))),
                                        mapOf(
                                            "boundary" to
                                                postgisRenderGeoJson(plantingSubzoneGeometry4),
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "fullName" to "Z1-4",
                                            "id" to "4",
                                            "modifiedTime" to "1970-01-01T00:00:00Z",
                                            "name" to "4",
                                            "plantingCompletedTime" to "1970-01-01T00:00:01Z",
                                            "totalPlants" to "26",
                                            "populations" to
                                                listOf(
                                                    mapOf(
                                                        "plantsSinceLastObservation" to "11",
                                                        "species_id" to "1",
                                                        "totalPlants" to "12",
                                                    ),
                                                    mapOf(
                                                        "plantsSinceLastObservation" to "13",
                                                        "species_id" to "2",
                                                        "totalPlants" to "14",
                                                    )),
                                            "monitoringPlots" to
                                                listOf(mapOf("id" to "7"), mapOf("id" to "8")))),
                                "populations" to
                                    listOf(
                                        mapOf(
                                            "plantsSinceLastObservation" to "5",
                                            "species_id" to "1",
                                            "totalPlants" to "6",
                                        ),
                                        mapOf(
                                            "plantsSinceLastObservation" to "7",
                                            "species_id" to "2",
                                            "totalPlants" to "8",
                                        )))),
                    "populations" to
                        listOf(
                            mapOf(
                                "plantsSinceLastObservation" to "1",
                                "species_id" to "1",
                                "totalPlants" to "2",
                            ),
                            mapOf(
                                "plantsSinceLastObservation" to "3",
                                "species_id" to "2",
                                "totalPlants" to "4",
                            )),
                    "project" to
                        mapOf(
                            "createdTime" to "1970-01-01T00:00:00Z",
                            "description" to "Project 1 description",
                            "id" to "$projectId",
                            "modifiedTime" to "1970-01-01T00:00:00Z",
                            "name" to "Project 1"),
                    "totalPlants" to "6")),
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
                "deliveries.plantings.plantingSubzone_fullName",
                "deliveries.plantings.species_scientificName",
                "deliveries.plantings.type",
                "deliveries.reassignedTime",
                "deliveries.withdrawal_facility_name",
                "exclusion",
                "id",
                "modifiedTime",
                "name",
                "numPlantingZones",
                "numPlantingSubzones",
                "plantingSeasons.endDate",
                "plantingSeasons.id",
                "plantingSeasons.isActive",
                "plantingSeasons.startDate",
                "plantingZones.boundary",
                "plantingZones.createdTime",
                "plantingZones.id",
                "plantingZones.modifiedTime",
                "plantingZones.name",
                "plantingZones.plantingSubzones.boundary",
                "plantingZones.plantingSubzones.createdTime",
                "plantingZones.plantingSubzones.fullName",
                "plantingZones.plantingSubzones.id",
                "plantingZones.plantingSubzones.modifiedTime",
                "plantingZones.plantingSubzones.name",
                "plantingZones.plantingSubzones.plantingCompletedTime",
                "plantingZones.plantingSubzones.populations.plantsSinceLastObservation",
                "plantingZones.plantingSubzones.populations.species_id",
                "plantingZones.plantingSubzones.populations.totalPlants",
                "plantingZones.plantingSubzones.monitoringPlots.id",
                "plantingZones.plantingSubzones.totalPlants",
                "plantingZones.populations.plantsSinceLastObservation",
                "plantingZones.populations.species_id",
                "plantingZones.populations.totalPlants",
                "populations.plantsSinceLastObservation",
                "populations.species_id",
                "populations.totalPlants",
                "project.createdTime",
                "project.description",
                "project.id",
                "project.modifiedTime",
                "project.name",
                "totalPlants",
            )
            .map { prefix.resolve(it) }

    val actual = searchService.search(prefix, fields, NoConditionNode())

    assertJsonEquals(expected, actual)
  }

  @Test
  fun `search for plots excludes planting sites in other organizations`() {
    val otherOrganizationId = OrganizationId(2)

    insertOrganization(otherOrganizationId)
    insertPlantingSite(organizationId = otherOrganizationId)
    insertPlantingZone()
    insertPlantingSubzone()

    val prefix = SearchFieldPrefix(root = searchTables.plantingSubzones)

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
