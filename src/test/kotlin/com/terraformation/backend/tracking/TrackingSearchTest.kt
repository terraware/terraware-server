package com.terraformation.backend.tracking

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.table.SearchTables
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Geometry

class TrackingSearchTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val searchService: SearchService by lazy { SearchService(dslContext) }
  private val searchTables: SearchTables by lazy { SearchTables(clock) }

  @BeforeEach
  fun setUp() {
    val organizationId = insertOrganization()
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
    val monitoringPlotGeometry5 = polygon(5, 6, 7, 8)
    val monitoringPlotGeometry6 = polygon(6, 7, 8, 9)
    val monitoringPlotGeometry7 = polygon(7, 8, 9, 10)
    val monitoringPlotGeometry8 = polygon(8, 9, 10, BigDecimal("11.0123456789"))
    val exteriorPlotGeometry9 = polygon(9, 10, 11, 12)
    val plantingSiteId =
        insertPlantingSite(
            boundary = plantingSiteGeometry,
            countryCode = "CI",
            exclusion = exclusionGeometry,
            projectId = projectId)
    val plantingSiteHistoryId = inserted.plantingSiteHistoryId

    val plantingSeasonId1 =
        insertPlantingSeason(
            startDate = LocalDate.of(1970, 1, 1),
            endDate = LocalDate.of(1970, 2, 15),
            isActive = true)
    val plantingSeasonId2 =
        insertPlantingSeason(
            startDate = LocalDate.of(1970, 3, 1), endDate = LocalDate.of(1970, 6, 5))

    val plantingZoneId2 = insertPlantingZone(boundary = plantingZoneGeometry, name = "Z2")

    val plantingSubzoneId3 = insertPlantingSubzone(boundary = plantingSubzoneGeometry3, name = "3")
    val monitoringPlotId5 = insertMonitoringPlot(boundary = monitoringPlotGeometry5)
    val monitoringPlotId6 = insertMonitoringPlot(boundary = monitoringPlotGeometry6)

    val plantingSubzoneId4 =
        insertPlantingSubzone(
            boundary = plantingSubzoneGeometry4,
            plantingCompletedTime = Instant.ofEpochSecond(1),
            name = "4")
    val monitoringPlotId7 = insertMonitoringPlot(boundary = monitoringPlotGeometry7)
    val monitoringPlotId8 =
        insertMonitoringPlot(boundary = monitoringPlotGeometry8, sizeMeters = 25)

    val exteriorPlotId9 =
        insertMonitoringPlot(boundary = exteriorPlotGeometry9, plantingSubzoneId = null)

    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()

    insertFacility(type = FacilityType.Nursery)

    insertWithdrawal()
    val deliveryId1 = insertDelivery(plantingSiteId = plantingSiteId)
    insertWithdrawal()

    val plantingId1 =
        insertPlanting(
            numPlants = 1, plantingSubzoneId = plantingSubzoneId3, speciesId = speciesId1)

    val plantingId3 =
        insertPlanting(
            numPlants = -2,
            plantingTypeId = PlantingType.ReassignmentFrom,
            plantingSubzoneId = plantingSubzoneId3,
            speciesId = speciesId1)
    val plantingId4 =
        insertPlanting(
            numPlants = 2,
            plantingTypeId = PlantingType.ReassignmentTo,
            plantingSubzoneId = plantingSubzoneId4,
            speciesId = speciesId1)
    val plantingId5 =
        insertPlanting(
            numPlants = 8, plantingSubzoneId = plantingSubzoneId4, speciesId = speciesId2)
    val deliveryId2 = insertDelivery(plantingSiteId = plantingSiteId)
    val plantingId2 =
        insertPlanting(
            numPlants = 4, plantingSubzoneId = plantingSubzoneId3, speciesId = speciesId1)

    // These population numbers are arbitrary; we just need them to be unique to test that the
    // searches are querying the right columns from the right tables.
    insertPlantingSitePopulation(plantingSiteId, speciesId1, 2, 1)
    insertPlantingSitePopulation(plantingSiteId, speciesId2, 4, 3)
    insertPlantingZonePopulation(plantingZoneId2, speciesId1, 6, 5)
    insertPlantingZonePopulation(plantingZoneId2, speciesId2, 8, 7)
    insertPlantingSubzonePopulation(plantingSubzoneId3, speciesId1, 10, 9)
    insertPlantingSubzonePopulation(plantingSubzoneId4, speciesId1, 12, 11)
    insertPlantingSubzonePopulation(plantingSubzoneId4, speciesId2, 14, 13)

    val observationId1 =
        insertObservation(
            completedTime = Instant.ofEpochSecond(2),
            createdTime = Instant.ofEpochSecond(1),
            endDate = LocalDate.of(2023, 1, 30),
            plantingSiteId = plantingSiteId,
            startDate = LocalDate.of(2023, 1, 1),
        )
    insertObservationPlot(
        ObservationPlotsRow(notes = "Plot notes"),
        claimedTime = Instant.ofEpochSecond(5),
        completedTime = Instant.ofEpochSecond(6),
        monitoringPlotId = monitoringPlotId5,
        isPermanent = true,
    )
    insertObservationPlot(monitoringPlotId = monitoringPlotId6)
    val observationId2 =
        insertObservation(
            plantingSiteId = plantingSiteId,
            startDate = LocalDate.of(2024, 2, 2),
            endDate = LocalDate.of(2024, 2, 28))
    insertObservationPlot(monitoringPlotId = monitoringPlotId5, isPermanent = true)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "boundary" to postgisRenderGeoJson(plantingSiteGeometry),
                    "country_code" to "CI",
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
                    "exteriorPlots" to
                        listOf(
                            mapOf("id" to "$exteriorPlotId9"),
                        ),
                    "id" to "$plantingSiteId",
                    "modifiedTime" to "1970-01-01T00:00:00Z",
                    "monitoringPlots" to
                        listOf(
                            mapOf("id" to "$monitoringPlotId5"),
                            mapOf("id" to "$monitoringPlotId6"),
                            mapOf("id" to "$monitoringPlotId7"),
                            mapOf("id" to "$monitoringPlotId8"),
                        ),
                    "name" to "Site 1",
                    "numPlantingZones" to "1",
                    "numPlantingSubzones" to "2",
                    "observations" to
                        listOf(
                            mapOf(
                                "completedTime" to "1970-01-01T00:00:02Z",
                                "createdTime" to "1970-01-01T00:00:01Z",
                                "endDate" to "2023-01-30",
                                "id" to "$observationId1",
                                "plantingSiteHistoryId" to "$plantingSiteHistoryId",
                                "startDate" to "2023-01-01",
                                "observationPlots" to
                                    listOf(
                                        mapOf(
                                            "claimedTime" to "1970-01-01T00:00:05Z",
                                            "completedTime" to "1970-01-01T00:00:06Z",
                                            "isPermanent" to "true",
                                            "monitoringPlot" to
                                                mapOf(
                                                    "id" to "$monitoringPlotId5",
                                                ),
                                            "notes" to "Plot notes",
                                        ),
                                        mapOf(
                                            "isPermanent" to "false",
                                            "monitoringPlot" to
                                                mapOf(
                                                    "id" to "$monitoringPlotId6",
                                                ),
                                        ),
                                    ),
                            ),
                            mapOf(
                                "createdTime" to "1970-01-01T00:00:00Z",
                                "endDate" to "2024-02-28",
                                "id" to "$observationId2",
                                "plantingSiteHistoryId" to "$plantingSiteHistoryId",
                                "startDate" to "2024-02-02",
                                "observationPlots" to
                                    listOf(
                                        mapOf(
                                            "isPermanent" to "true",
                                            "monitoringPlot" to
                                                mapOf(
                                                    "id" to "$monitoringPlotId5",
                                                ),
                                        ),
                                    ),
                            ),
                        ),
                    "plantingSeasons" to
                        listOf(
                            mapOf(
                                "endDate" to "1970-02-15",
                                "id" to "$plantingSeasonId1",
                                "isActive" to "true",
                                "startDate" to "1970-01-01",
                            ),
                            mapOf(
                                "endDate" to "1970-06-05",
                                "id" to "$plantingSeasonId2",
                                "isActive" to "false",
                                "startDate" to "1970-03-01",
                            ),
                        ),
                    "plantingZones" to
                        listOf(
                            mapOf(
                                "boundary" to postgisRenderGeoJson(plantingZoneGeometry),
                                "boundaryModifiedTime" to "1970-01-01T00:00:00Z",
                                "createdTime" to "1970-01-01T00:00:00Z",
                                "id" to "$plantingZoneId2",
                                "modifiedTime" to "1970-01-01T00:00:00Z",
                                "name" to "Z2",
                                "plantingSubzones" to
                                    listOf(
                                        mapOf(
                                            "boundary" to
                                                postgisRenderGeoJson(plantingSubzoneGeometry3),
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "fullName" to "Z1-3",
                                            "id" to "$plantingSubzoneId3",
                                            "modifiedTime" to "1970-01-01T00:00:00Z",
                                            "name" to "3",
                                            "totalPlants" to "10",
                                            "populations" to
                                                listOf(
                                                    mapOf(
                                                        "plantsSinceLastObservation" to "9",
                                                        "species_id" to "$speciesId1",
                                                        "totalPlants" to "10",
                                                    )),
                                            "monitoringPlots" to
                                                listOf(
                                                    mapOf(
                                                        "id" to "$monitoringPlotId5",
                                                        "northeastLatitude" to "8",
                                                        "northeastLongitude" to "7",
                                                        "northwestLatitude" to "8",
                                                        "northwestLongitude" to "5",
                                                        "plotNumber" to "1",
                                                        "sizeMeters" to "30",
                                                        "southeastLatitude" to "6",
                                                        "southeastLongitude" to "7",
                                                        "southwestLatitude" to "6",
                                                        "southwestLongitude" to "5",
                                                    ),
                                                    mapOf(
                                                        "id" to "$monitoringPlotId6",
                                                        "northeastLatitude" to "9",
                                                        "northeastLongitude" to "8",
                                                        "northwestLatitude" to "9",
                                                        "northwestLongitude" to "6",
                                                        "plotNumber" to "2",
                                                        "sizeMeters" to "30",
                                                        "southeastLatitude" to "7",
                                                        "southeastLongitude" to "8",
                                                        "southwestLatitude" to "7",
                                                        "southwestLongitude" to "6",
                                                    ))),
                                        mapOf(
                                            "boundary" to
                                                postgisRenderGeoJson(plantingSubzoneGeometry4),
                                            "createdTime" to "1970-01-01T00:00:00Z",
                                            "fullName" to "Z1-4",
                                            "id" to "$plantingSubzoneId4",
                                            "modifiedTime" to "1970-01-01T00:00:00Z",
                                            "name" to "4",
                                            "plantingCompletedTime" to "1970-01-01T00:00:01Z",
                                            "totalPlants" to "26",
                                            "populations" to
                                                listOf(
                                                    mapOf(
                                                        "plantsSinceLastObservation" to "11",
                                                        "species_id" to "$speciesId1",
                                                        "totalPlants" to "12",
                                                    ),
                                                    mapOf(
                                                        "plantsSinceLastObservation" to "13",
                                                        "species_id" to "$speciesId2",
                                                        "totalPlants" to "14",
                                                    )),
                                            "monitoringPlots" to
                                                listOf(
                                                    mapOf(
                                                        "id" to "$monitoringPlotId7",
                                                        "northeastLatitude" to "10",
                                                        "northeastLongitude" to "9",
                                                        "northwestLatitude" to "10",
                                                        "northwestLongitude" to "7",
                                                        "plotNumber" to "3",
                                                        "sizeMeters" to "30",
                                                        "southeastLatitude" to "8",
                                                        "southeastLongitude" to "9",
                                                        "southwestLatitude" to "8",
                                                        "southwestLongitude" to "7",
                                                    ),
                                                    mapOf(
                                                        "id" to "$monitoringPlotId8",
                                                        "northeastLatitude" to "11.01234568",
                                                        "northeastLongitude" to "10",
                                                        "northwestLatitude" to "11.01234568",
                                                        "northwestLongitude" to "8",
                                                        "plotNumber" to "4",
                                                        "sizeMeters" to "25",
                                                        "southeastLatitude" to "9",
                                                        "southeastLongitude" to "10",
                                                        "southwestLatitude" to "9",
                                                        "southwestLongitude" to "8",
                                                    )))),
                                "populations" to
                                    listOf(
                                        mapOf(
                                            "plantsSinceLastObservation" to "5",
                                            "species_id" to "$speciesId1",
                                            "totalPlants" to "6",
                                        ),
                                        mapOf(
                                            "plantsSinceLastObservation" to "7",
                                            "species_id" to "$speciesId2",
                                            "totalPlants" to "8",
                                        )))),
                    "populations" to
                        listOf(
                            mapOf(
                                "plantsSinceLastObservation" to "1",
                                "species_id" to "$speciesId1",
                                "totalPlants" to "2",
                            ),
                            mapOf(
                                "plantsSinceLastObservation" to "3",
                                "species_id" to "$speciesId2",
                                "totalPlants" to "4",
                            )),
                    "project" to
                        mapOf(
                            "createdTime" to "1970-01-01T00:00:00Z",
                            "description" to "Project 1 description",
                            "id" to "$projectId",
                            "modifiedTime" to "1970-01-01T00:00:00Z",
                            "name" to "Project 1"),
                    "totalPlants" to "6")))

    val prefix = SearchFieldPrefix(searchTables.plantingSites)
    val fields =
        listOf(
                "boundary",
                "country_code",
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
                "exteriorPlots.id",
                "id",
                "modifiedTime",
                "monitoringPlots.id",
                "name",
                "numPlantingZones",
                "numPlantingSubzones",
                "observations.completedTime",
                "observations.createdTime",
                "observations.endDate",
                "observations.id",
                "observations.observationPlots.claimedTime",
                "observations.observationPlots.completedTime",
                "observations.observationPlots.isPermanent",
                "observations.observationPlots.monitoringPlot.id",
                "observations.observationPlots.notes",
                "observations.plantingSiteHistoryId",
                "observations.startDate",
                "plantingSeasons.endDate",
                "plantingSeasons.id",
                "plantingSeasons.isActive",
                "plantingSeasons.startDate",
                "plantingZones.boundary",
                "plantingZones.boundaryModifiedTime",
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
                "plantingZones.plantingSubzones.monitoringPlots.northeastLatitude",
                "plantingZones.plantingSubzones.monitoringPlots.northeastLongitude",
                "plantingZones.plantingSubzones.monitoringPlots.northwestLatitude",
                "plantingZones.plantingSubzones.monitoringPlots.northwestLongitude",
                "plantingZones.plantingSubzones.monitoringPlots.plotNumber",
                "plantingZones.plantingSubzones.monitoringPlots.sizeMeters",
                "plantingZones.plantingSubzones.monitoringPlots.southeastLatitude",
                "plantingZones.plantingSubzones.monitoringPlots.southeastLongitude",
                "plantingZones.plantingSubzones.monitoringPlots.southwestLatitude",
                "plantingZones.plantingSubzones.monitoringPlots.southwestLongitude",
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
    insertOrganization()
    insertPlantingSite()
    insertPlantingZone()
    insertPlantingSubzone()

    val prefix = SearchFieldPrefix(root = searchTables.plantingSubzones)

    val expected = SearchResults(emptyList())
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
