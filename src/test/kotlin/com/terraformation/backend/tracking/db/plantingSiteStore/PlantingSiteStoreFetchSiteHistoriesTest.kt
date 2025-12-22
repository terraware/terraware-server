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
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
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

internal class PlantingSiteStoreFetchSiteHistoriesTest : DatabaseTest(), RunsAsDatabaseUser {
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
  fun `fetches site histories in descending order`() {
    val gridOrigin = point(1)
    val siteBoundary1 = multiPolygon(200)
    val siteBoundary2 = multiPolygon(250)
    val stratumBoundary1 = multiPolygon(150)
    val stratumBoundary2 = multiPolygon(200)
    val substratumBoundary1 = multiPolygon(100)
    val substratumBoundary2 = multiPolygon(90)
    val deletedSubstratumBoundary = multiPolygon(80)
    val monitoringPlotBoundary1 = polygon(30)
    val deletedMonitoringPlotBoundary = polygon(25)

    val plantingSiteId =
        insertPlantingSite(
            areaHa = BigDecimal(200),
            boundary = siteBoundary1,
            createdTime = Instant.ofEpochSecond(1000),
            gridOrigin = gridOrigin,
            name = "Site 1",
        )
    val plantingSiteHistoryId1 = inserted.plantingSiteHistoryId
    val stratumId1 =
        insertStratum(areaHa = BigDecimal(100), boundary = stratumBoundary1, name = "Stratum 1")
    val stratumHistoryId1 = inserted.stratumHistoryId
    val substratumId1 =
        insertSubstratum(
            areaHa = BigDecimal(70),
            boundary = substratumBoundary1,
            name = "Substratum 1",
        )
    val substratumHistoryId1 = inserted.substratumHistoryId
    val monitoringPlotId1 = insertMonitoringPlot(boundary = monitoringPlotBoundary1)
    val monitoringPlotHistoryId1 = inserted.monitoringPlotHistoryId

    // A second set of history records for the same site.
    val plantingSiteHistoryId2 =
        insertPlantingSiteHistory(
            areaHa = null,
            boundary = siteBoundary2,
            createdTime = Instant.ofEpochSecond(2000),
        )
    val stratumHistoryId2 =
        insertStratumHistory(areaHa = BigDecimal(150), boundary = stratumBoundary2)
    val substratumHistoryId2 =
        insertSubstratumHistory(
            areaHa = BigDecimal(120),
            boundary = substratumBoundary2,
            substratumId = substratumId1,
        )
    val monitoringPlotHistoryId2 =
        insertMonitoringPlotHistory(
            substratumId = substratumId1,
            monitoringPlotId = monitoringPlotId1,
        )

    // A substratum that was deleted after a monitoring plot was added to it, in the second set of
    // history records
    val deletedSubstratumId =
        insertSubstratum(
            areaHa = BigDecimal(180),
            boundary = deletedSubstratumBoundary,
            name = "Substratum 2",
        )
    val deletedSubstratumHistoryId = inserted.substratumHistoryId
    val deletedMonitoringPlotId = insertMonitoringPlot(boundary = deletedMonitoringPlotBoundary)
    val deletedMonitoringPlotHistoryId = inserted.monitoringPlotHistoryId
    substrataDao.deleteById(deletedSubstratumId)

    // A second site with its own history.
    insertPlantingSite(boundary = siteBoundary2, name = "Site 2")
    insertStratum(name = "Site 2 Stratum")
    insertSubstratum(name = "Site 2 Substratum")
    insertMonitoringPlot()

    val expected =
        listOf(
            PlantingSiteHistoryModel(
                areaHa = null,
                boundary = siteBoundary2,
                createdTime = Instant.ofEpochSecond(2000),
                gridOrigin = gridOrigin,
                id = plantingSiteHistoryId2,
                plantingSiteId = plantingSiteId,
                strata =
                    listOf(
                        StratumHistoryModel(
                            areaHa = BigDecimal(150),
                            boundary = stratumBoundary2,
                            id = stratumHistoryId2,
                            name = "Stratum 1",
                            stratumId = stratumId1,
                            stableId = StableId("Stratum 1"),
                            substrata =
                                listOf(
                                    SubstratumHistoryModel(
                                        areaHa = BigDecimal(120),
                                        boundary = substratumBoundary2,
                                        id = substratumHistoryId2,
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
                                                    id = monitoringPlotHistoryId2,
                                                    monitoringPlotId = monitoringPlotId1,
                                                    sizeMeters = MONITORING_PLOT_SIZE_INT,
                                                ),
                                            ),
                                    ),
                                    SubstratumHistoryModel(
                                        areaHa = BigDecimal(180),
                                        boundary = deletedSubstratumBoundary,
                                        id = deletedSubstratumHistoryId,
                                        fullName = "Stratum 1-Substratum 2",
                                        name = "Substratum 2",
                                        substratumId = null,
                                        stableId = StableId("Stratum 1-Substratum 2"),
                                        monitoringPlots =
                                            listOf(
                                                MonitoringPlotHistoryModel(
                                                    boundary = deletedMonitoringPlotBoundary,
                                                    createdBy = user.userId,
                                                    createdTime = Instant.EPOCH,
                                                    id = deletedMonitoringPlotHistoryId,
                                                    monitoringPlotId = deletedMonitoringPlotId,
                                                    sizeMeters = MONITORING_PLOT_SIZE_INT,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ),
            PlantingSiteHistoryModel(
                areaHa = BigDecimal(200),
                boundary = siteBoundary1,
                createdTime = Instant.ofEpochSecond(1000),
                gridOrigin = gridOrigin,
                id = plantingSiteHistoryId1,
                plantingSiteId = plantingSiteId,
                strata =
                    listOf(
                        StratumHistoryModel(
                            areaHa = BigDecimal(100),
                            boundary = stratumBoundary1,
                            id = stratumHistoryId1,
                            name = "Stratum 1",
                            stratumId = stratumId1,
                            stableId = StableId("Stratum 1"),
                            substrata =
                                listOf(
                                    SubstratumHistoryModel(
                                        areaHa = BigDecimal(70),
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
                                    )
                                ),
                        ),
                    ),
            ),
        )

    val actual = store.fetchSiteHistories(plantingSiteId, PlantingSiteDepth.Plot)

    val pair = expected.zip(actual)

    if (!pair.all { (a, b) -> a.equals(b, 0.00001) }) {
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `throws exception if site ID does not exist`() {
    assertThrows<PlantingSiteNotFoundException> {
      store.fetchSiteHistories(PlantingSiteId(-1), PlantingSiteDepth.Site)
    }
  }

  @Test
  fun `throws exception if no permission to read planting site`() {
    val plantingSiteId = insertPlantingSite(boundary = multiPolygon(1), name = "Site 1")

    deleteOrganizationUser(user.userId, inserted.organizationId)

    assertThrows<PlantingSiteNotFoundException> {
      store.fetchSiteHistories(plantingSiteId, PlantingSiteDepth.Site)
    }
  }
}
