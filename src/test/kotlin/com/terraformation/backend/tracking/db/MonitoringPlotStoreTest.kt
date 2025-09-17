package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MonitoringPlotStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: MonitoringPlotStore by lazy { MonitoringPlotStore(dslContext) }
  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    plantingSiteId = insertPlantingSite()
    insertPlantingZone()
    insertPlantingSubzone()
  }

  @Nested
  inner class GetOrganizationIdsFromPlots {
    @Test
    fun `returns organization IDs from plots`() {
      val plot1 = insertMonitoringPlot()
      val plot2 = insertMonitoringPlot()
      val org2 = insertOrganization()
      insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      val plot3 = insertMonitoringPlot()

      assertEquals(
          setOf(organizationId, org2),
          store.getOrganizationIdsFromPlots(setOf(plot1, plot2, plot3)),
      )
    }
  }

  @Nested
  inner class GetPlantingSiteIdsFromPlots {
    @Test
    fun `returns organization IDs from plots`() {
      val plot1 = insertMonitoringPlot()
      val plot2 = insertMonitoringPlot()
      val site2 = insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      val plot3 = insertMonitoringPlot()

      assertEquals(
          setOf(plantingSiteId, site2),
          store.getPlantingSiteIdsFromPlots(setOf(plot1, plot2, plot3)),
      )
    }
  }
}
