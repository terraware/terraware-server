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
import com.terraformation.backend.tracking.model.StratumHistoryModel
import com.terraformation.backend.tracking.model.SubstratumHistoryModel
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
        eventPublisher,
        strataDao,
        substrataDao,
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
    val stratumBoundary1 = multiPolygon(150)
    val substratumBoundary1 = multiPolygon(100)
    val substratumBoundary2 = multiPolygon(90)
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
    val stratumId1 =
        insertStratum(areaHa = BigDecimal(75), boundary = stratumBoundary1, name = "Stratum 1")
    val stratumHistoryId1 = inserted.stratumHistoryId
    val substratumId1 =
        insertSubstratum(
            areaHa = BigDecimal(50),
            boundary = substratumBoundary1,
            name = "Substratum 1",
        )
    val substratumHistoryId1 = inserted.substratumHistoryId
    val monitoringPlotId1 = insertMonitoringPlot(boundary = monitoringPlotBoundary1)
    val monitoringPlotHistoryId1 = inserted.monitoringPlotHistoryId

    // A substratum that was deleted after a monitoring plot was added to it.
    val substratumId2 =
        insertSubstratum(
            areaHa = BigDecimal(25),
            boundary = substratumBoundary2,
            name = "Substratum 2",
        )
    val substratumHistoryId2 = inserted.substratumHistoryId
    val monitoringPlotId2 = insertMonitoringPlot(boundary = monitoringPlotBoundary2)
    val monitoringPlotHistoryId2 = inserted.monitoringPlotHistoryId
    substrataDao.deleteById(substratumId2)

    // A second set of history records for the same site.
    insertPlantingSiteHistory(boundary = siteBoundary2)
    insertStratumHistory(boundary = siteBoundary2)
    insertSubstratumHistory(boundary = siteBoundary2, substratumId = null)
    insertMonitoringPlotHistory(substratumId = null)

    // A second site with its own history.
    insertPlantingSite(boundary = siteBoundary2, name = "Site 2")
    insertStratum(name = "Site 2 Stratum")
    insertSubstratum(name = "Site 2 Substratum")
    insertMonitoringPlot()

    val expected =
        PlantingSiteHistoryModel(
            areaHa = BigDecimal(100),
            boundary = siteBoundary1,
            createdTime = Instant.ofEpochSecond(1000),
            gridOrigin = gridOrigin,
            id = plantingSiteHistoryId1,
            plantingSiteId = plantingSiteId,
            strata =
                listOf(
                    StratumHistoryModel(
                        areaHa = BigDecimal(75),
                        boundary = stratumBoundary1,
                        id = stratumHistoryId1,
                        name = "Stratum 1",
                        stratumId = stratumId1,
                        stableId = StableId("Stratum 1"),
                        substrata =
                            listOf(
                                SubstratumHistoryModel(
                                    areaHa = BigDecimal(50),
                                    boundary = substratumBoundary1,
                                    id = substratumHistoryId1,
                                    fullName = "Stratum 1-Substratum 1",
                                    name = "Substratum 1",
                                    substratumId = substratumId1,
                                    stableId = StableId("Stratum 1-Substratum 1"),
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
                                SubstratumHistoryModel(
                                    areaHa = BigDecimal(25),
                                    boundary = substratumBoundary2,
                                    id = substratumHistoryId2,
                                    fullName = "Stratum 1-Substratum 2",
                                    name = "Substratum 2",
                                    substratumId = null,
                                    stableId = StableId("Stratum 1-Substratum 2"),
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
            strata = emptyList(),
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
