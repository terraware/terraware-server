package com.terraformation.backend.tracking.db

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.tracking.BiomassForestType
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.SoilType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.rectanglePolygon
import com.terraformation.backend.tracking.model.BiomassQuadratModel
import com.terraformation.backend.tracking.model.BiomassQuadratSpeciesModel
import com.terraformation.backend.tracking.model.BiomassSpeciesModel
import com.terraformation.backend.tracking.model.ExistingBiomassDetailsModel
import com.terraformation.backend.tracking.model.ExistingRecordedTreeModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationSiteStatsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumStatsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumStatsModel
import com.terraformation.backend.tracking.model.ObservedPlotCoordinatesModel
import com.terraformation.backend.tracking.model.RecordedPlantModel
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
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

    assertAll(
        { assertResults(prefix, allResults) },
        {
          assertSiteObservationStats(
              prefix,
              resultsStoreV2.fetchSiteObservationStats(plantingSiteId),
          )
        },
    )
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

      // Partially completed observation
      insertObservation()
      insertObservationPlot()
      insertMonitoringPlot()
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertSpecies()
      insertObservedPlotSpeciesTotals(totalLive = 1)
      insertObservedSubstratumSpeciesTotals(totalLive = 1)
      insertObservedStratumSpeciesTotals(totalLive = 1)
      insertObservedSiteSpeciesTotals(totalLive = 1)
      insertRecordedPlant(speciesId = inserted.speciesId)

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
          listOf(
              RecordedPlantModel(
                  certainty = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  id = inserted.recordedPlantId,
                  speciesId = inserted.speciesId,
                  speciesName = null,
                  status = RecordedPlantStatus.Live,
              )
          ),
          plantResults
              .single { it.observationId == inserted.observationId }
              .strata[0]
              .substrata[0]
              .monitoringPlots
              .single { it.monitoringPlotId == inserted.monitoringPlotId }
              .plants,
          "Plant depth contains plants",
      )

      val expectedPlotResults = plantResults.map { result ->
        result.copy(
            strata =
                result.strata.map { stratum ->
                  stratum.copy(
                      substrata =
                          stratum.substrata.map { substratum ->
                            substratum.copy(
                                monitoringPlots =
                                    substratum.monitoringPlots.map { plot ->
                                      plot.copy(plants = null)
                                    }
                            )
                          }
                  )
                }
        )
      }

      assertEquals(
          expectedPlotResults,
          plotResults,
          "Plot-level results should have nulled-out plot-level plants list",
      )

      val expectedSubstratumResults = expectedPlotResults.map { result ->
        result.copy(
            strata =
                result.strata.map { stratum ->
                  stratum.copy(
                      substrata =
                          stratum.substrata.map { substratum ->
                            substratum.copy(monitoringPlots = emptyList())
                          }
                  )
                }
        )
      }

      assertEquals(
          expectedSubstratumResults,
          substratumResults,
          "Substratum-level results should have empty plot lists",
      )

      val expectedStratumResults = expectedSubstratumResults.map { result ->
        result.copy(
            strata =
                result.strata.map { stratum ->
                  stratum.copy(substrata = emptyList())
                }
        )
      }

      assertEquals(
          expectedStratumResults,
          stratumResults,
          "Stratum-level results should have empty substratum lists",
      )

      val expectedSiteResults = expectedStratumResults.map { result ->
        result.copy(strata = emptyList())
      }

      assertEquals(
          expectedSiteResults,
          siteResults,
          "Site-level results should have empty stratum lists",
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
  inner class AdHocResults {
    @Test
    fun `fetches ad-hoc plot details at every depth`() {
      insertMonitoringPlot(isAdHoc = true)
      val observationId = insertObservation(completedTime = Instant.EPOCH, isAdHoc = true)
      insertObservationPlot(claimedBy = user.userId, completedBy = user.userId)
      insertObservationPlotCondition(condition = ObservableCondition.AnimalDamage)
      val speciesId = insertSpecies()
      insertObservedPlotSpeciesTotals(totalLive = 1)
      val plantId = insertRecordedPlant(speciesId = speciesId)

      val expected = expectedAdHocResults()
      val expectedPlot =
          expected.adHocPlot!!.copy(
              conditions = setOf(ObservableCondition.AnimalDamage),
              species =
                  listOf(
                      ObservationSpeciesResultsModel(
                          certainty = RecordedSpeciesCertainty.Known,
                          latestLive = 1,
                          permanentLive = 0,
                          speciesId = speciesId,
                          speciesName = null,
                          survivalRate = null,
                          t0Density = null,
                          totalDead = 0,
                          totalExisting = 0,
                          totalLive = 1,
                          totalPlants = 1,
                      )
                  ),
              totalPlants = 1,
              totalSpecies = 1,
          )
      val expectedPlants =
          listOf(
              RecordedPlantModel(
                  certainty = RecordedSpeciesCertainty.Known,
                  gpsCoordinates = point(1),
                  id = plantId,
                  speciesId = speciesId,
                  speciesName = null,
                  status = RecordedPlantStatus.Live,
              )
          )

      ObservationResultsDepth.entries.forEach { depth ->
        assertEquals(
            expected.copy(
                adHocPlot =
                    expectedPlot.copy(
                        plants =
                            if (depth == ObservationResultsDepth.Plant) expectedPlants else null
                    )
            ),
            resultsStoreV2.fetchOneById(observationId, depth),
            "$depth depth",
        )
      }
    }

    @Test
    fun `fetches only ad-hoc results for the requested site or organization`() {
      insertMonitoringPlot(isAdHoc = true)
      insertObservation(completedTime = Instant.EPOCH, isAdHoc = true)
      insertObservationPlot(completedBy = user.userId)
      val expected = expectedAdHocResults()
      insertObservation(completedTime = Instant.EPOCH) // non-ad-hoc observation

      insertPlantingSite()
      insertMonitoringPlot(isAdHoc = true)
      insertObservation(completedTime = Instant.ofEpochSecond(1), isAdHoc = true)
      insertObservationPlot(completedBy = user.userId)
      val expectedOtherSite =
          expectedAdHocResults(
              plotNumber = 2,
              areaHa = null,
              completedTime = Instant.ofEpochSecond(1),
          )

      insertOrganization()
      insertPlantingSite()
      insertMonitoringPlot(isAdHoc = true)
      insertObservation(completedTime = Instant.ofEpochSecond(2), isAdHoc = true)
      insertObservationPlot(completedBy = user.userId)

      assertEquals(
          listOf(expected),
          resultsStoreV2.fetchByPlantingSiteId(plantingSiteId, isAdHoc = true),
          "Site observations",
      )
      assertEquals(
          listOf(expectedOtherSite, expected),
          resultsStoreV2.fetchByOrganizationId(organizationId, isAdHoc = true),
          "Organization observations",
      )
    }

    @Test
    fun `fetches ad-hoc biomass observation results`() {
      val plotId = insertMonitoringPlot(isAdHoc = true)
      val observationId =
          insertObservation(
              completedTime = Instant.EPOCH,
              isAdHoc = true,
              observationType = ObservationType.BiomassMeasurements,
          )
      insertObservationPlot(completedBy = user.userId)
      insertObservationBiomassDetails(
          description = "Forest plot",
          forestType = BiomassForestType.Terrestrial,
          herbaceousCoverPercent = 25,
          smallTreesCountLow = 5,
          smallTreesCountHigh = 10,
          soilAssessment = "Moist soil",
          soilType = SoilType.Loam,
      )
      val speciesId = insertSpecies()
      insertObservationBiomassSpecies(speciesId = speciesId, isThreatened = true)
      insertObservationBiomassQuadratDetails(
          position = ObservationPlotPosition.NortheastCorner,
          description = "Dense ground cover",
      )
      insertObservationBiomassQuadratSpecies(
          position = ObservationPlotPosition.NortheastCorner,
          abundanceCount = 7,
      )
      val treeId =
          insertRecordedTree(
              diameterAtBreastHeightCm = BigDecimal(20),
              pointOfMeasurementM = BigDecimal("1.3"),
              heightM = BigDecimal(12),
          )

      val expected =
          expectedAdHocResults()
              .copy(
                  observationType = ObservationType.BiomassMeasurements,
                  biomassDetails =
                      ExistingBiomassDetailsModel(
                          description = "Forest plot",
                          forestType = BiomassForestType.Terrestrial,
                          herbaceousCoverPercent = 25,
                          observationId = observationId,
                          plotId = plotId,
                          smallTreeCountRange = 5 to 10,
                          soilAssessment = "Moist soil",
                          soilType = SoilType.Loam,
                          species =
                              setOf(
                                  BiomassSpeciesModel(
                                      speciesId = speciesId,
                                      isInvasive = false,
                                      isThreatened = true,
                                  )
                              ),
                          quadrats =
                              mapOf(
                                  ObservationPlotPosition.NortheastCorner to
                                      BiomassQuadratModel(
                                          description = "Dense ground cover",
                                          species =
                                              setOf(
                                                  BiomassQuadratSpeciesModel(
                                                      abundanceCount = 7,
                                                      speciesId = speciesId,
                                                  )
                                              ),
                                      ),
                                  ObservationPlotPosition.NorthwestCorner to
                                      BiomassQuadratModel(species = emptySet()),
                                  ObservationPlotPosition.SoutheastCorner to
                                      BiomassQuadratModel(species = emptySet()),
                                  ObservationPlotPosition.SouthwestCorner to
                                      BiomassQuadratModel(species = emptySet()),
                              ),
                          trees =
                              listOf(
                                  ExistingRecordedTreeModel(
                                      id = treeId,
                                      diameterAtBreastHeightCm = BigDecimal(20),
                                      gpsCoordinates = null,
                                      heightM = BigDecimal(12),
                                      isDead = false,
                                      pointOfMeasurementM = BigDecimal("1.3"),
                                      speciesId = speciesId,
                                      treeGrowthForm = TreeGrowthForm.Tree,
                                      treeNumber = 1,
                                      trunkNumber = 1,
                                  )
                              ),
                      ),
              )

      assertEquals(expected, resultsStoreV2.fetchOneById(observationId), "Observation by ID")
      assertEquals(
          listOf(expected),
          resultsStoreV2.fetchByPlantingSiteId(plantingSiteId, isAdHoc = true),
          "Site observations",
      )
      assertEquals(
          listOf(expected),
          resultsStoreV2.fetchByOrganizationId(organizationId, isAdHoc = true),
          "Organization observations",
      )
    }

    private fun expectedAdHocResults(
        plotNumber: Long = 1,
        areaHa: BigDecimal? = BigDecimal(2500),
        completedTime: Instant = Instant.EPOCH,
    ) =
        ObservationResultsModel(
            adHocPlot =
                ObservationMonitoringPlotResultsModel(
                    boundary = rectanglePolygon(30),
                    claimedByName = "First Last",
                    claimedByUserId = user.userId,
                    completedTime = Instant.EPOCH,
                    conditions = emptySet(),
                    coordinates = emptyList(),
                    elevationMeters = null,
                    isAdHoc = true,
                    isPermanent = false,
                    monitoringPlotId = inserted.monitoringPlotId,
                    monitoringPlotNumber = plotNumber,
                    notes = null,
                    overlappedByPlotIds = emptySet(),
                    overlapsWithPlotIds = emptySet(),
                    media = emptyList(),
                    plantingDensity = null,
                    plants = null,
                    sizeMeters = 30,
                    species = emptyList(),
                    status = ObservationPlotStatus.Completed,
                    survivalRate = null,
                    totalPlants = null,
                    totalSpecies = null,
                ),
            anyPlotsCompleted = false,
            areaHa = areaHa,
            biomassDetails = null,
            completedTime = completedTime,
            estimatedPlants = null,
            isAdHoc = true,
            observationId = inserted.observationId,
            observationType = ObservationType.Monitoring,
            observedDensity = null,
            plantingCompleted = false,
            plantingDensity = null,
            plantingDensityStdDev = null,
            plantingSiteHistoryId = inserted.plantingSiteHistoryId,
            plantingSiteId = inserted.plantingSiteId,
            species = emptyList(),
            startDate = LocalDate.of(2023, 1, 1),
            state = ObservationState.Completed,
            strata = emptyList(),
            survivalRate = null,
            survivalRateIncludesTempPlots = false,
            survivalRateStdDev = null,
            totalPlants = null,
            totalSpecies = null,
        )
  }

  @Nested
  inner class IncompletePlots {
    @Test
    fun `planting density calculations only consider completed plots`() {
      insertSpecies()
      insertStratum()
      insertSubstratum()
      insertObservation()
      insertObservationRequestedSubstratum()

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
  inner class FetchSiteObservationStats {
    @Test
    fun `only counts completed and abandoned observations of scheduled monitoring`() {
      importFromCsvFiles(
          "/tracking/observation/DisjointSubstrata",
          numObservations = 3,
          sizeMeters = 30,
      )

      val (_, observationId2, _) = inserted.observationIds
      val alpha1PlotId = plotIds["111"]!!

      // Newer than observation 3 and it completed a plot in Alpha-1, so Alpha-1 uses it.
      clock.instant = Instant.ofEpochSecond(10)
      val abandonedObservationId = insertObservation(state = ObservationState.InProgress)
      insertObservationRequestedSubstratum(substratumId = substratumIds["Alpha-1"]!!)
      insertObservationRequestedSubstratum(substratumId = substratumIds["Alpha-2"]!!)
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.EPOCH,
          isPermanent = true,
          monitoringPlotId = alpha1PlotId,
          monitoringPlotHistoryId = plotHistoryIds[alpha1PlotId]!!,
      )
      // Left unobserved, so the observation stays in progress and can be abandoned.
      val alpha2PlotId = plotIds["112"]!!
      insertObservationPlot(
          isPermanent = true,
          monitoringPlotId = alpha2PlotId,
          monitoringPlotHistoryId = plotHistoryIds[alpha2PlotId]!!,
      )
      observationStore.completePlot(
          abandonedObservationId,
          alpha1PlotId,
          emptySet(),
          "Notes",
          Instant.ofEpochSecond(10),
          emptyList(),
      )
      observationStore.abandonObservation(abandonedObservationId)

      // Newer still, but none of these are eligible.
      clock.instant = Instant.ofEpochSecond(20)
      insertObservation(state = ObservationState.InProgress)
      insertObservation(state = ObservationState.Upcoming)
      insertObservation(completedTime = Instant.ofEpochSecond(20), isAdHoc = true)

      val stats = resultsStoreV2.fetchSiteObservationStats(plantingSiteId)
      val substrataById = stats.strata.single().substrata.associateBy { it.substratumId }

      assertEquals(
          abandonedObservationId,
          substrataById[substratumIds["Alpha-1"]!!]?.observationId,
          "Alpha-1 uses the abandoned observation, which completed its plot",
      )
      assertEquals(
          observationId2,
          substrataById[substratumIds["Alpha-2"]!!]?.observationId,
          "Alpha-2 skips the abandoned observation, which left its plot unobserved",
      )
      assertEquals(
          abandonedObservationId,
          stats.strata.single().observationId,
          "Stratum uses the abandoned observation",
      )
      assertEquals(
          abandonedObservationId,
          stats.observationId,
          "Site uses the abandoned observation",
      )
    }

    @Test
    fun `includes areas that have never been observed`() {
      importSiteFromCsvFile("/tracking/observation/DisjointSubstrata", sizeMeters = 30)

      val stats = resultsStoreV2.fetchSiteObservationStats(plantingSiteId)

      assertEquals(
          ObservationSiteStatsModel(
              completedTime = null,
              observationId = null,
              plantingDensity = null,
              plantingSiteId = plantingSiteId,
              strata =
                  listOf(
                      ObservationStratumStatsModel(
                          completedTime = null,
                          observationId = null,
                          plantingDensity = null,
                          stratumId = stratumIds["Alpha"]!!,
                          substrata =
                              listOf("Alpha-1", "Alpha-2").map { name ->
                                ObservationSubstratumStatsModel(
                                    completedTime = null,
                                    observationId = null,
                                    plantingDensity = null,
                                    substratumId = substratumIds[name]!!,
                                    survivalRate = null,
                                    totalPlants = null,
                                    totalSpecies = null,
                                )
                              },
                          survivalRate = null,
                          totalPlants = null,
                          totalSpecies = null,
                      )
                  ),
              survivalRate = null,
              totalPlants = null,
              totalSpecies = null,
          ),
          stats,
      )
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        resultsStoreV2.fetchSiteObservationStats(plantingSiteId)
      }
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
