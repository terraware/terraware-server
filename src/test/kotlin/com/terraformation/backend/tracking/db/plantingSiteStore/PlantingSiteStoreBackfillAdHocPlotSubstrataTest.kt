package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.rectangle
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreBackfillAdHocPlotSubstrataTest : BasePlantingSiteStoreTest() {
  @Test
  fun `sets current and historical substrata from each map version`() {
    // Map version 1: no strata.
    val plantingSiteId = insertPlantingSite(width = 6)
    val adHocPlotId = insertMonitoringPlot(isAdHoc = true, substratumId = null, x = 4)
    val historyId1 = inserted.monitoringPlotHistoryId

    // Map version 2: one substratum covering the plot.
    insertPlantingSiteHistory()
    insertStratum(width = 6)
    val substratumId = insertSubstratum(width = 6)
    val substratumHistoryId = inserted.substratumHistoryId
    val historyId2 = insertMonitoringPlotHistory(monitoringPlotId = adHocPlotId)

    store.backfillAdHocPlotSubstrata(plantingSiteId)

    assertEquals(substratumId, monitoringPlotsDao.fetchOneById(adHocPlotId)!!.substratumId)
    val histories =
        monitoringPlotHistoriesDao.fetchById(historyId1, historyId2).associateBy { it.id }
    assertNull(histories[historyId1]!!.substratumHistoryId, "Version 1 history")
    assertEquals(substratumId, histories[historyId2]!!.substratumId, "Version 2 substratum")
    assertEquals(
        substratumHistoryId,
        histories[historyId2]!!.substratumHistoryId,
        "Version 2 substratum history",
    )
  }

  @Test
  fun `uses historical substratum boundaries rather than current ones`() {
    val plantingSiteId = insertPlantingSite(width = 6)
    insertStratum(width = 6)
    val substratumId = insertSubstratum(width = 6)
    val oldSubstratumHistoryId = inserted.substratumHistoryId
    val adHocPlotId = insertMonitoringPlot(isAdHoc = true, x = 4)
    val historyId = inserted.monitoringPlotHistoryId

    // The substratum later shrinks so it no longer covers the plot.
    dslContext
        .update(SUBSTRATA)
        .set(SUBSTRATA.BOUNDARY, rectangle(3 * MONITORING_PLOT_SIZE, 2 * MONITORING_PLOT_SIZE))
        .where(SUBSTRATA.ID.eq(substratumId))
        .execute()

    store.backfillAdHocPlotSubstrata(plantingSiteId)

    assertNull(monitoringPlotsDao.fetchOneById(adHocPlotId)!!.substratumId, "Current")
    assertEquals(
        oldSubstratumHistoryId,
        monitoringPlotHistoriesDao.fetchOneById(historyId)!!.substratumHistoryId,
        "Historical",
    )
  }

  @Test
  fun `keeps substratum history for plot whose historical substratum was deleted`() {
    val plantingSiteId = insertPlantingSite(width = 6)
    insertStratum(width = 6)
    val substratumId = insertSubstratum(width = 6)
    val substratumHistoryId = inserted.substratumHistoryId
    val adHocPlotId = insertMonitoringPlot(isAdHoc = true, x = 4)
    val historyId = inserted.monitoringPlotHistoryId

    substrataDao.deleteById(substratumId)

    store.backfillAdHocPlotSubstrata(plantingSiteId)

    val history = monitoringPlotHistoriesDao.fetchOneById(historyId)!!
    assertEquals(substratumHistoryId, history.substratumHistoryId, "Substratum history")
    assertNull(history.substratumId, "Substratum")
    assertNull(monitoringPlotsDao.fetchOneById(adHocPlotId)!!.substratumId, "Current")
  }

  @Test
  fun `second run changes nothing`() {
    val plantingSiteId = insertPlantingSite()
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot(isAdHoc = true, substratumId = null)

    assertEquals(2, store.backfillAdHocPlotSubstrata(plantingSiteId), "First run")
    assertEquals(0, store.backfillAdHocPlotSubstrata(plantingSiteId), "Second run")
  }

  @Test
  fun `does not modify non-ad-hoc plots`() {
    val plantingSiteId = insertPlantingSite()
    insertStratum()
    insertSubstratum()
    val plotId = insertMonitoringPlot(substratumId = null)

    store.backfillAdHocPlotSubstrata(plantingSiteId)

    assertNull(monitoringPlotsDao.fetchOneById(plotId)!!.substratumId)
  }

  @Test
  fun `throws exception if no permission to update site`() {
    val plantingSiteId = insertPlantingSite()
    every { user.canUpdatePlantingSite(any()) } returns false

    assertThrows<AccessDeniedException> { store.backfillAdHocPlotSubstrata(plantingSiteId) }
  }
}
