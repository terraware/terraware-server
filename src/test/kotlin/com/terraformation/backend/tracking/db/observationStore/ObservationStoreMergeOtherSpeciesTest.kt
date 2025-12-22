package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservedPlotSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedSiteSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedStratumSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservedSubstratumSpeciesTotalsRecord
import com.terraformation.backend.db.tracking.tables.records.RecordedPlantsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.point
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.roundToInt
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class ObservationStoreMergeOtherSpeciesTest : BaseObservationStoreTest() {
  private lateinit var stratumId: StratumId
  private lateinit var monitoringPlotId: MonitoringPlotId

  @BeforeEach
  fun insertDetailedPlantingSite() {
    stratumId = insertPlantingZone()
    insertPlantingSubzone()
    monitoringPlotId = insertMonitoringPlot()

    every { user.canUpdateSpecies(any()) } returns true
  }

  @Test
  fun `updates raw recorded plants data`() {
    val gpsCoordinates = point(1)
    val speciesId = insertSpecies()

    val observationId1 = insertObservation()
    insertObservationRequestedSubzone()
    insertObservationPlot()
    insertRecordedPlant(speciesName = "Species to merge", gpsCoordinates = gpsCoordinates)
    insertRecordedPlant(speciesName = "Other species", gpsCoordinates = gpsCoordinates)

    val observationId2 = insertObservation()
    insertObservationRequestedSubzone()
    insertObservationPlot()
    insertRecordedPlant(speciesName = "Species to merge", gpsCoordinates = gpsCoordinates)

    store.mergeOtherSpeciesForMonitoring(
        observationId1,
        plantingSiteId,
        false,
        "Species to merge",
        speciesId,
    )

    assertTableEquals(
        listOf(
            RecordedPlantsRecord(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = gpsCoordinates,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId1,
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRecord(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId1,
                speciesName = "Other species",
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRecord(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                monitoringPlotId = monitoringPlotId,
                observationId = observationId2,
                speciesName = "Species to merge",
                statusId = RecordedPlantStatus.Live,
            ),
        )
    )
  }

  @Test
  fun `updates observed species totals`() {
    val gpsCoordinates = point(1)
    val speciesId = insertSpecies()
    insertPlotT0Density(plotDensity = BigDecimal.valueOf(10).toPlantsPerHectare())

    val observationId1 = insertObservation()
    insertObservationRequestedSubzone()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    store.completePlot(
        observationId1,
        monitoringPlotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = gpsCoordinates,
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Dead,
            ),
        ),
    )

    clock.instant = Instant.ofEpochSecond(1)

    val observationId2 = insertObservation()
    insertObservationRequestedSubzone()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    store.populateCumulativeDead(observationId2)

    store.completePlot(
        observationId2,
        monitoringPlotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = gpsCoordinates,
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Dead,
            ),
        ),
    )

    val expectedPlotsBeforeMerge =
        listOf(
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId1,
                monitoringPlotId = monitoringPlotId,
                speciesId = speciesId,
                speciesName = null,
                certaintyId = RecordedSpeciesCertainty.Known,
                totalLive = 1,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = 0,
                cumulativeDead = 0,
                permanentLive = 1,
                survivalRate = (1 * 100.0 / 10).roundToInt(),
            ),
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId1,
                monitoringPlotId = monitoringPlotId,
                speciesId = null,
                speciesName = "Merge",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 1,
                totalDead = 1,
                totalExisting = 0,
                mortalityRate = 50,
                cumulativeDead = 1,
                permanentLive = 1,
            ),
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId2,
                monitoringPlotId = monitoringPlotId,
                speciesId = speciesId,
                speciesName = null,
                certaintyId = RecordedSpeciesCertainty.Known,
                totalLive = 1,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = 0,
                cumulativeDead = 0,
                permanentLive = 1,
                survivalRate = (1 * 100.0 / 10).roundToInt(),
            ),
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId2,
                monitoringPlotId = monitoringPlotId,
                speciesId = null,
                speciesName = "Merge",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 1,
                totalDead = 1,
                totalExisting = 0,
                mortalityRate = 67,
                cumulativeDead = 2,
                permanentLive = 1,
            ),
        )

    assertTableEquals(expectedPlotsBeforeMerge, "Before merge")
    assertTableEquals(expectedPlotsBeforeMerge.map { it.toSubstratum() }, "Before merge")
    assertTableEquals(expectedPlotsBeforeMerge.map { it.toStratum() }, "Before merge")
    assertTableEquals(expectedPlotsBeforeMerge.map { it.toSite() }, "Before merge")

    store.mergeOtherSpeciesForMonitoring(observationId1, plantingSiteId, false, "Merge", speciesId)

    val expectedPlotsAfterMerge =
        listOf(
            expectedPlotsBeforeMerge[0].apply {
              totalLive = 2
              totalDead = 1
              cumulativeDead = 1
              permanentLive = 2
              mortalityRate = 33
              survivalRate = (2 * 100.0 / 10).roundToInt()
            },
            // expectedPlotsBeforeMerge[1] should be deleted
            expectedPlotsBeforeMerge[2].apply {
              cumulativeDead = 1
              mortalityRate = 50
            },
            expectedPlotsBeforeMerge[3].apply {
              cumulativeDead = 1
              mortalityRate = 50
            },
        )

    assertTableEquals(expectedPlotsAfterMerge, "After merge")
    assertTableEquals(expectedPlotsAfterMerge.map { it.toSubstratum() }, "After merge")
    assertTableEquals(expectedPlotsAfterMerge.map { it.toStratum() }, "After merge")
    assertTableEquals(expectedPlotsAfterMerge.map { it.toSite() }, "After merge")
  }

  @Test
  fun `does not update stratum or site species totals for ad-hoc observation`() {
    val gpsCoordinates = point(1)
    val speciesId = insertSpecies()

    monitoringPlotId = insertMonitoringPlot(plantingSubzoneId = null, isAdHoc = true)
    val observationId1 = insertObservation(isAdHoc = true)
    insertObservationRequestedSubzone()
    insertObservationPlot(claimedBy = user.userId, isPermanent = false)

    store.completePlot(
        observationId1,
        monitoringPlotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = gpsCoordinates,
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = gpsCoordinates,
                speciesName = "Merge",
                statusId = RecordedPlantStatus.Dead,
            ),
        ),
    )

    clock.instant = Instant.ofEpochSecond(1)

    val expectedPlotsBeforeMerge =
        listOf(
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId1,
                monitoringPlotId = monitoringPlotId,
                speciesId = speciesId,
                speciesName = null,
                certaintyId = RecordedSpeciesCertainty.Known,
                totalLive = 1,
                totalDead = 0,
                totalExisting = 0,
                mortalityRate = null,
                cumulativeDead = 0,
                permanentLive = 0,
            ),
            ObservedPlotSpeciesTotalsRecord(
                observationId = observationId1,
                monitoringPlotId = monitoringPlotId,
                speciesId = null,
                speciesName = "Merge",
                certaintyId = RecordedSpeciesCertainty.Other,
                totalLive = 1,
                totalDead = 1,
                totalExisting = 0,
                mortalityRate = null,
                cumulativeDead = 0,
                permanentLive = 0,
            ),
        )

    assertTableEquals(expectedPlotsBeforeMerge, "Before merge")
    assertTableEmpty(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
    assertTableEmpty(OBSERVED_STRATUM_SPECIES_TOTALS)
    assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS)

    store.mergeOtherSpeciesForMonitoring(observationId1, plantingSiteId, true, "Merge", speciesId)

    val expectedPlotsAfterMerge =
        listOf(
            expectedPlotsBeforeMerge[0].apply {
              totalLive = 2
              totalDead = 1
            },
            // expectedPlotsBeforeMerge[1] should be deleted
        )

    assertTableEquals(expectedPlotsAfterMerge, "After merge")
    assertTableEmpty(OBSERVED_SUBSTRATUM_SPECIES_TOTALS)
    assertTableEmpty(OBSERVED_STRATUM_SPECIES_TOTALS)
    assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS)
  }

  private fun ObservedPlotSpeciesTotalsRecord.toSubstratum(
      substratumId: SubstratumId = inserted.plantingSubzoneId
  ) =
      ObservedSubstratumSpeciesTotalsRecord(
          observationId = observationId,
          substratumId = substratumId,
          speciesId = speciesId,
          speciesName = speciesName,
          certaintyId = certaintyId,
          totalLive = totalLive,
          totalDead = totalDead,
          totalExisting = totalExisting,
          mortalityRate = mortalityRate,
          cumulativeDead = cumulativeDead,
          permanentLive = permanentLive,
          survivalRate = survivalRate,
      )

  private fun ObservedPlotSpeciesTotalsRecord.toStratum(
      stratumId: StratumId = inserted.plantingZoneId
  ) =
      ObservedStratumSpeciesTotalsRecord(
          observationId = observationId,
          stratumId = stratumId,
          speciesId = speciesId,
          speciesName = speciesName,
          certaintyId = certaintyId,
          totalLive = totalLive,
          totalDead = totalDead,
          totalExisting = totalExisting,
          mortalityRate = mortalityRate,
          cumulativeDead = cumulativeDead,
          permanentLive = permanentLive,
          survivalRate = survivalRate,
      )

  private fun ObservedPlotSpeciesTotalsRecord.toSite(
      plantingSiteId: PlantingSiteId = inserted.plantingSiteId
  ) =
      ObservedSiteSpeciesTotalsRecord(
          observationId = observationId,
          plantingSiteId = plantingSiteId,
          speciesId = speciesId,
          speciesName = speciesName,
          certaintyId = certaintyId,
          totalLive = totalLive,
          totalDead = totalDead,
          totalExisting = totalExisting,
          mortalityRate = mortalityRate,
          cumulativeDead = cumulativeDead,
          permanentLive = permanentLive,
          survivalRate = survivalRate,
      )
}
