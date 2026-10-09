package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreFetchMonitoringPlotNumbersTest : BasePlantingSiteStoreTest() {
  @Test
  fun `returns plot numbers for plots that exist and are in specified site`() {
    val plantingSiteId = insertPlantingSite()
    insertStratum()
    insertSubstratum()
    val monitoringPlotId1 = insertMonitoringPlot(plotNumber = 1)
    val monitoringPlotId2 = insertMonitoringPlot(plotNumber = 2)
    val monitoringPlotId3 =
        insertMonitoringPlot(plotNumber = 3, substratumId = null, isAdHoc = true)
    insertPlantingSite()
    insertStratum()
    insertSubstratum()
    val otherSiteMonitoringPlotId = insertMonitoringPlot(plotNumber = 4)

    assertEquals(
        mapOf(
            monitoringPlotId1 to 1L,
            monitoringPlotId2 to 2L,
            monitoringPlotId3 to 3L,
        ),
        store.fetchMonitoringPlotNumbers(
            plantingSiteId,
            listOf(
                monitoringPlotId1,
                monitoringPlotId2,
                monitoringPlotId3,
                otherSiteMonitoringPlotId,
                MonitoringPlotId(-1L),
            ),
        ),
    )
  }

  @Test
  fun `throws exception if no permission to read planting site`() {
    val plantingSiteId = insertPlantingSite()
    insertStratum()
    insertSubstratum()
    val monitoringPlotId = insertMonitoringPlot()

    every { user.canReadPlantingSite(any()) } returns false

    assertThrows<PlantingSiteNotFoundException> {
      store.fetchMonitoringPlotNumbers(plantingSiteId, listOf(monitoringPlotId))
    }
  }
}
