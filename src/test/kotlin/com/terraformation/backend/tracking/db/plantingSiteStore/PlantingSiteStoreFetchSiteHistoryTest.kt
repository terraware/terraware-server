package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteHistoryNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.tracking.model.MonitoringPlotHistoryModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteHistoryModel
import com.terraformation.backend.tracking.model.PlantingSubzoneHistoryModel
import com.terraformation.backend.tracking.model.PlantingZoneHistoryModel
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreFetchSiteHistoryTest : BasePlantingSiteStoreTest() {
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
        insertPlantingSite(boundary = siteBoundary1, gridOrigin = gridOrigin, name = "Site 1")
    val plantingSiteHistoryId1 = inserted.plantingSiteHistoryId
    val plantingZoneId1 = insertPlantingZone(boundary = zoneBoundary1, name = "Zone 1")
    val plantingZoneHistoryId1 = inserted.plantingZoneHistoryId
    val plantingSubzoneId1 = insertPlantingSubzone(boundary = subzoneBoundary1, name = "Subzone 1")
    val subzoneHistoryId1 = inserted.plantingSubzoneHistoryId
    val monitoringPlotId1 = insertMonitoringPlot(boundary = monitoringPlotBoundary1)
    val monitoringPlotHistoryId1 = inserted.monitoringPlotHistoryId

    // A subzone that was deleted after a monitoring plot was added to it.
    val subzoneId2 = insertPlantingSubzone(boundary = subzoneBoundary2, name = "Subzone 2")
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
            boundary = siteBoundary1,
            gridOrigin = gridOrigin,
            id = plantingSiteHistoryId1,
            plantingSiteId = plantingSiteId,
            plantingZones =
                listOf(
                    PlantingZoneHistoryModel(
                        boundary = zoneBoundary1,
                        id = plantingZoneHistoryId1,
                        name = "Zone 1",
                        plantingZoneId = plantingZoneId1,
                        stableId = StableId("Zone 1"),
                        plantingSubzones =
                            listOf(
                                PlantingSubzoneHistoryModel(
                                    boundary = subzoneBoundary1,
                                    id = subzoneHistoryId1,
                                    fullName = "Z1-Subzone 1",
                                    name = "Subzone 1",
                                    plantingSubzoneId = plantingSubzoneId1,
                                    stableId = StableId("Z1-Subzone 1"),
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
                                    boundary = subzoneBoundary2,
                                    id = subzoneHistoryId2,
                                    fullName = "Z1-Subzone 2",
                                    name = "Subzone 2",
                                    plantingSubzoneId = null,
                                    stableId = StableId("Z1-Subzone 2"),
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
                                        )),
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
  fun `throws exception if history ID does not exist`() {
    assertThrows<PlantingSiteHistoryNotFoundException> {
      store.fetchSiteHistoryById(
          PlantingSiteId(-1), PlantingSiteHistoryId(-1), PlantingSiteDepth.Site)
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

    every { user.canReadPlantingSite(any()) } returns false

    assertThrows<PlantingSiteNotFoundException> {
      store.fetchSiteHistoryById(plantingSiteId, historyId, PlantingSiteDepth.Site)
    }
  }
}
