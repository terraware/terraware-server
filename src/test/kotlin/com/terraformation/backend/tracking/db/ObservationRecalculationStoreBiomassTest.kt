package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.TreeGrowthForm
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationSiteResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationStratumResultsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationSubstratumResultsRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.tracking.db.observationStore.BaseObservationStoreTest
import com.terraformation.backend.tracking.scenario.ExpectedStoredResultsStep
import com.terraformation.backend.tracking.scenario.ObservationScenario
import io.mockk.every
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ObservationRecalculationStoreBiomassTest : BaseObservationStoreTest() {
  private val recalculationStore: ObservationRecalculationStore by lazy {
    ObservationRecalculationStore(dslContext)
  }
  private val scenario by lazy { ObservationScenario.forTest(this, clock = clock) }

  @BeforeEach
  fun setUpRecalculation() {
    every { user.canUpdatePlantingSite(any()) } returns true
  }

  @Test
  fun `rolls up completed biomass plots across strata using biomass species identity`() {
    scenario.apply {
      siteCreated {
        stratum(1) { substratum(1) { plot(1) } }
        stratum(2) { substratum(2) { (2L..5L).forEach { plot(it) } } }
      }
      observation(1, type = ObservationType.BiomassMeasurements, recalculate = false) {
        biomassSpecies(1)
        biomassSpecies(2)
        biomassSpecies(3, scientificName = "Herbaceous species")
        biomassSpecies(4, scientificName = "Additional species")
        plot(1) {
          repeat(3) { trunk ->
            tree(1, treeNumber = 1, trunkNumber = trunk + 1, growthForm = TreeGrowthForm.Trunk)
          }
          repeat(2) { tree(1, growthForm = TreeGrowthForm.Shrub, isDead = true) }
          repeat(4) { tree(2, growthForm = TreeGrowthForm.Shrub) }
        }
        plot(2, isPermanent = false) { repeat(5) { tree(1) } }
        plot(3)
        plot(4, status = ObservationPlotStatus.Unclaimed) { tree(1) }
        plot(5, status = ObservationPlotStatus.NotObserved) { tree(1) }
        val plot = scenario.getMonitoringPlotId(1)
        insertPlantingSiteHistory()
        insertStratum()
        insertSubstratum()
        dslContext
            .update(MONITORING_PLOTS)
            .set(MONITORING_PLOTS.SUBSTRATUM_ID, inserted.substratumId)
            .where(MONITORING_PLOTS.ID.eq(plot))
            .execute()
        insertMonitoringPlotHistory(monitoringPlotId = plot)
        populatePendingResults(1)
      }
    }

    val plantingSiteId = scenario.plantingSiteId
    val plot1 = scenario.getMonitoringPlotId(1)
    val plot2 = scenario.getMonitoringPlotId(2)

    val quadratSpecies = scenario.getBiomassSpeciesId(1, 3)
    insertObservationBiomassDetails(monitoringPlotId = plot1)
    insertObservationBiomassDetails(monitoringPlotId = plot2)
    insertObservationBiomassQuadratDetails(monitoringPlotId = plot1)
    insertObservationBiomassQuadratSpecies(
        monitoringPlotId = plot1,
        biomassSpeciesId = quadratSpecies,
        abundanceCount = 5,
    )
    val additionalSpecies = scenario.getBiomassSpeciesId(1, 4)
    insertObservationBiomassAdditionalSpecies(
        monitoringPlotId = plot2,
        biomassSpeciesId = additionalSpecies,
    )
    listOf(
            OBSERVATION_PLOT_RESULTS,
            OBSERVATION_SUBSTRATUM_RESULTS,
            OBSERVATION_STRATUM_RESULTS,
            OBSERVATION_SITE_RESULTS,
        )
        .forEach { table ->
          dslContext
              .update(table)
              .set(table.field("plant_density", Int::class.java)!!, 99)
              .apply {
                table.field("plant_density_std_dev", Int::class.java)?.let { set(it, 99) }
                table.field("observed_density", Int::class.java)?.let { set(it, 99) }
              }
              .execute()
        }
    repeat(2) {
      recalculationStore.recalculateFlaggedResults(plantingSiteId)

      scenario.expectStoredResults(1) {
        expectExactResults()
        site {
          biomassCounts(12, totalDead = 2, permanentLive = 7)
          species(1, totalLive = 8, totalDead = 2, permanentLive = 3)
          species(2, totalLive = 4)
        }
        stratum(1) {
          biomassCounts(7, totalDead = 2)
          species(1, totalLive = 3, totalDead = 2)
          species(2, totalLive = 4)
          substratum(1) {
            biomassCounts(7, totalDead = 2)
            species(1, totalLive = 3, totalDead = 2)
            species(2, totalLive = 4)
            plot(1) {
              biomassCounts(7, totalDead = 2)
              species(1, totalLive = 3, totalDead = 2)
              species(2, totalLive = 4)
            }
          }
        }
        stratum(2) {
          biomassCounts(5, permanentLive = 0)
          species(1, totalLive = 5, permanentLive = 0)
          substratum(2) {
            biomassCounts(5, permanentLive = 0)
            species(1, totalLive = 5, permanentLive = 0)
            plot(2) {
              biomassCounts(5, permanentLive = 0)
              species(1, totalLive = 5, permanentLive = 0)
            }
            (3L..5L).forEach { plot(it) { biomassCounts(0) } }
          }
        }
      }
    }
  }

  @ParameterizedTest
  @CsvSource(
      "Monitoring, false, true",
      "BiomassMeasurements, false, false",
      "BiomassMeasurements, true, true",
  )
  fun `does not rebuild input counts for excluded observations`(
      type: ObservationType,
      isAdHoc: Boolean,
      needsRecalculation: Boolean,
  ) {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot()
    insertObservation(observationType = ObservationType.BiomassMeasurements)
    insertObservationPlot(statusId = ObservationPlotStatus.Completed)
    insertObservationPlotResult(row = ObservationPlotResultsRow(needsRecalculation = true))
    insertObservationBiomassSpecies(scientificName = "First observation species")
    insertRecordedTree(treeGrowthForm = TreeGrowthForm.Shrub, shrubDiameterCm = 10)

    val later = insertObservation(observationType = type, isAdHoc = isAdHoc)
    insertObservationPlot(statusId = ObservationPlotStatus.Completed)
    insertObservationPlotResult(
        row = ObservationPlotResultsRow(needsRecalculation = needsRecalculation)
    )
    insertObservationSubstratumResult(
        row = ObservationSubstratumResultsRow(needsRecalculation = needsRecalculation)
    )
    insertObservationStratumResult(
        row = ObservationStratumResultsRow(needsRecalculation = needsRecalculation)
    )
    insertObservationSiteResult(
        row = ObservationSiteResultsRow(needsRecalculation = needsRecalculation)
    )
    val speciesId = insertSpecies()
    if (!isAdHoc) {
      insertObservedPlotSpeciesTotals(speciesId = speciesId, totalLive = 42)
    }
    insertObservationBiomassSpecies(speciesId = speciesId)
    insertRecordedTree(treeGrowthForm = TreeGrowthForm.Shrub, shrubDiameterCm = 10)
    val expected =
        dslContext
            .selectFrom(OBSERVED_PLOT_SPECIES_TOTALS)
            .where(OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(later))
            .fetch()

    recalculationStore.recalculateFlaggedResults(plantingSiteId)

    assertTableEquals(
        OBSERVED_PLOT_SPECIES_TOTALS,
        expected,
        message = "Later $type source counts (ad hoc=$isAdHoc, pending=$needsRecalculation)",
        where = OBSERVED_PLOT_SPECIES_TOTALS.OBSERVATION_ID.eq(later),
    )
    if (isAdHoc) {
      assertTableEmpty(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
      assertTableEmpty(OBSERVED_STRATUM_SPECIES_TOTALS)
      assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS)
    }
  }

  private fun ExpectedStoredResultsStep.ExpectedResult.biomassCounts(
      totalLive: Int,
      totalDead: Int = 0,
      permanentLive: Int = totalLive,
  ) {
    counts(totalLive, totalDead, permanentLive = permanentLive)
    expectExactSpecies()
    pending(true)
    noPlantMonitoringMetrics()
  }
}
