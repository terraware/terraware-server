package com.terraformation.backend.daily

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.mapbox.MapboxService
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.net.URI
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel

class MonitoringPlotElevationSchedulerTest {
  val mapboxService: MapboxService = mockk()
  val plantingSiteStore: PlantingSiteStore = mockk()

  val scheduler: MonitoringPlotElevationScheduler by lazy {
    MonitoringPlotElevationScheduler(
        TerrawareServerConfig(
            webAppUrl = URI("https://terraware.io"),
            keycloak =
                TerrawareServerConfig.KeycloakConfig(
                    apiClientId = "test",
                    apiClientGroupName = "test",
                    apiClientUsernamePrefix = "test")),
        mapboxService,
        plantingSiteStore,
        SystemUser(mockk()))
  }

  @BeforeEach
  fun setup() {
    val boundary1 = polygon(11.0, 11.0, 13.0, 13.0)
    val boundary2 = polygon(21.0, 21.0, 23.0, 23.0)
    val boundary3 = polygon(31.0, 31.0, 33.0, 33.0)

    val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
    val point1 = geometryFactory.createPoint(boundary1.coordinates.first())
    val point2 = geometryFactory.createPoint(boundary2.coordinates.first())
    val point3 = geometryFactory.createPoint(boundary3.coordinates.first())

    every { plantingSiteStore.fetchMonitoringPlotsWithoutElevation(any()) } returns
        listOf(
            MonitoringPlotModel(
                boundary = boundary1,
                elevationMeters = null,
                id = MonitoringPlotId(1),
                isAdHoc = false,
                isAvailable = true,
                permanentCluster = null,
                plotNumber = 1L,
                sizeMeters = 30,
            ),
            MonitoringPlotModel(
                boundary = boundary2,
                elevationMeters = null,
                id = MonitoringPlotId(2),
                isAdHoc = false,
                isAvailable = true,
                permanentCluster = null,
                plotNumber = 2L,
                sizeMeters = 30,
            ),
            MonitoringPlotModel(
                boundary = boundary3,
                elevationMeters = null,
                id = MonitoringPlotId(3),
                isAdHoc = false,
                isAvailable = true,
                permanentCluster = null,
                plotNumber = 3L,
                sizeMeters = 30,
            ),
        )

    every { plantingSiteStore.updateMonitoringPlotElevation(any()) } returns 0

    every { mapboxService.getElevation(point1) } returns 1.5
    every { mapboxService.getElevation(point2) } returns 2.5
    every { mapboxService.getElevation(point3) } returns 3.5
  }

  @Test
  fun `fetches elevation data and updates for each plot`() {
    scheduler.updatePlotElevation()

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
