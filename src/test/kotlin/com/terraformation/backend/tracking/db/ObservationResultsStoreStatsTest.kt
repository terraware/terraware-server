package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumHistoryId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.model.ObservationSiteStatsModel
import com.terraformation.backend.tracking.model.ObservationStratumStatsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumStatsModel
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationResultsStoreStatsTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val store: ObservationResultsStoreV2 by lazy { ObservationResultsStoreV2(dslContext) }

  /** One stratum with a single substratum and a single monitoring plot in it. */
  private data class TestStratum(
      val monitoringPlotHistoryId: MonitoringPlotHistoryId,
      val monitoringPlotId: MonitoringPlotId,
      val stratumHistoryId: StratumHistoryId,
      val stratumId: StratumId,
      val substratumHistoryId: SubstratumHistoryId,
      val substratumId: SubstratumId,
  )

  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var plantingSiteHistoryId: PlantingSiteHistoryId

  private lateinit var speciesId1: SpeciesId
  private lateinit var speciesId2: SpeciesId

  private lateinit var stratumA: TestStratum
  private lateinit var stratumB: TestStratum
  private lateinit var stratumC: TestStratum
  private lateinit var neverObserved: TestStratum

  @BeforeEach
  fun setUp() {
    insertOrganization()
    plantingSiteId = insertPlantingSite()
    plantingSiteHistoryId = insertPlantingSiteHistory()
    speciesId1 = insertSpecies()
    speciesId2 = insertSpecies()
    stratumA = insertTestStratum("S1", x = 0)
    stratumB = insertTestStratum("S2", x = 5)
    stratumC = insertTestStratum("S3", x = 10)
    neverObserved = insertTestStratum("S4", x = 15)

    every { user.canReadPlantingSite(any()) } returns true
  }

  @Test
  fun `each area uses the most recent observation that completed a plot in it`() {
    val completedObservationId1 = insertObservation(completedTime = Instant.ofEpochSecond(1000))
    observeStratum(completedObservationId1, stratumA, survivalRate = 11, plantDensity = 110)
    observeStratum(completedObservationId1, stratumB, survivalRate = 21, plantDensity = 210)
    observeStratum(completedObservationId1, stratumC, survivalRate = 31, plantDensity = 310)
    insertSiteResult(completedObservationId1, survivalRate = 101, plantDensity = 1010)

    val completedObservationId2 = insertObservation(completedTime = Instant.ofEpochSecond(2000))
    observeStratum(completedObservationId2, stratumA, survivalRate = 12, plantDensity = 120)
    // Stratum B stats will fall back to completedObservationId1
    insertSiteResult(completedObservationId2, survivalRate = 102, plantDensity = 1020)

    val abandonedObservationId =
        insertObservation(
            completedTime = Instant.ofEpochSecond(3000),
            state = ObservationState.Abandoned,
        )
    // Stratum C stats will depend on abandonedObservationId because it was observed
    observeStratum(abandonedObservationId, stratumC, survivalRate = 33, plantDensity = 330)
    insertSiteResult(abandonedObservationId, survivalRate = 103, plantDensity = 1030)

    // None of these observations will count
    val inProgressId = insertObservation(state = ObservationState.InProgress)
    observeStratum(inProgressId, stratumA, survivalRate = 44, plantDensity = 440)
    insertSiteResult(inProgressId, survivalRate = 104, plantDensity = 1040)

    val adHocId = insertObservation(completedTime = Instant.ofEpochSecond(4000), isAdHoc = true)
    observeStratum(adHocId, stratumA, survivalRate = 55, plantDensity = 550)
    insertSiteResult(adHocId, survivalRate = 105, plantDensity = 1050)

    insertObservation(state = ObservationState.Upcoming)

    assertEquals(
        ObservationSiteStatsModel(
            completedTime = Instant.ofEpochSecond(3000),
            observationId = abandonedObservationId,
            plantingDensity = 1030,
            plantingSiteId = plantingSiteId,
            strata =
                listOf(
                    ObservationStratumStatsModel(
                        completedTime = Instant.ofEpochSecond(2000),
                        observationId = completedObservationId2,
                        plantingDensity = 120,
                        stratumId = stratumA.stratumId,
                        substrata =
                            listOf(
                                ObservationSubstratumStatsModel(
                                    completedTime = Instant.ofEpochSecond(2000),
                                    observationId = completedObservationId2,
                                    plantingDensity = 121,
                                    substratumId = stratumA.substratumId,
                                    survivalRate = 13,
                                    totalPlants = 1205,
                                    totalSpecies = 1,
                                )
                            ),
                        survivalRate = 12,
                        totalPlants = 1204,
                        totalSpecies = 1,
                    ),
                    ObservationStratumStatsModel(
                        completedTime = Instant.ofEpochSecond(1000),
                        observationId = completedObservationId1,
                        plantingDensity = 210,
                        stratumId = stratumB.stratumId,
                        substrata =
                            listOf(
                                ObservationSubstratumStatsModel(
                                    completedTime = Instant.ofEpochSecond(1000),
                                    observationId = completedObservationId1,
                                    plantingDensity = 211,
                                    substratumId = stratumB.substratumId,
                                    survivalRate = 22,
                                    totalPlants = 2105,
                                    totalSpecies = 1,
                                )
                            ),
                        survivalRate = 21,
                        totalPlants = 2104,
                        totalSpecies = 1,
                    ),
                    ObservationStratumStatsModel(
                        completedTime = Instant.ofEpochSecond(3000),
                        observationId = abandonedObservationId,
                        plantingDensity = 330,
                        stratumId = stratumC.stratumId,
                        substrata =
                            listOf(
                                ObservationSubstratumStatsModel(
                                    completedTime = Instant.ofEpochSecond(3000),
                                    observationId = abandonedObservationId,
                                    plantingDensity = 331,
                                    substratumId = stratumC.substratumId,
                                    survivalRate = 34,
                                    totalPlants = 3305,
                                    totalSpecies = 1,
                                )
                            ),
                        survivalRate = 33,
                        totalPlants = 3304,
                        totalSpecies = 1,
                    ),
                    ObservationStratumStatsModel(
                        completedTime = null,
                        observationId = null,
                        plantingDensity = null,
                        stratumId = neverObserved.stratumId,
                        substrata =
                            listOf(
                                ObservationSubstratumStatsModel(
                                    completedTime = null,
                                    observationId = null,
                                    plantingDensity = null,
                                    substratumId = neverObserved.substratumId,
                                    survivalRate = null,
                                    totalPlants = null,
                                    totalSpecies = null,
                                )
                            ),
                        survivalRate = null,
                        totalPlants = null,
                        totalSpecies = null,
                    ),
                ),
            survivalRate = 103,
            totalPlants = 10304,
            totalSpecies = 1,
        ),
        store.fetchSiteObservationStats(plantingSiteId),
    )
  }

  @Test
  fun `throws exception if no permission to read planting site`() {
    every { user.canReadPlantingSite(any()) } returns false

    assertThrows<PlantingSiteNotFoundException> {
      store.fetchSiteObservationStats(plantingSiteId)
    }
  }

  private fun insertTestStratum(name: String, x: Int): TestStratum {
    val stratumId = insertStratum(name = name, x = x)
    val stratumHistoryId = inserted.stratumHistoryId
    val substratumId = insertSubstratum(x = x)
    val substratumHistoryId = inserted.substratumHistoryId
    val monitoringPlotId = insertMonitoringPlot(x = x)

    return TestStratum(
        monitoringPlotHistoryId = inserted.monitoringPlotHistoryId,
        monitoringPlotId = monitoringPlotId,
        stratumHistoryId = stratumHistoryId,
        stratumId = stratumId,
        substratumHistoryId = substratumHistoryId,
        substratumId = substratumId,
    )
  }

  private fun observeStratum(
      observationId: ObservationId,
      stratum: TestStratum,
      survivalRate: Int,
      plantDensity: Int,
      plotCompleted: Boolean = true,
  ) {
    insertObservationPlot(
        observationId = observationId,
        monitoringPlotId = stratum.monitoringPlotId,
        monitoringPlotHistoryId = stratum.monitoringPlotHistoryId,
        completedBy = if (plotCompleted) inserted.userId else null,
    )
    insertObservationStratumResult(
        observationId = observationId,
        stratumId = stratum.stratumId,
        stratumHistoryId = stratum.stratumHistoryId,
        plantDensity = plantDensity,
        survivalRate = survivalRate,
    )
    insertObservationSubstratumResult(
        observationId = observationId,
        substratumId = stratum.substratumId,
        substratumHistoryId = stratum.substratumHistoryId,
        plantDensity = plantDensity + 1,
        survivalRate = survivalRate + 1,
    )

    val liveCount = plantDensity * 10

    insertObservedStratumSpeciesTotals(
        observationId = observationId,
        stratumId = stratum.stratumId,
        stratumHistoryId = stratum.stratumHistoryId,
        speciesId = speciesId1,
        totalLive = liveCount,
        totalDead = 1,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = observationId,
        substratumId = stratum.substratumId,
        substratumHistoryId = stratum.substratumHistoryId,
        speciesId = speciesId1,
        totalLive = liveCount + 1,
        totalDead = 1,
    )

    // All dead, so it counts toward the plant total but not the species total.
    insertObservedStratumSpeciesTotals(
        observationId = observationId,
        stratumId = stratum.stratumId,
        stratumHistoryId = stratum.stratumHistoryId,
        speciesId = speciesId2,
        totalDead = 1,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = observationId,
        substratumId = stratum.substratumId,
        substratumHistoryId = stratum.substratumHistoryId,
        speciesId = speciesId2,
        totalDead = 1,
    )

    // Unidentified, so it counts toward the plant total but not the species total.
    insertObservedStratumSpeciesTotals(
        observationId = observationId,
        stratumId = stratum.stratumId,
        stratumHistoryId = stratum.stratumHistoryId,
        certainty = RecordedSpeciesCertainty.Unknown,
        totalLive = 2,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = observationId,
        substratumId = stratum.substratumId,
        substratumHistoryId = stratum.substratumHistoryId,
        certainty = RecordedSpeciesCertainty.Unknown,
        totalLive = 2,
    )
  }

  private fun insertSiteResult(
      observationId: ObservationId,
      survivalRate: Int,
      plantDensity: Int,
  ) {
    insertObservationSiteResult(
        observationId = observationId,
        plantingSiteId = plantingSiteId,
        plantingSiteHistoryId = plantingSiteHistoryId,
        plantDensity = plantDensity,
        survivalRate = survivalRate,
    )

    insertObservedSiteSpeciesTotals(
        observationId = observationId,
        plantingSiteId = plantingSiteId,
        plantingSiteHistoryId = plantingSiteHistoryId,
        speciesId = speciesId1,
        totalLive = plantDensity * 10,
        totalDead = 1,
    )
    insertObservedSiteSpeciesTotals(
        observationId = observationId,
        plantingSiteId = plantingSiteId,
        plantingSiteHistoryId = plantingSiteHistoryId,
        speciesId = speciesId2,
        totalDead = 1,
    )
    insertObservedSiteSpeciesTotals(
        observationId = observationId,
        plantingSiteId = plantingSiteId,
        plantingSiteHistoryId = plantingSiteHistoryId,
        certainty = RecordedSpeciesCertainty.Unknown,
        totalLive = 2,
    )
  }
}
