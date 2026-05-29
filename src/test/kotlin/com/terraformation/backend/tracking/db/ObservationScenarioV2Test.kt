package com.terraformation.backend.tracking.db

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumResultsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumResultsModel
import com.terraformation.backend.tracking.model.ObservedPlotCoordinatesModel
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationScenarioV2Test : ObservationScenarioTest() {
  override val user = mockUser()

  @BeforeEach
  fun setUp() {
    every { user.canReadOrganization(organizationId) } returns true
  }

  private fun runV2Scenario(
      prefix: String,
      numObservations: Int,
      sizeMeters: Int,
      plantingSiteId: com.terraformation.backend.db.tracking.PlantingSiteId,
  ) {
    importFromCsvFiles(prefix, numObservations, sizeMeters)
    val allResults =
        resultsStoreV2
            .fetchByPlantingSiteId(plantingSiteId, ObservationResultsDepth.Plant)
            .sortedBy { it.observationId }
    assertResults(prefix, allResults)
  }

  @Nested
  inner class FetchByOrganizationId {
    @Test
    fun `results are in descending completed time order`() {
      val completedObservationId1 = insertObservation(completedTime = Instant.ofEpochSecond(1))
      val completedObservationId2 = insertObservation(completedTime = Instant.ofEpochSecond(2))
      val inProgressObservationId = insertObservation(state = ObservationState.InProgress)
      val upcomingObservationId = insertObservation(state = ObservationState.Upcoming)

      val results = resultsStoreV2.fetchByOrganizationId(organizationId)

      assertEquals(
          listOf(
              completedObservationId2,
              completedObservationId1,
              upcomingObservationId,
              inProgressObservationId,
          ),
          results.map { it.observationId },
          "Observation IDs",
      )
    }

    @Test
    fun `respects states`() {
      val completedObservationId1 = insertObservation(completedTime = Instant.ofEpochSecond(1))
      val completedObservationId2 = insertObservation(completedTime = Instant.ofEpochSecond(2))
      val inProgressObservationId = insertObservation(state = ObservationState.InProgress)
      insertObservation(state = ObservationState.Upcoming)

      val results =
          resultsStoreV2.fetchByOrganizationId(
              organizationId,
              states = setOf(ObservationState.Completed, ObservationState.InProgress),
          )

      assertEquals(
          listOf(
              completedObservationId2,
              completedObservationId1,
              inProgressObservationId,
          ),
          results.map { it.observationId },
          "Observation IDs",
      )
    }

    @Test
    fun `respects depth`() {
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertObservation(completedTime = Instant.ofEpochSecond(1))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val plantResults =
          resultsStoreV2.fetchByOrganizationId(
              organizationId,
              depth = ObservationResultsDepth.Plant,
          )
      val plotResults =
          resultsStoreV2.fetchByOrganizationId(organizationId, depth = ObservationResultsDepth.Plot)
      val substratumResults =
          resultsStoreV2.fetchByOrganizationId(
              organizationId,
              depth = ObservationResultsDepth.Substratum,
          )
      val stratumResults =
          resultsStoreV2.fetchByOrganizationId(
              organizationId,
              depth = ObservationResultsDepth.Stratum,
          )
      val siteResults =
          resultsStoreV2.fetchByOrganizationId(organizationId, depth = ObservationResultsDepth.Site)

      assertEquals(
          emptyList<com.terraformation.backend.tracking.model.RecordedPlantModel>(),
          plantResults[0].strata[0].substrata[0].monitoringPlots[0].plants,
          "Plant depth contains plants",
      )

      assertNull(
          plotResults[0].strata[0].substrata[0].monitoringPlots[0].plants,
          "Plot depth has plants = null",
      )

      assertEquals(
          emptyList<ObservationMonitoringPlotResultsModel>(),
          substratumResults[0].strata[0].substrata[0].monitoringPlots,
          "Substratum depth contains empty list of monitoring plots",
      )

      assertEquals(
          emptyList<ObservationSubstratumResultsModel>(),
          stratumResults[0].strata[0].substrata,
          "Stratum depth contains empty list of substrata",
      )

      assertEquals(
          emptyList<ObservationStratumResultsModel>(),
          siteResults[0].strata,
          "Site depth contains empty list of strata",
      )
    }

    @Test
    fun `throws exception if no permission to read organization`() {
      every { user.canReadOrganization(organizationId) } returns false

      assertThrows<OrganizationNotFoundException> {
        resultsStoreV2.fetchByOrganizationId(organizationId)
      }
    }
  }

  @Nested
  inner class FetchByPlantingSiteId {
    @Test
    fun `limit of 1 returns most recently completed observation`() {
      insertObservation(completedTime = Instant.ofEpochSecond(1))
      val mostRecentlyCompletedObservationId =
          insertObservation(completedTime = Instant.ofEpochSecond(3))
      insertObservation(completedTime = Instant.ofEpochSecond(2))

      val results = resultsStoreV2.fetchByPlantingSiteId(plantingSiteId, limit = 1)

      assertEquals(
          listOf(mostRecentlyCompletedObservationId),
          results.map { it.observationId },
          "Observation IDs",
      )
    }

    @Test
    fun `associates monitoring plots with the substrata they were in at the time of the observation`() {
      insertStratum()
      val substratumId1 = insertSubstratum()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.ofEpochSecond(1))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      insertPlantingSiteHistory()
      insertStratum()
      val substratumId2 = insertSubstratum()
      monitoringPlotsDao.update(
          monitoringPlotsDao.fetchOneById(plotId)!!.copy(substratumId = substratumId2)
      )
      insertMonitoringPlotHistory()

      insertObservation(completedTime = Instant.ofEpochSecond(2))
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val results = resultsStoreV2.fetchByPlantingSiteId(plantingSiteId)

      assertEquals(
          listOf(substratumId2),
          results[0].strata.flatMap { stratum ->
            stratum.substrata
                .filter { substratum ->
                  substratum.monitoringPlots.any { it.monitoringPlotId == plotId }
                }
                .map { it.substratumId }
          },
          "Substratum of monitoring plot in second observation",
      )
      assertEquals(
          listOf(substratumId1),
          results[1].strata.flatMap { stratum ->
            stratum.substrata
                .filter { substratum ->
                  substratum.monitoringPlots.any { it.monitoringPlotId == plotId }
                }
                .map { it.substratumId }
          },
          "Substratum of monitoring plot in first observation",
      )
    }

    @Test
    fun `includes monitoring plots in substrata that have subsequently been deleted`() {
      insertStratum()
      val substratumId = insertSubstratum()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      substrataDao.deleteById(substratumId)

      val results = resultsStoreV2.fetchByPlantingSiteId(plantingSiteId)

      assertNotEquals(
          emptyList<ObservationResultsModel>(),
          results,
          "Should have returned observation result",
      )
      assertEquals(
          listOf(plotId),
          results[0].strata.flatMap { stratum ->
            stratum.substrata.flatMap { substratum ->
              substratum.monitoringPlots.map { it.monitoringPlotId }
            }
          },
          "Monitoring plot IDs in observation",
      )
    }

    @Test
    fun `includes monitoring plots in strata that have subsequently been deleted`() {
      val stratumId = insertStratum()
      insertSubstratum()
      val plotId = insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      strataDao.deleteById(stratumId)

      val results = resultsStoreV2.fetchByPlantingSiteId(plantingSiteId)

      assertNotEquals(
          emptyList<ObservationResultsModel>(),
          results,
          "Should have returned observation result",
      )
      assertEquals(
          listOf(plotId),
          results[0].strata.flatMap { stratum ->
            stratum.substrata.flatMap { substratum ->
              substratum.monitoringPlots.map { it.monitoringPlotId }
            }
          },
          "Monitoring plot IDs in observation",
      )
    }

    @Test
    fun `returns observed coordinates in counterclockwise position order`() {
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val northwest = point(1)
      val northeast = point(2)
      val southwest = point(3)

      val id1 =
          insertObservedCoordinates(
              position = ObservationPlotPosition.NorthwestCorner,
              gpsCoordinates = northwest,
          )
      val id2 =
          insertObservedCoordinates(
              position = ObservationPlotPosition.SouthwestCorner,
              gpsCoordinates = southwest,
          )
      val id3 =
          insertObservedCoordinates(
              position = ObservationPlotPosition.NortheastCorner,
              gpsCoordinates = northeast,
          )

      val results = resultsStoreV2.fetchByPlantingSiteId(plantingSiteId)

      val actualCoordinates = results[0].strata[0].substrata[0].monitoringPlots[0].coordinates

      assertEquals(
          listOf(
              ObservedPlotCoordinatesModel(id2, southwest, ObservationPlotPosition.SouthwestCorner),
              ObservedPlotCoordinatesModel(id3, northeast, ObservationPlotPosition.NortheastCorner),
              ObservedPlotCoordinatesModel(id1, northwest, ObservationPlotPosition.NorthwestCorner),
          ),
          actualCoordinates,
      )
    }

    @Test
    fun `returns stratum and substratum names even for strata that have subsequently been deleted`() {
      insertObservation(completedTime = Instant.EPOCH)
      insertStratum(name = "Stratum 1")
      insertSubstratum(name = "Substratum 1")
      insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.AnimalDamage)
      val stratumId2 = insertStratum(name = "Stratum 2")
      insertSubstratum(name = "Substratum 2")
      insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.Pests)

      strataDao.deleteById(stratumId2)

      val results = resultsStoreV2.fetchByPlantingSiteId(plantingSiteId)

      val stratum1Result =
          results[0].strata.single { stratum ->
            stratum.substrata.any { substratum ->
              substratum.monitoringPlots.any { ObservableCondition.AnimalDamage in it.conditions }
            }
          }
      val stratum2Result =
          results[0].strata.single { stratum ->
            stratum.substrata.any { substratum ->
              substratum.monitoringPlots.any { ObservableCondition.Pests in it.conditions }
            }
          }

      assertEquals("Stratum 1", stratum1Result.name)
      assertEquals("Stratum 2", stratum2Result.name)
      assertNull(stratum2Result.stratumId, "ID of deleted stratum")
      assertEquals(
          listOf("Substratum 1"),
          stratum1Result.substrata.map { it.name },
          "Names of all substrata in stratum 1",
      )
      assertEquals(
          listOf("Substratum 2"),
          stratum2Result.substrata.map { it.name },
          "Names of all substrata in stratum 2",
      )
    }

    @Test
    fun `returns plot information`() {
      insertMonitoringPlot(isAdHoc = true)
      val adHocObservationId = insertObservation(completedTime = Instant.EPOCH, isAdHoc = true)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.AnimalDamage)
      insertObservationPlotCondition(condition = ObservableCondition.Fungus)
      insertObservationPlotCondition(condition = ObservableCondition.UnfavorableWeather)

      val results = resultsStoreV2.fetchByPlantingSiteId(plantingSiteId)
      assertNull(results.find { it.observationId == adHocObservationId }, "No ad-hoc observation")

      val observationResults = results.first()
      assertEquals(inserted.observationId, observationResults.observationId, "Observation ID")
      assertFalse(observationResults.isAdHoc, "Observation Is Ad Hoc")
      assertNull(observationResults.survivalRate, "Observation survival rate with no plants")

      val plotResults = observationResults.strata.first().substrata.first().monitoringPlots.first()
      assertEquals(inserted.monitoringPlotId, plotResults.monitoringPlotId, "Plot ID")
      assertFalse(plotResults.isAdHoc, "Plot Is Ad Hoc")
      assertEquals(2L, plotResults.monitoringPlotNumber, "Plot number")
      assertSetEquals(
          setOf(
              ObservableCondition.AnimalDamage,
              ObservableCondition.Fungus,
              ObservableCondition.UnfavorableWeather,
          ),
          plotResults.conditions,
          "Plot conditions",
      )
    }

    @Test
    fun `returns plot overlaps in both directions`() {
      insertStratum()
      insertSubstratum()
      insertObservation(completedTime = Instant.EPOCH)
      val oldPlotId1 = insertMonitoringPlot()
      val oldPlotId2 = insertMonitoringPlot()
      val currentPlotId = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      val newPlotId1 = insertMonitoringPlot()
      val newPlotId2 = insertMonitoringPlot()

      insertMonitoringPlotOverlap(monitoringPlotId = currentPlotId, overlapsPlotId = oldPlotId1)
      insertMonitoringPlotOverlap(monitoringPlotId = currentPlotId, overlapsPlotId = oldPlotId2)
      insertMonitoringPlotOverlap(monitoringPlotId = newPlotId1, overlapsPlotId = currentPlotId)
      insertMonitoringPlotOverlap(monitoringPlotId = newPlotId2, overlapsPlotId = currentPlotId)

      val results = resultsStoreV2.fetchByPlantingSiteId(plantingSiteId)
      val plotResults = results[0].strata[0].substrata[0].monitoringPlots[0]

      assertSetEquals(
          setOf(oldPlotId1, oldPlotId2),
          plotResults.overlapsWithPlotIds,
          "Overlaps with",
      )
      assertSetEquals(
          setOf(newPlotId1, newPlotId2),
          plotResults.overlappedByPlotIds,
          "Overlapped by",
      )
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        resultsStoreV2.fetchByPlantingSiteId(plantingSiteId)
      }
    }

    @Test
    fun `reads survival rate and planting density from results tables`() {
      val speciesId = insertSpecies()
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      val observationId = insertObservation()
      insertObservationRequestedSubstratum()
      insertObservationPlot(claimedBy = user.userId, isPermanent = true)
      insertPlotT0Density(
          plotDensity = BigDecimal.valueOf(10).toPlantsPerHectare(),
          speciesId = speciesId,
      )

      observationStore.completePlot(
          observationId,
          inserted.monitoringPlotId,
          emptySet(),
          null,
          Instant.EPOCH,
          listOf(
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId,
                  statusId = RecordedPlantStatus.Live,
              )
          ),
      )

      val results = resultsStoreV2.fetchOneById(observationId)
      val stratumResults = results.strata[0]
      val substratumResults = stratumResults.substrata[0]
      val plotResults = substratumResults.monitoringPlots[0]

      // 1 live / 10 T0 = 10%
      assertEquals(10, plotResults.survivalRate, "Plot survival rate")
      assertEquals(10, substratumResults.survivalRate, "Substratum survival rate")
      assertEquals(10, stratumResults.survivalRate, "Stratum survival rate")
      assertEquals(10, results.survivalRate, "Site survival rate")

      // 1 permanent live / 0.09 ha = 11 plants/ha (rounded from 11.11)
      assertEquals(11, plotResults.plantingDensity, "Plot planting density")
      assertEquals(11, substratumResults.plantingDensity, "Substratum planting density")
      assertEquals(11, stratumResults.plantingDensity, "Stratum planting density")
      assertEquals(11, results.plantingDensity, "Site planting density")
    }

    @Test
    fun `reads planting density std dev from results tables when multiple plots are present`() {
      val speciesId = insertSpecies()
      insertStratum()
      insertSubstratum()
      val plot1Id = insertMonitoringPlot()
      insertPlotT0Density(
          plotDensity = BigDecimal.valueOf(10).toPlantsPerHectare(),
          speciesId = speciesId,
      )
      val plot2Id = insertMonitoringPlot()
      insertPlotT0Density(
          plotDensity = BigDecimal.valueOf(10).toPlantsPerHectare(),
          speciesId = speciesId,
      )

      val observationId = insertObservation()
      insertObservationRequestedSubstratum()
      insertObservationPlot(
          claimedBy = user.userId,
          isPermanent = true,
          monitoringPlotId = plot1Id,
      )
      insertObservationPlot(
          claimedBy = user.userId,
          isPermanent = true,
          monitoringPlotId = plot2Id,
      )

      // Plot 1: 1 live plant → density = 1/0.09 ≈ 11
      observationStore.completePlot(
          observationId,
          plot1Id,
          emptySet(),
          null,
          Instant.EPOCH,
          listOf(
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  speciesId = speciesId,
                  statusId = RecordedPlantStatus.Live,
              )
          ),
      )

      // Plot 2: 3 live plants → density = 3/0.09 ≈ 33
      observationStore.completePlot(
          observationId,
          plot2Id,
          emptySet(),
          null,
          Instant.EPOCH,
          List(3) {
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            )
          },
      )

      val results = resultsStoreV2.fetchOneById(observationId)
      val substratumResults = results.strata[0].substrata[0]

      // avg(11, 33) = 22
      assertEquals(22, substratumResults.plantingDensity, "Substratum planting density")
      assertNotNull(substratumResults.plantingDensityStdDev, "Substratum planting density std dev")
      assertNotNull(results.strata[0].plantingDensityStdDev, "Stratum planting density std dev")
      assertNotNull(results.plantingDensityStdDev, "Site planting density std dev")

      // survival rate std dev is also populated for multi-plot observations
      assertNotNull(substratumResults.survivalRateStdDev, "Substratum survival rate std dev")
      assertNotNull(results.strata[0].survivalRateStdDev, "Stratum survival rate std dev")
      assertNotNull(results.survivalRateStdDev, "Site survival rate std dev")
    }
  }

  @Nested
  inner class FetchOneById {
    @Test
    fun `returns observation results by ID`() {
      insertStratum()
      insertSubstratum()
      val plotId = insertMonitoringPlot()
      val observationId = insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)

      val results = resultsStoreV2.fetchOneById(observationId)

      assertEquals(observationId, results.observationId, "Observation ID")
      assertEquals(
          listOf(plotId),
          results.strata
              .flatMap { it.substrata }
              .flatMap { it.monitoringPlots }
              .map { it.monitoringPlotId },
          "Monitoring plot IDs",
      )
    }

    @Test
    fun `throws exception if no permission to read observation`() {
      val observationId = insertObservation(completedTime = Instant.EPOCH)
      every { user.canReadObservation(observationId) } returns false

      assertThrows<ObservationNotFoundException> { resultsStoreV2.fetchOneById(observationId) }
    }
  }

  @Nested
  inner class IncompletePlots {
    @Test
    fun `planting density calculations only consider completed plots`() {
      insertSpecies()
      insertStratum()
      insertSubstratum()
      insertObservation()

      val completePlotId = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId)

      val incompletePlotId = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId)

      observationStore.completePlot(
          inserted.observationId,
          completePlotId,
          emptySet(),
          "Notes",
          Instant.EPOCH,
          listOf(
              RecordedPlantsRow(
                  certaintyId = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  observationId = inserted.observationId,
                  monitoringPlotId = completePlotId,
                  speciesId = inserted.speciesId,
                  statusId = RecordedPlantStatus.Live,
              )
          ),
      )

      observationStore.abandonObservation(inserted.observationId)

      val results = resultsStoreV2.fetchOneById(inserted.observationId)
      val stratumResults = results.strata[0]
      val substratumResults = stratumResults.substrata[0]
      val incompletePlotResults =
          substratumResults.monitoringPlots.first { it.monitoringPlotId == incompletePlotId }
      val completePlotResults =
          substratumResults.monitoringPlots.first { it.monitoringPlotId == completePlotId }

      assertEquals(ObservationState.Abandoned, results.state, "Observation state")
      assertEquals(11, results.plantingDensity, "Site Planting Density")
      assertNull(results.plantingDensityStdDev, "Site Planting Density Standard Deviation")
      assertEquals(11, stratumResults.plantingDensity, "Stratum Planting Density")
      assertNull(
          stratumResults.plantingDensityStdDev,
          "Stratum Planting Density Standard Deviation",
      )
      assertEquals(11, substratumResults.plantingDensity, "Substratum Planting Density")
      assertNull(
          substratumResults.plantingDensityStdDev,
          "Substratum Planting Density Standard Deviation",
      )

      assertEquals(11, completePlotResults.plantingDensity, "Completed Plot Planting Density")
      assertEquals(
          ObservationPlotStatus.Completed,
          completePlotResults.status,
          "Completed Plot Status",
      )
      assertNull(incompletePlotResults.plantingDensity, "Incomplete Plot Planting Density")
      assertNull(incompletePlotResults.totalPlants, "Incomplete Plot Total Plants")
      assertNull(incompletePlotResults.totalSpecies, "Incomplete Plot Total Species")
      assertEquals(
          ObservationPlotStatus.NotObserved,
          incompletePlotResults.status,
          "Incomplete Plot Status",
      )
    }

    @Test
    fun `plant counts and survival rate are null if there are no completed plots`() {
      insertSpecies()
      val observationId = insertObservation()
      val incompleteStratumId = insertStratum()
      insertSubstratum()
      val incompletePlotId = insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, isPermanent = true)
      insertPlotT0Density()

      val results = resultsStoreV2.fetchOneById(observationId)
      val incompleteStratumResults = results.strata.single { it.stratumId == incompleteStratumId }
      val incompleteSubstratumResults = incompleteStratumResults.substrata[0]
      val incompletePlotResults =
          incompleteSubstratumResults.monitoringPlots.first {
            it.monitoringPlotId == incompletePlotId
          }

      assertNull(results.totalPlants, "Site Total Plants")
      assertNull(results.totalSpecies, "Site Total Species")
      assertNull(results.plantingDensity, "Site Planting Density")
      assertNull(results.survivalRate, "Site Survival Rate")

      assertNull(incompleteStratumResults.totalPlants, "Incomplete Stratum Total Plants")
      assertNull(incompleteStratumResults.totalSpecies, "Incomplete Stratum Total Species")
      assertNull(incompleteStratumResults.plantingDensity, "Incomplete Stratum Planting Density")
      assertNull(incompleteStratumResults.survivalRate, "Incomplete Stratum Survival Rate")
      assertNull(incompleteSubstratumResults.totalPlants, "Incomplete Substratum Total Plants")
      assertNull(incompleteSubstratumResults.totalSpecies, "Incomplete Substratum Total Species")
      assertNull(
          incompleteSubstratumResults.plantingDensity,
          "Incomplete Substratum Planting Density",
      )
      assertNull(incompleteSubstratumResults.survivalRate, "Incomplete Substratum Survival Rate")
      assertNull(incompletePlotResults.totalPlants, "Incomplete Plot Total Plants")
      assertNull(incompletePlotResults.totalSpecies, "Incomplete Plot Total Species")
      assertNull(incompletePlotResults.plantingDensity, "Incomplete Plot Planting Density")
      assertEquals(emptyList<Any>(), incompletePlotResults.species, "Incomplete Plot Species")
    }
  }

  @Nested
  inner class Scenarios {
    @Test
    fun `site with two observations`() {
      runV2Scenario(
          "/tracking/observation/TwoObservations",
          numObservations = 2,
          sizeMeters = 30,
          plantingSiteId,
      )
    }

    @Test
    fun `partial observations of disjoint substratum lists`() {
      runV2Scenario(
          "/tracking/observation/DisjointSubstrata",
          numObservations = 3,
          sizeMeters = 30,
          plantingSiteId,
      )
    }

    @Test
    fun `permanent plots being added and removed`() {
      runV2Scenario(
          "/tracking/observation/PermanentPlotChanges",
          numObservations = 3,
          sizeMeters = 25,
          plantingSiteId,
      )
    }
  }
}
