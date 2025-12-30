package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.tracking.model.BaseMonitoringResult
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertAll

/**
 * Superclass for DSL nodes that specify expected observation results. The format of expected
 * results is more or less the same at each level of the site map hierarchy.
 *
 * @param baseline A previous expected result for the same level of the site map hierarchy. The
 *   expected results specified in the current step are merged with the expected baseline results,
 *   such that a scenario that verifies results multiple times only needs to specify the values that
 *   should have changed from one step to the next.
 * @param assertions Running list of all the assertions to make for this observation. This is shared
 *   across different levels of the hierarchy. The idea is that we build a list of all the
 *   assertions we want to make for the entire observation, and then check them all at once using
 *   [assertAll] such that if multiple values don't match, we get a full list of all the mismatches
 *   rather than bailing out when we hit the first one.
 */
abstract class ExpectedObservationResult<
    T : BaseMonitoringResult,
    B : ExpectedObservationResult<T, B>,
>(
    val name: String,
    val scenario: ObservationScenario,
    val actualResult: T,
    var survivalRate: Int? = null,
    val baseline: B? = null,
    val assertions: MutableList<() -> Unit>,
) : NodeWithChildren<ExpectedObservationResult.Species>() {
  private var survivalRateSet: Boolean = survivalRate != null

  fun survivalRate(expected: Int?) = apply {
    survivalRate = expected
    survivalRateSet = true
    assertions.add { assertEquals(expected, actualResult.survivalRate, "$name survival rate") }
  }

  fun species(
      number: Int,
      survivalRate: Int? = null,
      init: Species.() -> Unit = {},
  ): Species {
    val speciesId = scenario.getOrInsertSpecies(number)
    return initChild(
        Species(
            name,
            number,
            actualResult.species.firstOrNull { it.speciesId == speciesId },
            survivalRate,
            assertions,
        ),
        init,
    )
  }

  protected fun mergeBaselineRates() {
    if (baseline != null) {
      if (!survivalRateSet && baseline.survivalRateSet) {
        survivalRate(baseline.survivalRate)
      }

      val ourSpeciesNumbers = children.map { it.number }.toSet()
      baseline.children
          .filter { it.number !in ourSpeciesNumbers }
          .forEach { species(it.number, it.survivalRate) }
    }
  }

  class Species(
      val parentName: String,
      val number: Int,
      val speciesResult: ObservationSpeciesResultsModel?,
      val survivalRate: Int?,
      val assertions: MutableList<() -> Unit>,
  ) : ScenarioNode {
    override fun finish() {
      assertions.add {
        assertEquals(
            survivalRate,
            speciesResult?.survivalRate,
            "$parentName species $number survival rate",
        )
      }
    }
  }
}
