package com.terraformation.backend.admin

import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.model.OrganizationModel
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.tracking.db.ObservationResultsStoreV2
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.T0Store
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.ExistingSubstratumModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumResultsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumResultsModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.springframework.ui.ExtendedModelMap

class SurvivalRatesPageModelTest {
  private val geometryFactory = GeometryFactory()
  private val polygon =
      geometryFactory.createPolygon(
          arrayOf(
              Coordinate(0.0, 0.0),
              Coordinate(0.0, 1.0),
              Coordinate(1.0, 1.0),
              Coordinate(1.0, 0.0),
              Coordinate(0.0, 0.0),
          )
      )
  private val boundary = geometryFactory.createMultiPolygon(arrayOf(polygon))
  private val organization =
      OrganizationModel(
          createdTime = Instant.EPOCH,
          id = OrganizationId(1),
          name = "Test Org",
          totalUsers = 1,
      )

  @Test
  fun `uses newest result for each entity and newest non-null result for site`() {
    val site =
        site(
            stratum(1, substratum(11, plot(111))),
            stratum(2, substratum(22, plot(222))),
            stratum(3, substratum(33, plot(333))),
        )
    val newest =
        result(
            observationId = 3,
            completedTime = Instant.parse("2025-03-03T00:00:00Z"),
            survivalRate = null,
            strata =
                listOf(
                    stratumResult(
                        id = 1,
                        survivalRate = null,
                        substrata =
                            listOf(
                                substratumResult(
                                    id = 11,
                                    survivalRate = null,
                                    plots = listOf(plotResult(111, null)),
                                )
                            ),
                    )
                ),
        )
    val older =
        result(
            observationId = 2,
            completedTime = Instant.parse("2025-02-02T00:00:00Z"),
            survivalRate = 72,
            strata =
                listOf(
                    stratumResult(
                        id = 1,
                        survivalRate = 71,
                        substrata =
                            listOf(
                                substratumResult(
                                    id = 11,
                                    survivalRate = 70,
                                    plots = listOf(plotResult(111, 69)),
                                )
                            ),
                    ),
                    stratumResult(
                        id = 2,
                        survivalRate = 62,
                        substrata =
                            listOf(
                                substratumResult(
                                    id = 22,
                                    survivalRate = 61,
                                    plots = listOf(plotResult(222, 60)),
                                )
                            ),
                    ),
                ),
        )

    val actual = SurvivalRatesPageModel.of(site, organization, listOf(newest, older))

    assertThat(actual.latestCompletedObservationId).isEqualTo(ObservationId(3))
    assertThat(actual.latestCompletedTime).isEqualTo("Mon, 3 Mar 2025 00:00:00 GMT")
    assertThat(actual.latestCompletedSurvivalRate).isNull()
    assertThat(actual.organizationId).isEqualTo(OrganizationId(1))
    assertThat(actual.organizationName).isEqualTo("Test Org")
    assertThat(actual.siteId).isEqualTo(PlantingSiteId(1))
    assertThat(actual.siteName).isEqualTo("Test Site")
    assertThat(actual.siteSurvivalRate).isEqualTo(72)
    assertThat(actual.siteSurvivalRateObservationId).isEqualTo(ObservationId(2))
    assertThat(actual.siteSurvivalRateObservationCompletedTime)
        .isEqualTo("Sun, 2 Feb 2025 00:00:00 GMT")
    assertThat(actual.survivalRateIncludesTempPlots).isFalse()

    assertThat(actual.strata.map { it.id to Pair(it.observationId, it.survivalRate) })
        .containsExactly(
            StratumId(1) to Pair(ObservationId(3), null),
            StratumId(2) to Pair(ObservationId(2), 62),
            StratumId(3) to Pair(null, null),
        )
    assertThat(actual.substrata.map { it.id to Pair(it.observationId, it.survivalRate) })
        .containsExactly(
            SubstratumId(11) to Pair(ObservationId(3), null),
            SubstratumId(22) to Pair(ObservationId(2), 61),
            SubstratumId(33) to Pair(null, null),
        )
    assertThat(actual.monitoringPlots.map { it.id to Pair(it.observationId, it.survivalRate) })
        .containsExactly(
            MonitoringPlotId(111) to Pair(ObservationId(3), null),
            MonitoringPlotId(222) to Pair(ObservationId(2), 60),
            MonitoringPlotId(333) to Pair(null, null),
        )

    with(actual.strata.first()) {
      assertThat(name).isEqualTo("Stratum 1")
      assertThat(observationCompletedTime).isEqualTo("Mon, 3 Mar 2025 00:00:00 GMT")
      assertThat(survivalRateStdDev).isEqualTo(5)
      assertThat(plantingDensity).isEqualTo(100)
      assertThat(plantingCompleted).isTrue()
      assertThat(totalPlants).isEqualTo(100)
    }
    with(actual.substrata.first()) {
      assertThat(stratumName).isEqualTo("Stratum 1")
      assertThat(name).isEqualTo("Substratum 11")
      assertThat(observationCompletedTime).isEqualTo("Mon, 3 Mar 2025 00:00:00 GMT")
      assertThat(survivalRateStdDev).isEqualTo(5)
      assertThat(plantingDensity).isEqualTo(100)
      assertThat(plantingCompleted).isTrue()
      assertThat(totalPlants).isEqualTo(100)
    }
    with(actual.monitoringPlots.first()) {
      assertThat(stratumName).isEqualTo("Stratum 1")
      assertThat(substratumName).isEqualTo("Substratum 11")
      assertThat(plotNumber).isEqualTo(111)
      assertThat(observationCompletedTime).isEqualTo("Mon, 3 Mar 2025 00:00:00 GMT")
      assertThat(isPermanent).isTrue()
      assertThat(status).isEqualTo(ObservationPlotStatus.Completed)
      assertThat(plantingDensity).isEqualTo(100)
      assertThat(totalPlants).isEqualTo(100)
    }
  }

  @Test
  fun `controller exposes independent missing observation and t0 states`() {
    val observationResultsStore = mockk<ObservationResultsStoreV2>()
    val observationStore = mockk<ObservationStore>()
    val organizationStore = mockk<OrganizationStore>()
    val plantingSiteStore = mockk<PlantingSiteStore>()
    val t0Store = mockk<T0Store>()
    val controller =
        AdminSurvivalRatesController(
            observationResultsStore,
            observationStore,
            organizationStore,
            plantingSiteStore,
            t0Store,
        )
    val plantingSiteId = PlantingSiteId(1)
    val site = site(stratum(1, substratum(11, plot(111))))
    every { plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot) } returns site
    every { observationStore.fetchObservationsByPlantingSite(plantingSiteId) } returns emptyList()
    every { organizationStore.fetchOneById(OrganizationId(1)) } returns organization
    every { t0Store.fetchAllT0SiteDataSet(plantingSiteId) } returns false
    every {
      observationResultsStore.fetchByPlantingSiteId(
          plantingSiteId = plantingSiteId,
          depth = ObservationResultsDepth.Plot,
          states = setOf(ObservationState.Completed),
      )
    } returns emptyList()
    val model = ExtendedModelMap()

    val viewName = controller.getSurvivalRates(plantingSiteId, model)

    assertThat(viewName).isEqualTo("/admin/survivalRates")
    assertThat(model["hasObservations"]).isEqualTo(false)
    assertThat(model["hasCompletedObservations"]).isEqualTo(false)
    assertThat(model["allT0DataSet"]).isEqualTo(false)
    assertThat(model["model"]).isEqualTo(SurvivalRatesPageModel.of(site, organization, emptyList()))
  }

  private fun site(vararg strata: ExistingStratumModel) =
      ExistingPlantingSiteModel(
          boundary = boundary,
          id = PlantingSiteId(1),
          name = "Test Site",
          organizationId = OrganizationId(1),
          strata = strata.toList(),
      )

  private fun stratum(id: Long, vararg substrata: ExistingSubstratumModel) =
      ExistingStratumModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          boundaryModifiedTime = Instant.EPOCH,
          id = StratumId(id),
          name = "Stratum $id",
          substrata = substrata.toList(),
          stableId = StableId("stratum-$id"),
      )

  private fun substratum(id: Long, vararg plots: MonitoringPlotModel) =
      ExistingSubstratumModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          fullName = "Stratum-Substratum $id",
          id = SubstratumId(id),
          monitoringPlots = plots.toList(),
          name = "Substratum $id",
          stableId = StableId("substratum-$id"),
      )

  private fun plot(id: Long) =
      MonitoringPlotModel(
          boundary = polygon,
          elevationMeters = null,
          id = MonitoringPlotId(id),
          isAdHoc = false,
          isAvailable = true,
          permanentIndex = 1,
          plotNumber = id,
          sizeMeters = 30,
      )

  private fun result(
      observationId: Long,
      completedTime: Instant,
      survivalRate: Int?,
      strata: List<ObservationStratumResultsModel>,
  ) =
      ObservationResultsModel(
          adHocPlot = null,
          anyPlotsCompleted = true,
          areaHa = BigDecimal.ONE,
          biomassDetails = null,
          completedTime = completedTime,
          estimatedPlants = null,
          isAdHoc = false,
          observationId = ObservationId(observationId),
          observationType = ObservationType.Monitoring,
          plantingCompleted = true,
          plantingDensity = 100,
          plantingDensityStdDev = 10,
          plantingSiteHistoryId = null,
          plantingSiteId = PlantingSiteId(1),
          species = emptyList(),
          startDate = LocalDate.of(2025, 1, 1),
          state = ObservationState.Completed,
          strata = strata,
          survivalRate = survivalRate,
          survivalRateIncludesTempPlots = false,
          survivalRateStdDev = 5,
          totalPlants = 100,
          totalSpecies = 10,
      )

  private fun stratumResult(
      id: Long,
      survivalRate: Int?,
      substrata: List<ObservationSubstratumResultsModel>,
  ) =
      ObservationStratumResultsModel(
          anyPlotsCompleted = true,
          areaHa = BigDecimal.ONE,
          completedTime = Instant.parse("2025-01-01T00:00:00Z"),
          estimatedPlants = null,
          name = "Stratum $id",
          plantingCompleted = true,
          plantingDensity = 100,
          plantingDensityStdDev = 10,
          species = emptyList(),
          stratumId = StratumId(id),
          substrata = substrata,
          survivalRate = survivalRate,
          survivalRateStdDev = 5,
          totalPlants = 100,
          totalSpecies = 10,
      )

  private fun substratumResult(
      id: Long,
      survivalRate: Int?,
      plots: List<ObservationMonitoringPlotResultsModel>,
  ) =
      ObservationSubstratumResultsModel(
          anyPlotsCompleted = true,
          areaHa = BigDecimal.ONE,
          completedTime = Instant.parse("2025-01-01T00:00:00Z"),
          estimatedPlants = null,
          monitoringPlots = plots,
          name = "Substratum $id",
          plantingCompleted = true,
          plantingDensity = 100,
          plantingDensityStdDev = 10,
          species = emptyList(),
          substratumId = SubstratumId(id),
          survivalRate = survivalRate,
          survivalRateIncludesTempPlots = false,
          survivalRateStdDev = 5,
          totalPlants = 100,
          totalSpecies = 10,
      )

  private fun plotResult(id: Long, survivalRate: Int?) =
      ObservationMonitoringPlotResultsModel(
          boundary = polygon,
          claimedByName = null,
          claimedByUserId = null,
          completedTime = Instant.parse("2025-01-01T00:00:00Z"),
          conditions = emptySet(),
          coordinates = emptyList(),
          elevationMeters = null,
          isAdHoc = false,
          isPermanent = true,
          monitoringPlotId = MonitoringPlotId(id),
          monitoringPlotNumber = id,
          notes = null,
          overlappedByPlotIds = emptySet(),
          overlapsWithPlotIds = emptySet(),
          media = emptyList(),
          plantingDensity = 100,
          plants = null,
          sizeMeters = 30,
          species = emptyList(),
          status = ObservationPlotStatus.Completed,
          survivalRate = survivalRate,
          totalPlants = 100,
          totalSpecies = 10,
      )
}
