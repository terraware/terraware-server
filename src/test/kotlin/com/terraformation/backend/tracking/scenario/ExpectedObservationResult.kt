package com.terraformation.backend.tracking.scenario

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.toBigDecimal
import com.terraformation.backend.tracking.model.BaseAggregatedMonitoringResult
import com.terraformation.backend.tracking.model.BaseMonitoringResult
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    val observationNumber: Int? = null,
) : NodeWithChildren<ExpectedObservationResult.Species>() {
  private val fields = ExpectedFields<T>(name)

  private data class SpeciesIdentity(
      val certainty: RecordedSpeciesCertainty,
      val speciesId: SpeciesId?,
      val speciesName: String?,
  )

  init {
    if (survivalRate != null) {
      survivalRate(survivalRate)
    }
  }

  protected fun <V> expect(field: String, expected: V, actual: (T) -> V) =
      fields.expect(field, expected, actual)

  fun emptyBiomassResult(completed: Boolean) {
    totalPlants(if (completed) 0 else null)
    totalSpecies(if (completed) 0 else null)
    speciesExactly()
    noPlantMonitoringMetrics()
    if (this is ExpectedAggregatedResult<*, *>) {
      anyPlotsCompleted(completed)
    }
  }

  fun noPlantMonitoringMetrics() {
    plantingDensity(null)
    survivalRate(null)
    if (this is ExpectedAggregatedResult<*, *>) {
      estimatedPlants(null)
      plantingDensityStdDev(null)
      survivalRateStdDev(null)
    }
  }

  fun plantingDensity(expected: Int?) = expect("planting density", expected) { it.plantingDensity }

  fun survivalRate(expected: Int?) {
    survivalRate = expected
    expect("survival rate", expected) { it.survivalRate }
  }

  fun totalPlants(expected: Int?) = expect("total plants", expected) { it.totalPlants }

  fun totalSpecies(expected: Int?) = expect("total species", expected) { it.totalSpecies }

  fun speciesExactly(vararg numbers: Int) =
      expect("species", numbers.map { speciesIdentity(it) }.sortedBy { it.toString() }) { result ->
        result.species
            .map { SpeciesIdentity(it.certainty, it.speciesId, it.speciesName) }
            .sortedBy { it.toString() }
      }

  private fun speciesIdentity(number: Int): SpeciesIdentity {
    val observationId = observationNumber?.let { scenario.observationIds[it] }
    val biomassSpecies = observationId?.let { scenario.biomassSpecies[it to number] }
    return if (biomassSpecies != null) {
      SpeciesIdentity(
          if (biomassSpecies.speciesId == null) {
            RecordedSpeciesCertainty.Other
          } else {
            RecordedSpeciesCertainty.Known
          },
          biomassSpecies.speciesId,
          biomassSpecies.scientificName,
      )
    } else
        when (number) {
          ObservationScenario.OTHER ->
              SpeciesIdentity(RecordedSpeciesCertainty.Other, null, "Other")
          ObservationScenario.UNKNOWN ->
              SpeciesIdentity(RecordedSpeciesCertainty.Unknown, null, null)
          else ->
              SpeciesIdentity(
                  RecordedSpeciesCertainty.Known,
                  scenario.getOrInsertSpecies(number),
                  null,
              )
        }
  }

  /** Returns the ratio between two integers as a percentage value with half-up rounding. */
  fun percent(numerator: Number, denominator: Number): Int {
    return numerator
        .toBigDecimal()
        .multiply(BigDecimal.valueOf(100))
        .divide(denominator.toBigDecimal(), 0, RoundingMode.HALF_UP)
        .toInt()
  }

  /**
   * Asserts that the per-species data matches expectations for this result.
   *
   * @param survivalRate Expected survival rate. If this is null, asserts that the result has no
   *   survival rate. That is, null is treated as an expected value, not an indication that the
   *   survival rate shouldn't be tested.
   * @param totalDead Expected total dead plants, or null to skip verifying the value.
   * @param totalExisting Expected total existing plants, or null to skip verifying the value.
   * @param totalLive Expected total live plants, or null to skip verifying the value.
   */
  fun species(
      number: Int,
      survivalRate: Int?,
      totalDead: Int? = null,
      totalExisting: Int? = null,
      totalLive: Int? = null,
      init: Species.() -> Unit = {},
  ): Species {
    val identity = speciesIdentity(number)

    return initChild(
        Species(
            assertions = assertions,
            baseline = baseline?.children?.firstOrNull { it.number == number },
            parentName = name,
            number = number,
            speciesResult =
                actualResult.species.firstOrNull {
                  SpeciesIdentity(it.certainty, it.speciesId, it.speciesName) == identity
                },
            survivalRate = survivalRate,
            totalDead = totalDead,
            totalExisting = totalExisting,
            totalLive = totalLive,
        ),
        init,
    )
  }

  override fun finish() {
    fields.addAssertions(actualResult, assertions, baseline?.fields)
    if (baseline != null) {
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
      val totalDead: Int?,
      val totalExisting: Int?,
      val totalLive: Int?,
      val assertions: MutableList<() -> Unit>,
      val baseline: Species? = null,
  ) : ScenarioNode {
    private val fields =
        ExpectedFields<ObservationSpeciesResultsModel>("$parentName species $number")

    init {
      fields.expect("survival rate", survivalRate) { it.survivalRate }
      if (totalDead != null) {
        fields.expect("total dead", totalDead) { it.totalDead }
      }
      if (totalExisting != null) {
        fields.expect("total existing", totalExisting) { it.totalExisting }
      }
      if (totalLive != null) {
        fields.expect("total live", totalLive) { it.totalLive }
      }
    }

    fun latestLive(expected: Int) = fields.expect("latest live", expected) { it.latestLive }

    fun permanentLive(expected: Int) =
        fields.expect("permanent live", expected) { it.permanentLive }

    fun t0Density(expected: BigDecimal?) = fields.expect("T0 density", expected) { it.t0Density }

    fun totalPlants(expected: Int) = fields.expect("total plants", expected) { it.totalPlants }

    override fun finish() {
      assertions.add { assertNotNull(speciesResult, "$parentName species $number result") }
      if (speciesResult != null) {
        fields.addAssertions(speciesResult, assertions, baseline?.fields)
      }
    }
  }
}

abstract class ExpectedAggregatedResult<
    T : BaseAggregatedMonitoringResult,
    B : ExpectedAggregatedResult<T, B>,
>(
    name: String,
    scenario: ObservationScenario,
    actualResult: T,
    survivalRate: Int?,
    baseline: B?,
    assertions: MutableList<() -> Unit>,
    observationNumber: Int? = null,
) :
    ExpectedObservationResult<T, B>(
        name,
        scenario,
        actualResult,
        survivalRate,
        baseline,
        assertions,
        observationNumber,
    ) {
  fun anyPlotsCompleted(expected: Boolean) =
      expect("any plots completed", expected) { it.anyPlotsCompleted }

  fun estimatedPlants(expected: Int?) = expect("estimated plants", expected) { it.estimatedPlants }

  fun plantingCompleted(expected: Boolean) =
      expect("planting completed", expected) { it.plantingCompleted }

  fun plantingDensityStdDev(expected: Int?) =
      expect("planting density standard deviation", expected) { it.plantingDensityStdDev }

  fun survivalRateStdDev(expected: Int?) =
      expect("survival rate standard deviation", expected) { it.survivalRateStdDev }
}

private class ExpectedFields<T>(private val name: String) {
  private val fields = mutableMapOf<String, (T) -> Unit>()

  fun <V> expect(field: String, expected: V, actual: (T) -> V) {
    fields[field] = { result -> assertEquals(expected, actual(result), "$name $field") }
  }

  fun addAssertions(actual: T, assertions: MutableList<() -> Unit>, baseline: ExpectedFields<T>?) {
    baseline?.fields?.forEach { (field, check) -> fields.putIfAbsent(field, check) }
    fields.values.forEach { check -> assertions.add { check(actual) } }
  }
}
