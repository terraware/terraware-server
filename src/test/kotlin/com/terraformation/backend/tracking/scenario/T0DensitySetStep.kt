package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.db.DatabaseBackedTest
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_T0_TEMP_DENSITIES
import com.terraformation.backend.util.toPlantsPerHectare

class T0DensitySetStep(val scenario: ObservationScenario) :
    NodeWithChildren<T0DensitySetStep.Plot>() {
  val strata = mutableListOf<Stratum>()

  private val test: DatabaseBackedTest
    get() = scenario.test

  fun plot(number: Long, init: Plot.() -> Unit) = initChild(Plot(number), init)

  fun stratum(number: Int, init: Stratum.() -> Unit) = initAndAppend(Stratum(number), strata, init)

  inner class Stratum(val number: Int) : NodeWithChildren<Stratum.Species>() {
    val stratumId =
        scenario.stratumIds[number] ?: throw IllegalArgumentException("Stratum $number not found")

    fun species(speciesId: Int, density: Int?, init: Species.() -> Unit = {}) =
        initChild(Species(speciesId, density), init)

    override fun finish() {
      scenario.observationStore.recalculateSurvivalRates(stratumId)
    }

    inner class Species(val number: Int, val density: Int?) : ScenarioNode {
      override fun finish() {
        val speciesId = scenario.getOrInsertSpecies(number)

        test.dslContext
            .deleteFrom(STRATUM_T0_TEMP_DENSITIES)
            .where(STRATUM_T0_TEMP_DENSITIES.STRATUM_ID.eq(stratumId))
            .and(STRATUM_T0_TEMP_DENSITIES.SPECIES_ID.eq(speciesId))
            .execute()

        if (density != null) {
          test.insertStratumT0TempDensity(
              speciesId = speciesId,
              stratumDensity = density.toBigDecimal().toPlantsPerHectare(),
              stratumId = stratumId,
          )
        }
      }
    }
  }

  inner class Plot(val number: Long) : NodeWithChildren<Plot.Species>() {
    val monitoringPlotId = scenario.getMonitoringPlotId(number)

    fun observation(observation: Int) {
      scenario.t0Store.assignT0PlotObservation(
          monitoringPlotId,
          scenario.observationIds[observation]
              ?: throw IllegalArgumentException("Unknown observation $observation"),
      )
    }

    fun species(speciesId: Int, density: Int?, init: Species.() -> Unit = {}) =
        initChild(Species(speciesId, density), init)

    override fun finish() {
      scenario.observationStore.recalculateSurvivalRates(monitoringPlotId)
    }

    inner class Species(val number: Int, val density: Int?) : ScenarioNode {
      override fun finish() {
        val speciesId = scenario.getOrInsertSpecies(number)

        test.dslContext
            .deleteFrom(PLOT_T0_DENSITIES)
            .where(PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(monitoringPlotId))
            .and(PLOT_T0_DENSITIES.SPECIES_ID.eq(speciesId))
            .execute()

        if (density != null) {
          test.insertPlotT0Density(
              monitoringPlotId = monitoringPlotId,
              plotDensity = density.toBigDecimal().toPlantsPerHectare(),
              speciesId = speciesId,
          )
        }
      }
    }
  }
}
