package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationStratumResultsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumResultsModel
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertNull

class ExpectedSiteResultStep(
    scenario: ObservationScenario,
    val observation: Int,
    actualResult: ObservationResultsModel,
    survivalRate: Int? = null,
    baseline: ExpectedSiteResultStep? = null,
) :
    ExpectedAggregatedResult<ObservationResultsModel, ExpectedSiteResultStep>(
        "Site",
        scenario,
        actualResult,
        survivalRate,
        baseline,
        mutableListOf(),
        observation,
    ) {
  val strata = mutableListOf<Stratum>()

  fun pending(expected: Boolean) = expect("pending", expected) { it.pending }

  fun strataExactly(vararg numbers: Int) =
      expect("strata", numbers.map { "$it" }.sorted()) { result ->
        result.strata.map { it.name }.sorted()
      }

  fun stratum(number: Int, survivalRate: Int? = null, init: Stratum.() -> Unit) =
      initAndAppend(
          Stratum(
              number,
              survivalRate,
              baseline?.strata?.firstOrNull { it.number == number },
          ),
          strata,
          init,
      )

  fun noResultForStratum(number: Int) {
    assertions.add {
      assertNull(
          actualResult.strata.firstOrNull { it.name == "$number" },
          "Result for stratum $number",
      )
    }
  }

  override fun finish() {
    super.finish()
    if (baseline != null) {
      val stratumNumbers = strata.map { it.number }.toSet()
      baseline.strata.filter { it.number !in stratumNumbers }.forEach { stratum(it.number) {} }
    }

    assertAll("Observation $observation", assertions)
  }

  inner class Stratum(val number: Int, survivalRate: Int? = null, baseline: Stratum?) :
      ExpectedAggregatedResult<ObservationStratumResultsModel, Stratum>(
          "Stratum $number",
          scenario,
          actualResult.strata.first { it.name == "$number" },
          survivalRate,
          baseline,
          assertions,
          observation,
      ) {
    val substrata = mutableListOf<Substratum>()

    fun pending(expected: Boolean) = expect("pending", expected) { it.pending }

    fun substrataExactly(vararg numbers: Int) =
        expect("substrata", numbers.map { "$it" }.sorted()) { result ->
          result.substrata.map { it.name }.sorted()
        }

    fun substratum(number: Int, survivalRate: Int? = null, init: Substratum.() -> Unit) =
        initAndAppend(
            Substratum(
                number,
                survivalRate,
                baseline?.substrata?.firstOrNull { it.number == number },
            ),
            substrata,
            init,
        )

    fun noResultForSubstratum(number: Int) {
      assertions.add {
        assertNull(
            actualResult.substrata.firstOrNull { it.name == "$number" },
            "Result for $name substratum $number",
        )
      }
    }

    override fun finish() {
      super.finish()
      if (baseline != null) {
        val ourSubstratumNumbers = substrata.map { it.number }.toSet()
        baseline.substrata
            .filter { it.number !in ourSubstratumNumbers }
            .forEach { substratum(it.number) {} }
      }
    }

    inner class Substratum(val number: Int, survivalRate: Int?, baseline: Substratum?) :
        ExpectedAggregatedResult<ObservationSubstratumResultsModel, Substratum>(
            "$name substratum $number",
            scenario,
            actualResult.substrata.first { it.name == "$number" },
            survivalRate,
            baseline,
            assertions,
            observation,
        ) {
      val plots = mutableListOf<Plot>()

      fun pending(expected: Boolean) = expect("pending", expected) { it.pending }

      fun plotsExactly(vararg numbers: Long) =
          expect("plots", numbers.sorted()) { result ->
            result.monitoringPlots.map { it.monitoringPlotNumber }.sorted()
          }

      fun plot(number: Long, survivalRate: Int? = null, init: Plot.() -> Unit) =
          initAndAppend(
              Plot(
                  number,
                  resultForPlot(number),
                  survivalRate,
                  baseline?.plots?.firstOrNull { it.number == number },
              ),
              plots,
              init,
          )

      override fun finish() {
        super.finish()
        if (baseline != null) {
          val ourPlotNumbers = plots.map { it.number }.toSet()
          baseline.plots.filter { it.number !in ourPlotNumbers }.forEach { plot(it.number) {} }
        }
      }

      /**
       * Returns the actual result for the monitoring plot of the given number.
       *
       * @throws IllegalArgumentException There weren't any results for the requested plot in this
       *   observation.
       */
      private fun resultForPlot(number: Long): ObservationMonitoringPlotResultsModel =
          actualResult.monitoringPlots.firstOrNull { it.monitoringPlotNumber == number }
              ?: throw IllegalArgumentException(
                  "No result for $name plot $number in observation $observation"
              )

      inner class Plot(
          val number: Long,
          actualResult: ObservationMonitoringPlotResultsModel,
          survivalRate: Int?,
          baseline: Plot?,
      ) :
          ExpectedObservationResult<ObservationMonitoringPlotResultsModel, Plot>(
              "$name plot $number",
              scenario,
              actualResult,
              survivalRate,
              baseline,
              assertions,
              observation,
          ) {
        fun pending(expected: Boolean) = expect("pending", expected) { it.pending }
      }
    }
  }
}
