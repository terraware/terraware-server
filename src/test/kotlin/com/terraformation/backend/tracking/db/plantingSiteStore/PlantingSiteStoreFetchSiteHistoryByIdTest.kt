package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteHistoryNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.MonitoringPlotHistoryModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteHistoryModel
import com.terraformation.backend.tracking.model.PlantingSubzoneHistoryModel
import com.terraformation.backend.tracking.model.PlantingZoneHistoryModel
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreFetchSiteHistoryByIdTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  protected val clock = TestClock()
  protected val eventPublisher = TestEventPublisher()
  protected val store: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        eventPublisher,
        IdentifierGenerator(clock, dslContext),
        monitoringPlotsDao,
        ParentStore(dslContext),
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao,
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(user.userId, inserted.organizationId, Role.Contributor)
  }

  @Test
  fun `fetches site history`() {
    val gridOrigin = point(1)
    val siteBoundary1 = multiPolygon(200)
    val siteBoundary2 = multiPolygon(250)
    val zoneBoundary1 = multiPolygon(150)
    val subzoneBoundary1 = multiPolygon(100)
    val subzoneBoundary2 = multiPolygon(90)
    val monitoringPlotBoundary1 = polygon(30)
    val monitoringPlotBoundary2 = polygon(25)

    val plantingSiteId =
        insertPlantingSite(
            areaHa = BigDecimal(100),
            boundary = siteBoundary1,
            createdTime = Instant.ofEpochSecond(1000),
            gridOrigin = gridOrigin,
            name = "Site 1",
        )
    val plantingSiteHistoryId1 = inserted.plantingSiteHistoryId
    val plantingZoneId1 =
        insertPlantingZone(areaHa = BigDecimal(75), boundary = zoneBoundary1, name = "Zone 1")
    val plantingZoneHistoryId1 = inserted.plantingZoneHistoryId
    val plantingSubzoneId1 =
        insertPlantingSubzone(
            areaHa = BigDecimal(50),
            boundary = subzoneBoundary1,
            name = "Subzone 1",
        )
    val subzoneHistoryId1 = inserted.plantingSubzoneHistoryId
    val monitoringPlotId1 = insertMonitoringPlot(boundary = monitoringPlotBoundary1)
    val monitoringPlotHistoryId1 = inserted.monitoringPlotHistoryId

    // A subzone that was deleted after a monitoring plot was added to it.
    val subzoneId2 =
        insertPlantingSubzone(
            areaHa = BigDecimal(25),
            boundary = subzoneBoundary2,
            name = "Subzone 2",
        )
    val subzoneHistoryId2 = inserted.plantingSubzoneHistoryId
    val monitoringPlotId2 = insertMonitoringPlot(boundary = monitoringPlotBoundary2)
    val monitoringPlotHistoryId2 = inserted.monitoringPlotHistoryId
    plantingSubzonesDao.deleteById(subzoneId2)

    // A second set of history records for the same site.
    insertPlantingSiteHistory(boundary = siteBoundary2)
    insertPlantingZoneHistory(boundary = siteBoundary2)
    insertPlantingSubzoneHistory(boundary = siteBoundary2, plantingSubzoneId = null)
    insertMonitoringPlotHistory(plantingSubzoneId = null)

    // A second site with its own history.
    insertPlantingSite(boundary = siteBoundary2, name = "Site 2")
    insertPlantingZone(name = "Site 2 Zone")
    insertPlantingSubzone(name = "Site 2 Subzone")
    insertMonitoringPlot()

    val expected =
        PlantingSiteHistoryModel(
            areaHa = BigDecimal(100),
            boundary = siteBoundary1,
            createdTime = Instant.ofEpochSecond(1000),
            gridOrigin = gridOrigin,
            id = plantingSiteHistoryId1,
            plantingSiteId = plantingSiteId,
            plantingZones =
                listOf(
                    PlantingZoneHistoryModel(
                        areaHa = BigDecimal(75),
                        boundary = zoneBoundary1,
                        id = plantingZoneHistoryId1,
                        name = "Zone 1",
                        plantingZoneId = plantingZoneId1,
                        stableId = StableId("Zone 1"),
                        plantingSubzones =
                            listOf(
                                PlantingSubzoneHistoryModel(
                                    areaHa = BigDecimal(50),
                                    boundary = subzoneBoundary1,
                                    id = subzoneHistoryId1,
                                    fullName = "Zone 1-Subzone 1",
                                    name = "Subzone 1",
                                    plantingSubzoneId = plantingSubzoneId1,
                                    stableId = StableId("Zone 1-Subzone 1"),
                                    monitoringPlots =
                                        listOf(
                                            MonitoringPlotHistoryModel(
                                                boundary = monitoringPlotBoundary1,
                                                createdBy = user.userId,
                                                createdTime = Instant.EPOCH,
                                                id = monitoringPlotHistoryId1,
                                                monitoringPlotId = monitoringPlotId1,
                                                sizeMeters = MONITORING_PLOT_SIZE_INT,
                                            ),
                                        ),
                                ),
                                PlantingSubzoneHistoryModel(
                                    areaHa = BigDecimal(25),
                                    boundary = subzoneBoundary2,
                                    id = subzoneHistoryId2,
                                    fullName = "Zone 1-Subzone 2",
                                    name = "Subzone 2",
                                    plantingSubzoneId = null,
                                    stableId = StableId("Zone 1-Subzone 2"),
                                    monitoringPlots =
                                        listOf(
                                            MonitoringPlotHistoryModel(
                                                boundary = monitoringPlotBoundary2,
                                                createdBy = user.userId,
                                                createdTime = Instant.EPOCH,
                                                id = monitoringPlotHistoryId2,
                                                monitoringPlotId = monitoringPlotId2,
                                                sizeMeters = MONITORING_PLOT_SIZE_INT,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    val actual =
        store.fetchSiteHistoryById(plantingSiteId, plantingSiteHistoryId1, PlantingSiteDepth.Plot)

    if (!expected.equals(actual, 0.00001)) {
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `fetches history for simple planting site`() {
    val siteBoundary = multiPolygon(100)
    val plantingSiteId =
        insertPlantingSite(areaHa = BigDecimal(50), boundary = siteBoundary, name = "Site")
    val plantingSiteHistoryId = inserted.plantingSiteHistoryId

    val expected =
        PlantingSiteHistoryModel(
            areaHa = BigDecimal(50),
            boundary = siteBoundary,
            createdTime = Instant.EPOCH,
            gridOrigin = null,
            id = plantingSiteHistoryId,
            plantingSiteId = plantingSiteId,
            plantingZones = emptyList(),
        )

    val actual =
        store.fetchSiteHistoryById(plantingSiteId, plantingSiteHistoryId, PlantingSiteDepth.Plot)

    if (!expected.equals(actual, 0.00001)) {
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `throws exception if history ID does not exist`() {
    val plantingSiteId = insertPlantingSite(boundary = multiPolygon(1), name = "Site 1")
    assertThrows<PlantingSiteHistoryNotFoundException> {
      store.fetchSiteHistoryById(plantingSiteId, PlantingSiteHistoryId(-1), PlantingSiteDepth.Site)
    }
  }

  @Test
  fun `throws exception if history is for a different site`() {
    insertPlantingSite(boundary = multiPolygon(1))
    val historyId1 = inserted.plantingSiteHistoryId
    val plantingSiteId2 = insertPlantingSite()

    assertThrows<PlantingSiteHistoryNotFoundException> {
      store.fetchSiteHistoryById(plantingSiteId2, historyId1, PlantingSiteDepth.Site)
    }
  }

  @Test
  fun `throws exception if no permission to read planting site`() {
    val plantingSiteId = insertPlantingSite(boundary = multiPolygon(1), name = "Site 1")
    val historyId = inserted.plantingSiteHistoryId

    deleteOrganizationUser(user.userId, inserted.organizationId)

    assertThrows<PlantingSiteNotFoundException> {
      store.fetchSiteHistoryById(plantingSiteId, historyId, PlantingSiteDepth.Site)
    }
  }
}
