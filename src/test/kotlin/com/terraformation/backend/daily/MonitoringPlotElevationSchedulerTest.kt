package com.terraformation.backend.daily

import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.dummyTerrawareServerConfig
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.mapbox.MapboxService
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MonitoringPlotElevationSchedulerTest {
  val mapboxService: MapboxService = mockk()
  val plantingSiteStore: PlantingSiteStore = mockk()

  val scheduler: MonitoringPlotElevationScheduler by lazy {
    MonitoringPlotElevationScheduler(
        dummyTerrawareServerConfig(), mapboxService, plantingSiteStore, SystemUser(mockk()))
  }

  @BeforeEach
  fun setup() {
    val boundary1 = polygon(11.0, 11.0, 13.0, 13.0)
    val boundary2 = polygon(21.0, 21.0, 23.0, 23.0)
    val boundary3 = polygon(31.0, 31.0, 33.0, 33.0)
    every { plantingSiteStore.fetchMonitoringPlotsWithoutElevation(any()) } returns
        listOf(
            MonitoringPlotModel(
                boundary = boundary1,
                elevationMeters = null,
                id = MonitoringPlotId(1),
                isAdHoc = false,
                isAvailable = true,
                permanentIndex = null,
                plotNumber = 1L,
                sizeMeters = 30,
            ),
            MonitoringPlotModel(
                boundary = boundary2,
                elevationMeters = null,
                id = MonitoringPlotId(2),
                isAdHoc = false,
                isAvailable = true,
                permanentIndex = null,
                plotNumber = 2L,
                sizeMeters = 30,
            ),
            MonitoringPlotModel(
                boundary = boundary3,
                elevationMeters = null,
                id = MonitoringPlotId(3),
                isAdHoc = false,
                isAvailable = true,
                permanentIndex = null,
                plotNumber = 3L,
                sizeMeters = 30,
            ),
        )

    every { plantingSiteStore.updateMonitoringPlotElevation(any()) } returns 0

    every { mapboxService.getElevation(boundary1.centroid) } returns 1.5
    every { mapboxService.getElevation(boundary2.centroid) } returns 2.5
    every { mapboxService.getElevation(boundary3.centroid) } returns 3.5
  }

  @Test
  fun `fetches elevation data and updates for each plot`() {
    scheduler.updatePlotElevation(10)

    verify {
      plantingSiteStore.updateMonitoringPlotElevation(
          mapOf(
              MonitoringPlotId(1) to BigDecimal(1.5),
              MonitoringPlotId(2) to BigDecimal(2.5),
              MonitoringPlotId(3) to BigDecimal(3.5),
          ))
    }
  }
}
