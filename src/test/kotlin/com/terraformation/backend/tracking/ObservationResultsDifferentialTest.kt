package com.terraformation.backend.tracking

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLOT_T0_DENSITIES
import com.terraformation.backend.tracking.db.ObservationScenarioTest
import com.terraformation.backend.tracking.event.T0PlotDataAssignedEvent
import com.terraformation.backend.tracking.event.T0StratumDataAssignedEvent
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import java.math.BigDecimal
import org.jooq.Record
import org.jooq.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Checks that rebuilding only the results flagged by an edit produces the same data as rebuilding
 * the whole planting site. A difference means an edit's invalidation missed something the edit
 * affected, or the per-row rebuild disagrees with itself.
 */
class ObservationResultsDifferentialTest : ObservationScenarioTest() {
  @BeforeEach
  fun setUp() {
    every { user.canReadOrganization(organizationId) } returns true
  }

  @MethodSource("scenarios")
  @ParameterizedTest(name = "{0}")
  fun `t0 plot density change`(scenario: Scenario) {
    scenario.import()
    val plotId = firstPermanentPlotWithT0()

    assertFlaggedRebuildMatchesFullRebuild("after t0 change") {
      dslContext
          .update(PLOT_T0_DENSITIES)
          .set(PLOT_T0_DENSITIES.PLOT_DENSITY, PLOT_T0_DENSITIES.PLOT_DENSITY.plus(BigDecimal.TEN))
          .where(PLOT_T0_DENSITIES.MONITORING_PLOT_ID.eq(plotId))
          .execute()
      observationResultsInvalidator.on(T0PlotDataAssignedEvent(plotId))
    }
  }

  @MethodSource("scenarios")
  @ParameterizedTest(name = "{0}")
  fun `species count edit in the earliest observation`(scenario: Scenario) {
    scenario.import()
    val (observationId, plotId, speciesId) = firstKnownPlotSpecies()

    assertFlaggedRebuildMatchesFullRebuild("after species edit") {
      observationStore.updateMonitoringSpecies(
          observationId,
          plotId,
          RecordedSpeciesCertainty.Known,
          speciesId,
          null,
      ) { model ->
        model.copy(totalLive = model.totalLive + 3)
      }
    }
  }

  @MethodSource("scenarios")
  @ParameterizedTest(name = "{0}")
  fun `temp plots setting change`(scenario: Scenario) {
    scenario.import()

    assertFlaggedRebuildMatchesFullRebuild("after temp plots setting change") {
      dslContext
          .update(PLANTING_SITES)
          .set(PLANTING_SITES.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, true)
          .where(PLANTING_SITES.ID.eq(plantingSiteId))
          .execute()
      observationResultsInvalidator.invalidateSite(plantingSiteId)
    }
  }

  @MethodSource("scenarios")
  @ParameterizedTest(name = "{0}")
  fun `stratum temp density change with temp plots included`(scenario: Scenario) {
    scenario.import()
    dslContext
        .update(PLANTING_SITES)
        .set(PLANTING_SITES.SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, true)
        .where(PLANTING_SITES.ID.eq(plantingSiteId))
        .execute()
    observationResultsInvalidator.invalidateSite(plantingSiteId)
    observationResultsRecalculator.recalculateAllSites()

    val stratumId = stratumIds.values.first()

    assertFlaggedRebuildMatchesFullRebuild("after stratum temp density change") {
      speciesIds.values.forEach { speciesId ->
        insertStratumT0TempDensity(
            stratumId = stratumId,
            speciesId = speciesId,
            stratumDensity = BigDecimal(5).toPlantsPerHectare(),
        )
      }
      observationResultsInvalidator.on(T0StratumDataAssignedEvent(stratumId))
    }
  }

  data class Scenario(val prefix: String, val numObservations: Int) {
    override fun toString() = prefix.substringAfterLast('/')
  }

  private fun Scenario.import() {
    importFromCsvFiles(prefix, numObservations, 30)
  }

  /**
   * Applies an edit, rebuilds only the flagged results, and asserts that rebuilding every result of
   * the site afterwards doesn't change anything.
   */
  private fun assertFlaggedRebuildMatchesFullRebuild(message: String, edit: () -> Unit) {
    edit()
    observationResultsRecalculator.recalculateAllSites()
    val afterFlaggedRebuild = snapshot()

    observationResultsInvalidator.invalidateSite(plantingSiteId)
    observationResultsRecalculator.recalculateAllSites()
    val afterFullRebuild = snapshot()

    derivedTables.forEach { table ->
      val flagged = afterFlaggedRebuild[table.name]!!
      val full = afterFullRebuild[table.name]!!
      assertEquals(
          emptyList<String>() to emptyList<String>(),
          (full - flagged.toSet()) to (flagged - full.toSet()),
          "$message: ${table.name} (rows only in full rebuild, rows only in flagged rebuild)",
      )
    }
  }

  private fun snapshot(): Map<String, List<String>> = derivedTables.associate { table ->
    table.name to dslContext.selectFrom(table).fetch().map { it.formatCSV(false) }.sorted()
  }

  private fun firstPermanentPlotWithT0(): MonitoringPlotId =
      dslContext
          .selectDistinct(PLOT_T0_DENSITIES.MONITORING_PLOT_ID)
          .from(PLOT_T0_DENSITIES)
          .join(OBSERVATION_PLOTS)
          .on(OBSERVATION_PLOTS.MONITORING_PLOT_ID.eq(PLOT_T0_DENSITIES.MONITORING_PLOT_ID))
          .where(OBSERVATION_PLOTS.IS_PERMANENT.isTrue)
          .and(OBSERVATION_PLOTS.STATUS_ID.eq(ObservationPlotStatus.Completed))
          .orderBy(PLOT_T0_DENSITIES.MONITORING_PLOT_ID)
          .limit(1)
          .fetchSingle(PLOT_T0_DENSITIES.MONITORING_PLOT_ID)!!

  private fun firstKnownPlotSpecies() =
      dslContext
          .select(
              OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID,
              OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID,
              OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
          )
          .from(OBSERVED_PLOT_SPECIES_TOTALS)
          .join(OBSERVATIONS)
          .on(OBSERVATIONS.ID.eq(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID))
          .where(OBSERVED_PLOT_SPECIES_TOTALS.CERTAINTY_ID.eq(RecordedSpeciesCertainty.Known))
          .orderBy(
              OBSERVATIONS.COMPLETED_TIME,
              OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID,
              OBSERVED_PLOT_SPECIES_TOTALS.SPECIES_ID,
          )
          .limit(1)
          .fetchSingle { record ->
            Triple<ObservationId, MonitoringPlotId, SpeciesId>(
                record.value1()!!,
                record.value2()!!,
                record.value3()!!,
            )
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
            Scenario("/tracking/observation/TwoObservations", 2),
            Scenario("/tracking/observation/DisjointSubstrata", 3),
            Scenario("/tracking/observation/DisjointStrata", 2),
            Scenario("/tracking/observation/PermanentPlotChanges", 3),
        )
  }
}
