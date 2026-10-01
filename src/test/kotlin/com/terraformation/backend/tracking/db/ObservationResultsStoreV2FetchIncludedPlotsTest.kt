package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotHistoryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.ObservationIncludedPlotModel
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservationResultsStoreV2FetchIncludedPlotsTest : ObservationScenarioTest() {
  override val user = mockUser()

  @Test
  fun `includes plots from earlier observations of substrata not covered by the observation`() {
    val speciesId = insertSpecies()
    val stratumId = insertStratum()
    insertStratumT0TempDensity(stratumDensity = BigDecimal.ONE.toPlantsPerHectare())

    val substratumIdA = insertSubstratum()
    val plotIdA = insertMonitoringPlot(permanentIndex = 1, plotNumber = 1)
    val plotHistoryIdA = inserted.monitoringPlotHistoryId
    insertPlotT0Density(plotDensity = BigDecimal.ONE.toPlantsPerHectare())

    val substratumIdB = insertSubstratum()
    val plotIdB1 = insertMonitoringPlot(permanentIndex = 2, plotNumber = 2)
    val plotHistoryIdB1 = inserted.monitoringPlotHistoryId
    val plotIdB2 = insertMonitoringPlot(plotNumber = 3)
    val plotHistoryIdB2 = inserted.monitoringPlotHistoryId

    val time1 = Instant.ofEpochSecond(1)
    clock.instant = time1
    val observationId1 = insertObservation()
    insertObservationRequestedSubstratum(substratumId = substratumIdA)
    insertObservationRequestedSubstratum(substratumId = substratumIdB)
    insertObservationPlotAndComplete(observationId1, plotIdA, plotHistoryIdA, true, speciesId)
    insertObservationPlotAndComplete(observationId1, plotIdB1, plotHistoryIdB1, true, speciesId)
    insertObservationPlotAndComplete(observationId1, plotIdB2, plotHistoryIdB2, false, speciesId)

    val time2 = Instant.ofEpochSecond(2)
    clock.instant = time2
    val observationId2 = insertObservation()
    insertObservationRequestedSubstratum(substratumId = substratumIdA)
    insertObservationPlotAndComplete(observationId2, plotIdA, plotHistoryIdA, true, speciesId)

    assertEquals(
        listOf(
            ObservationIncludedPlotModel(
                completedTime = time2,
                hasT0Density = true,
                isPermanent = true,
                isRolledForwardFromDeletedSubstratum = false,
                monitoringPlotId = plotIdA,
                monitoringPlotNumber = 1,
                observationId = observationId2,
                stratumId = stratumId,
                substratumId = substratumIdA,
                survivalRate = 100,
                totalLive = 1,
            ),
            ObservationIncludedPlotModel(
                completedTime = time1,
                hasT0Density = false,
                isPermanent = true,
                isRolledForwardFromDeletedSubstratum = false,
                monitoringPlotId = plotIdB1,
                monitoringPlotNumber = 2,
                observationId = observationId1,
                stratumId = stratumId,
                substratumId = substratumIdB,
                survivalRate = null,
                totalLive = 1,
            ),
            ObservationIncludedPlotModel(
                completedTime = time1,
                hasT0Density = true,
                isPermanent = false,
                isRolledForwardFromDeletedSubstratum = false,
                monitoringPlotId = plotIdB2,
                monitoringPlotNumber = 3,
                observationId = observationId1,
                stratumId = stratumId,
                substratumId = substratumIdB,
                survivalRate = null,
                totalLive = 1,
            ),
        ),
        resultsStoreV2.fetchIncludedPlots(observationId2),
    )
  }

  @Test
  fun `includes plots from earlier observations of substrata that have since been deleted`() {
    allowSiteEdits()

    scenario {
      siteCreated {
        stratum(1) {
          substratum(1) { plot(1) }
          substratum(2) { plot(2) }
        }
      }

      observation(1) {
        plot(1) { species(0, live = 1) }
        plot(2) { species(0, live = 1) }
      }

      observation(2) { plot(1) { species(0, live = 1) } }

      siteEdited {
        stratum(1) { substratum(1) { plot(1) } }
        substratumDeleted(2)
      }

      val includedPlots = resultsStoreV2.fetchIncludedPlots(observationIds[2]!!)

      assertEquals(
          listOf(
              monitoringPlotIds[1L] to observationIds[2],
              monitoringPlotIds[2L] to observationIds[1],
          ),
          includedPlots.map { it.monitoringPlotId to it.observationId },
          "Included plots",
      )
      assertEquals(
          null,
          includedPlots.single { it.monitoringPlotId == monitoringPlotIds[2L] }.substratumId,
          "Substratum ID of plot in deleted substratum",
      )
      assertEquals(
          listOf(false, true),
          includedPlots.map { it.isRolledForwardFromDeletedSubstratum },
          "Is rolled forward from deleted substratum",
      )
    }
  }

  @Test
  fun `does not flag plots in deleted substrata that were observed by the observation itself`() {
    allowSiteEdits()

    scenario {
      siteCreated {
        stratum(1) {
          substratum(1) { plot(1) }
          substratum(2) { plot(2) }
        }
      }

      observation(1) {
        plot(1) { species(0, live = 1) }
        plot(2) { species(0, live = 1) }
      }

      siteEdited {
        stratum(1) { substratum(1) { plot(1) } }
        substratumDeleted(2)
      }

      val includedPlot =
          resultsStoreV2.fetchIncludedPlots(observationIds[1]!!).single {
            it.monitoringPlotId == monitoringPlotIds[2L]
          }

      assertEquals(null, includedPlot.substratumId, "Substratum ID")
      assertEquals(
          false,
          includedPlot.isRolledForwardFromDeletedSubstratum,
          "Is rolled forward from deleted substratum",
      )
    }
  }

  @Test
  fun `checks temporary plot t0 density against the stratum the plot was observed in`() {
    allowSiteEdits()

    scenario {
      siteCreated {
        stratum(1) {
          substratum(1) { plot(1, permanentIndex = null) }
          substratum(3) { plot(3) }
        }
        stratum(2) { substratum(2) { plot(2) } }
      }

      t0DensitySet { stratum(1) { species(0, density = 10) } }

      observation(1) {
        plot(1, isPermanent = false) { species(0, live = 1) }
        plot(2) { species(0, live = 1) }
        plot(3) { species(0, live = 1) }
      }

      siteEdited {
        stratum(1) { substratum(3) { plot(3) } }
        stratum(2) {
          substratum(1) { plot(1) } // Moved from stratum 1
          substratum(2) { plot(2) }
        }
      }

      observation(2) { plot(2) { species(0, live = 1) } }

      val includedPlot =
          resultsStoreV2.fetchIncludedPlots(observationIds[2]!!).single {
            it.monitoringPlotId == monitoringPlotIds[1L]
          }

      assertEquals(observationIds[1], includedPlot.observationId, "Observation ID")
      assertEquals(stratumIds[2], includedPlot.stratumId, "Stratum ID")
      assertEquals(true, includedPlot.hasT0Density, "Has t0 density")
    }
  }

  @Test
  fun `does not include plots that were not completed`() {
    val speciesId = insertSpecies()
    insertStratum()
    insertSubstratum()
    val plotIdCompleted = insertMonitoringPlot(permanentIndex = 1)
    val plotHistoryIdCompleted = inserted.monitoringPlotHistoryId
    val plotIdNotObserved = insertMonitoringPlot(permanentIndex = 2)

    val observationId = insertObservation()
    insertObservationRequestedSubstratum()
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = true,
        monitoringPlotId = plotIdNotObserved,
        statusId = ObservationPlotStatus.NotObserved,
    )
    insertObservationPlotAndComplete(
        observationId,
        plotIdCompleted,
        plotHistoryIdCompleted,
        true,
        speciesId,
    )

    assertEquals(
        listOf(plotIdCompleted),
        resultsStoreV2.fetchIncludedPlots(observationId).map { it.monitoringPlotId },
    )
  }

  private fun insertObservationPlotAndComplete(
      observationId: ObservationId,
      monitoringPlotId: MonitoringPlotId,
      monitoringPlotHistoryId: MonitoringPlotHistoryId,
      isPermanent: Boolean,
      speciesId: SpeciesId,
  ) {
    insertObservationPlot(
        claimedBy = user.userId,
        isPermanent = isPermanent,
        monitoringPlotId = monitoringPlotId,
        monitoringPlotHistoryId = monitoringPlotHistoryId,
        observationId = observationId,
    )
    observationStore.completePlot(
        observationId,
        monitoringPlotId,
        emptySet(),
        "Notes",
        clock.instant,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            )
        ),
    )
    observationResultsRecalculator.recalculateAllSites()
  }

  private fun allowSiteEdits() {
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
  }
}
