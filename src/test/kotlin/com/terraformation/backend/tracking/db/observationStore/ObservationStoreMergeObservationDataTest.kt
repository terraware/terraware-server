package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_PLOT_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SUBSTRATUM_RESULTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.point
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationStoreMergeObservationDataTest : BaseObservationStoreTest() {
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUpSite() {
    helper.insertPlantedSite(numPermanentPlots = 2)
    speciesId = inserted.speciesId
  }

  @Test
  fun `recalculateObservationTotals reproduces the totals from scratch`() {
    val observationId = insertObservation()
    val plotId1 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId)
    val plotId2 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId)
    val speciesId2 = insertSpecies()

    store.completePlot(
        observationId,
        plotId1,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(livePlant(), deadPlant()),
    )

    store.completePlot(
        observationId,
        plotId2,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(livePlant(speciesId), livePlant(speciesId2), livePlant(speciesId2)),
    )

    val expectedTotals = helper.fetchAllTotals()
    val expectedResults = helper.fetchAllResults()

    dslContext
        .update(OBSERVED_SITE_SPECIES_TOTALS)
        .set(OBSERVED_SITE_SPECIES_TOTALS.TOTAL_LIVE, 9999)
        .execute()
    dslContext
        .update(OBSERVED_STRATUM_SPECIES_TOTALS)
        .set(OBSERVED_STRATUM_SPECIES_TOTALS.TOTAL_LIVE, 8888)
        .execute()
    dslContext
        .update(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
        .set(OBSERVED_SUBSTRATUM_SPECIES_TOTALS.TOTAL_LIVE, 7777)
        .execute()
    dslContext
        .update(OBSERVED_PLOT_SPECIES_TOTALS)
        .set(OBSERVED_PLOT_SPECIES_TOTALS.TOTAL_LIVE, 6666)
        .execute()
    dslContext
        .update(OBSERVATION_SITE_RESULTS)
        .set(OBSERVATION_SITE_RESULTS.TOTAL_LIVE, 9898)
        .execute()
    dslContext
        .update(OBSERVATION_STRATUM_RESULTS)
        .set(OBSERVATION_STRATUM_RESULTS.TOTAL_LIVE, 8787)
        .execute()
    dslContext
        .update(OBSERVATION_SUBSTRATUM_RESULTS)
        .set(OBSERVATION_SUBSTRATUM_RESULTS.TOTAL_LIVE, 7676)
        .execute()
    dslContext
        .update(OBSERVATION_PLOT_RESULTS)
        .set(OBSERVATION_PLOT_RESULTS.TOTAL_LIVE, 6565)
        .execute()

    store.recalculateObservationTotals(observationId)

    assertEquals(expectedTotals, helper.fetchAllTotals(), "Species totals after rebuild")
    assertEquals(expectedResults, helper.fetchAllResults(), "Observation results after rebuild")
  }

  private fun livePlant(speciesId: SpeciesId = this.speciesId) =
      RecordedPlantsRow(
          certaintyId = RecordedSpeciesCertainty.Known,
          gpsCoordinates = point(1),
          speciesId = speciesId,
          statusId = RecordedPlantStatus.Live,
      )

  private fun deadPlant(speciesId: SpeciesId = this.speciesId) =
      RecordedPlantsRow(
          certaintyId = RecordedSpeciesCertainty.Known,
          gpsCoordinates = point(1),
          speciesId = speciesId,
          statusId = RecordedPlantStatus.Dead,
      )
}
