package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import io.mockk.every
import java.math.BigDecimal
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Checks that recalculating flagged results reproduces the stored results. Each test scrambles the
 * values every level is responsible for, flags every result of the site, recalculates, and expects
 * every derived table to match what it was before scrambling.
 */
class ObservationRecalculationStoreTest : ObservationScenarioTest() {
  private val recalculationStore: ObservationRecalculationStore by lazy {
    ObservationRecalculationStore(dslContext)
  }

  @BeforeEach
  fun setUp() {
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
  }

  @MethodSource("scenarios")
  @ParameterizedTest(name = "{0}")
  fun `recalculates scrambled results at every level`(scenario: Scenario) {
    scenario.import()

    assertRecalculationRestores()
  }

  @Test
  fun `recalculates scrambled results after t0 densities change`() {
    importFromCsvFiles("/tracking/observation/TwoObservations", 2, 30)

    dslContext
        .update(PLOT_T0_DENSITIES)
        .set(PLOT_T0_DENSITIES.PLOT_DENSITY, PLOT_T0_DENSITIES.PLOT_DENSITY.times(2))
        .execute()
    observationStore.recalculateSurvivalRates(plantingSiteId)

    assertRecalculationRestores()
  }

  data class Scenario(val prefix: String, val numObservations: Int) {
    override fun toString() = "${prefix.substringAfterLast('/')} ($numObservations)"
  }

  private fun Scenario.import() {
    importFromCsvFiles(prefix, numObservations, 30)
  }

  private fun assertRecalculationRestores() {
    // Rolled-forward stratum species totals for strata an observation didn't observe have no
    // stratum results row and are never read; the recalculation removes them.
    dslContext
        .deleteFrom(OBSERVED_STRATUM_SPECIES_TOTALS)
        .whereNotExists(
            DSL.selectOne()
                .from(OBSERVATION_STRATUM_RESULTS)
                .where(
                    OBSERVATION_STRATUM_RESULTS.OBSERVATION_ID.eq(
                        OBSERVED_STRATUM_SPECIES_TOTALS.OBSERVATION_ID
                    )
                )
                .and(
                    OBSERVATION_STRATUM_RESULTS.STRATUM_HISTORY_ID.eq(
                        OBSERVED_STRATUM_SPECIES_TOTALS.STRATUM_HISTORY_ID
                    )
                )
        )
        .execute()

    ObservationResultsInvalidator(dslContext).invalidateSite(plantingSiteId)
    val expected = snapshot()

    scramblePlots()
    scrambleSubstrata()
    scrambleStrata()
    scrambleSite()
    recalculationStore.recalculateFlaggedResults(plantingSiteId)
    val actual = snapshot()

    assertAll(
        derivedTables.map { table ->
          {
            val expectedRows = expected[table.name]!!
            val actualRows = actual[table.name]!!
            assertEquals(
                emptyList<String>() to emptyList<String>(),
                (expectedRows - actualRows.toSet()) to (actualRows - expectedRows.toSet()),
                "${table.name}: (missing rows, unexpected rows)",
            )
          }
        }
    )
  }

  private fun snapshot(): Map<String, List<String>> = derivedTables.associate { table ->
    table.name to dslContext.selectFrom(table).fetch().map { it.formatCSV(false) }.sorted()
  }

  private fun scramblePlots() {
    dslContext
        .update(OBSERVED_PLOT_SPECIES_TOTALS)
        .set(OBSERVED_PLOT_SPECIES_TOTALS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVATION_PLOT_RESULTS)
        .set(OBSERVATION_PLOT_RESULTS.TOTAL_LIVE, -1)
        .set(OBSERVATION_PLOT_RESULTS.PLANT_DENSITY, -1)
        .set(OBSERVATION_PLOT_RESULTS.SURVIVAL_RATE, -1)
        .execute()
  }

  private fun scrambleSubstrata() {
    dslContext
        .update(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
        .set(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_LIVE, -1)
        .set(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVATION_SUBSTRATUM_RESULTS)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_LIVE, -1)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.PLANT_DENSITY, -1)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE, -1)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.SURVIVAL_RATE_AREA, BigDecimal(-1))
        .execute()
  }

  private fun scrambleStrata() {
    dslContext
        .update(OBSERVED_STRATUM_SPECIES_TOTALS)
        .set(OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_LIVE, -1)
        .set(OBSERVED_STRATUM_SPECIES_TOTALS.PERMANENT_LIVE, -1)
        .set(OBSERVED_STRATUM_SPECIES_TOTALS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVATION_STRATUM_RESULTS)
        .set(OBSERVATION_STRATUM_RESULTS.TOTAL_LIVE, -1)
        .set(OBSERVATION_STRATUM_RESULTS.PLANT_DENSITY, -1)
        .set(OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE, -1)
        .set(OBSERVATION_STRATUM_RESULTS.SURVIVAL_RATE_AREA, BigDecimal(-1))
        .execute()
  }

  private fun scrambleSite() {
    dslContext
        .update(OBSERVED_SITE_SPECIES_TOTALS)
        .set(OBSERVED_SITE_SPECIES_TOTALS.TOTAL_LIVE, -1)
        .set(OBSERVED_SITE_SPECIES_TOTALS.PERMANENT_LIVE, -1)
        .set(OBSERVED_SITE_SPECIES_TOTALS.SURVIVAL_RATE, -1)
        .execute()
    dslContext
        .update(OBSERVATION_SITE_RESULTS)
        .set(OBSERVATION_SITE_RESULTS.TOTAL_LIVE, -1)
        .set(OBSERVATION_SITE_RESULTS.PLANT_DENSITY, -1)
        .set(OBSERVATION_SITE_RESULTS.SURVIVAL_RATE, -1)
        .set(OBSERVATION_SITE_RESULTS.SURVIVAL_RATE_AREA, BigDecimal(-1))
        .execute()
  }

  private val derivedTables: List<Table<out Record>> =
      listOf(
          OBSERVED_PLOT_SPECIES_TOTALS,
          OBSERVED_SUBSTRATUM_SPECIES_TOTALS,
          OBSERVED_STRATUM_SPECIES_TOTALS,
          OBSERVED_SITE_SPECIES_TOTALS,
          OBSERVATION_PLOT_RESULTS,
          OBSERVATION_SUBSTRATUM_RESULTS,
          OBSERVATION_STRATUM_RESULTS,
          OBSERVATION_SITE_RESULTS,
      )

  companion object {
    @JvmStatic
    fun scenarios() =
        listOf(
            Scenario("/tracking/observation/TwoObservations", 1),
            Scenario("/tracking/observation/TwoObservations", 2),
            Scenario("/tracking/observation/DisjointSubstrata", 3),
            Scenario("/tracking/observation/DisjointStrata", 2),
            Scenario("/tracking/observation/PermanentPlotChanges", 3),
        )
  }
}
