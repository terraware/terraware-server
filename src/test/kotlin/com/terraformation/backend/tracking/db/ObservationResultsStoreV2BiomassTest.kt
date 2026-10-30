package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.tracking.db.observationStore.BaseObservationStoreTest
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.scenario.ExpectedAggregatedResult
import com.terraformation.backend.tracking.scenario.ExpectedObservationResult
import com.terraformation.backend.tracking.scenario.ObservationScenario
import java.math.BigDecimal
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

class ObservationResultsStoreV2BiomassTest : BaseObservationStoreTest() {
  private val scenario by lazy { ObservationScenario.forTest(this, clock = clock) }

  @ParameterizedTest
  @EnumSource(ObservationResultsDepth::class)
  fun `counts live and dead species without monitoring baselines or carry forward`(
      depth: ObservationResultsDepth
  ) {
    scenario.apply {
      siteCreated {
        stratum(1) { substratum(1) { plot(1) } }
        stratum(2) { substratum(2) { plot(2) } }
      }
      observation(1, initialState = ObservationState.Completed, recalculate = false) {}
      observation(
          2,
          type = ObservationType.BiomassMeasurements,
          initialState = ObservationState.Completed,
          recalculate = false,
      ) {
        biomassSpecies(1)
        biomassSpecies(2, scientificName = "Dead-only other")
      }
      populateStoredResults(1) {
        (1..2).forEach { number ->
          stratum(number) {
            substratum(number) {
              plot(number.toLong(), completed = true, isPermanent = true) {
                species(2, totalLive = 50)
                t0Density(1, BigDecimal.TEN)
                t0Density(2, BigDecimal.TEN)
              }
            }
          }
        }
      }
      populateStoredResults(2, pending = true) {
        site {
          counts(totalLive = 12, totalDead = 2, permanentLive = 6)
          species(1, totalLive = 12, permanentLive = 6)
          species(2, totalDead = 2)
        }
        (1..2).forEach { number ->
          val permanentLive = if (number == 1) 6 else 0
          stratum(number) {
            counts(totalLive = 6, totalDead = 1, permanentLive = permanentLive)
            species(1, totalLive = 6, permanentLive = permanentLive)
            species(2, totalDead = 1)
            substratum(number) {
              counts(totalLive = 6, totalDead = 1, permanentLive = permanentLive)
              species(1, totalLive = 6, permanentLive = permanentLive)
              species(2, totalDead = 1)
              plot(number.toLong(), completed = true, isPermanent = number == 1) {
                counts(totalLive = 6, totalDead = 1, permanentLive = permanentLive)
                species(1, totalLive = 6, permanentLive = permanentLive)
                species(2, totalDead = 1)
              }
            }
          }
        }
      }

      observation(
          3,
          type = ObservationType.BiomassMeasurements,
          initialState = ObservationState.Completed,
          recalculate = false,
      ) {
        biomassSpecies(2, scientificName = "Later other")
      }

      val baseline =
          expectResults(2, depth = depth) {
            biomassCounts(live = 12, dead = 2, permanentLive = 6)
            pending(true)
            strataExactly(1, 2)
            (1..2).forEach { number ->
              val permanentLive = if (number == 1) 6 else 0
              stratum(number) {
                biomassCounts(live = 6, dead = 1, permanentLive = permanentLive)
                pending(true)
                substrataExactly(number)
                substratum(number) {
                  biomassCounts(live = 6, dead = 1, permanentLive = permanentLive)
                  pending(true)
                  plotsExactly(number.toLong())
                  plot(number.toLong()) {
                    biomassCounts(live = 6, dead = 1, permanentLive = permanentLive)
                    pending(true)
                  }
                }
              }
            }
          }
      expectUnchangedResults(2, baseline)
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `distinguishes completed empty scopes from unavailable scopes at every depth`(
      completed: Boolean
  ) {
    scenario.apply {
      siteCreated { stratum(1) { substratum(1) { plot(1) } } }
      observation(1, type = ObservationType.BiomassMeasurements, recalculate = false) {}
      populateStoredResults(1, pending = true) {
        site { counts(totalLive = 0) }
        stratum(1) {
          counts(totalLive = 0)
          substratum(1) {
            counts(totalLive = 0)
            plot(1, completed = completed) { counts(totalLive = 0) }
          }
        }
      }
      ObservationResultsDepth.entries.forEach { depth ->
        expectResults(1, depth = depth) {
          emptyBiomassResult(completed)
          pending(true)
          strataExactly(1)
          stratum(1) {
            emptyBiomassResult(completed)
            pending(true)
            substrataExactly(1)
            substratum(1) {
              emptyBiomassResult(completed)
              pending(true)
              plotsExactly(1)
              plot(1) {
                emptyBiomassResult(completed)
                pending(true)
              }
            }
          }
        }
      }
    }
  }

  private fun ExpectedObservationResult<*, *>.biomassCounts(
      live: Int,
      dead: Int,
      permanentLive: Int,
  ) {
    totalPlants(live + dead)
    totalSpecies(2)
    noPlantMonitoringMetrics()
    if (this is ExpectedAggregatedResult<*, *>) {
      anyPlotsCompleted(true)
    }
    speciesExactly(1, 2)
    species(1, survivalRate = null, totalLive = live, totalDead = 0, totalExisting = 0) {
      latestLive(live)
      this.permanentLive(permanentLive)
      totalPlants(live)
      t0Density(null)
    }
    species(2, survivalRate = null, totalLive = 0, totalDead = dead, totalExisting = 0) {
      latestLive(0)
      this.permanentLive(0)
      totalPlants(dead)
      t0Density(null)
    }
  }
}
