package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.embeddables.pojos.ObservationPlotId
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotConditionsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservationPlotsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedStratumSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSubstratumSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.pojos.StratumPopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstratumPopulationsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotConditionsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSitePopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.StratumPopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.SubstratumPopulationsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_STRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBSTRATUM_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.RECORDED_PLANTS
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.PlotAlreadyCompletedException
import com.terraformation.backend.tracking.db.PlotNotInObservationException
import com.terraformation.backend.util.toPlantsPerHectare
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ObservationStoreCompletePlotTest : BaseObservationStoreTest() {
  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId

  @BeforeEach
  fun insertDetailedSiteAndObservation() {
    insertStratum()
    insertSubstratum()
    plotId = insertMonitoringPlot()
    observationId = insertObservation()
    insertObservationRequestedSubstratum()
  }

  @Test
  fun `records plot data`() {
    val speciesId = insertSpecies()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)
    insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)
    insertObservation()
    insertObservationRequestedSubstratum()
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        monitoringPlotId = plotId,
    )

    val initialRows = observationPlotsDao.findAll()

    val observedTime = Instant.ofEpochSecond(1)
    clock.instant = Instant.ofEpochSecond(123)

    val recordedPlants =
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                gpsCoordinates = point(2),
                statusId = RecordedPlantStatus.Dead,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(3),
                speciesName = "Who knows",
                statusId = RecordedPlantStatus.Existing,
            ),
        )

    store.completePlot(
        observationId,
        plotId,
        setOf(ObservableCondition.AnimalDamage, ObservableCondition.FastGrowth),
        "Notes",
        observedTime,
        recordedPlants,
    )

    val expectedConditions =
        setOf(
            ObservationPlotConditionsRow(observationId, plotId, ObservableCondition.AnimalDamage),
            ObservationPlotConditionsRow(observationId, plotId, ObservableCondition.FastGrowth),
        )

    val expectedPlants =
        recordedPlants
            .map { it.copy(monitoringPlotId = plotId, observationId = observationId) }
            .toSet()

    // Verify that only the row for this plot in this observation was updated.
    val expectedRows =
        initialRows
            .map { row ->
              if (row.observationId == observationId && row.monitoringPlotId == plotId) {
                row.copy(
                    completedBy = user.userId,
                    completedTime = clock.instant,
                    notes = "Notes",
                    observedTime = observedTime,
                    statusId = ObservationPlotStatus.Completed,
                )
              } else {
                row
              }
            }
            .toSet()

    assertEquals(expectedConditions, observationPlotConditionsDao.findAll().toSet())
    assertEquals(expectedPlants, recordedPlantsDao.findAll().map { it.copy(id = null) }.toSet())
    assertEquals(expectedRows, observationPlotsDao.findAll().toSet())

    assertEquals(
        observedTime,
        substrataDao.fetchOneById(inserted.substratumId)?.observedTime,
        "Substratum observed time",
    )
  }

  @Test
  fun `updates observed species totals`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val speciesId3 = insertSpecies()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
    insertPlotT0Density(
        speciesId = speciesId1,
        plotDensity = BigDecimal.valueOf(1).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId2,
        plotDensity = BigDecimal.valueOf(2).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId3,
        plotDensity = BigDecimal.valueOf(3).toPlantsPerHectare(),
    )
    val stratumId1 = inserted.stratumId
    val stratum1SubstratumId1 = inserted.substratumId
    val stratum1PlotId2 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = false)
    // excluded densities because not permanent
    insertPlotT0Density(
        speciesId = speciesId1,
        plotDensity = BigDecimal.valueOf(4).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId2,
        plotDensity = BigDecimal.valueOf(5).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId3,
        plotDensity = BigDecimal.valueOf(6).toPlantsPerHectare(),
    )
    val stratumId2 = insertStratum()
    val stratum2SubstratumId1 = insertSubstratum()
    insertObservationRequestedSubstratum()
    val stratum2PlotId1 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
    insertPlotT0Density(
        speciesId = speciesId1,
        plotDensity = BigDecimal.valueOf(7).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId2,
        plotDensity = BigDecimal.valueOf(8).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId3,
        plotDensity = BigDecimal.valueOf(9).toPlantsPerHectare(),
    )

    // We want to verify that the "plants since last observation" numbers aren't reset until all
    // the plots are completed.
    insertMonitoringPlot()
    insertObservationPlot()
    // excluded densities because not permanent
    insertPlotT0Density(
        speciesId = speciesId1,
        plotDensity = BigDecimal.valueOf(10).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId2,
        plotDensity = BigDecimal.valueOf(11).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = speciesId3,
        plotDensity = BigDecimal.valueOf(12).toPlantsPerHectare(),
    )
    insertPlantingSitePopulation(totalPlants = 3, plantsSinceLastObservation = 3)
    insertStratumPopulation(totalPlants = 2, plantsSinceLastObservation = 2)
    insertSubstratumPopulation(totalPlants = 1, plantsSinceLastObservation = 1)

    val observedTime = Instant.ofEpochSecond(1)
    clock.instant = Instant.ofEpochSecond(123)

    store.completePlot(
        observationId,
        plotId,
        emptySet(),
        "Notes",
        observedTime,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Dead,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Existing,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId2,
                statusId = RecordedPlantStatus.Dead,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId3,
                statusId = RecordedPlantStatus.Existing,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(1),
                speciesName = "Other 1",
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(1),
                speciesName = "Other 1",
                statusId = RecordedPlantStatus.Dead,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(1),
                speciesName = "Other 2",
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                gpsCoordinates = point(1),
                statusId = RecordedPlantStatus.Live,
            ),
        ),
    )

    val stratum1Plot1Species1Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId = observationId,
            monitoringPlotId = plotId,
            speciesId = speciesId1,
            speciesName = null,
            certaintyId = RecordedSpeciesCertainty.Known,
            totalLive = 2,
            totalDead = 1,
            totalExisting = 1,
            mortalityRate = 33,
            cumulativeDead = 1,
            permanentLive = 2,
            survivalRate = 2 * 100 / 1,
        )
    // Parameter names omitted after this to keep the test method size manageable.
    val stratum1Plot1Species2Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            plotId,
            speciesId2,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            1,
            0,
            100,
            1,
            0,
            0,
        )
    val stratum1Plot1Species3Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            plotId,
            speciesId3,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
        )
    val stratum1Plot1Other1Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            plotId,
            null,
            "Other 1",
            RecordedSpeciesCertainty.Other,
            1,
            1,
            0,
            50,
            1,
            1,
        )
    val stratum1Plot1Other2Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            plotId,
            null,
            "Other 2",
            RecordedSpeciesCertainty.Other,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    val stratum1Plot1UnknownTotals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            plotId,
            null,
            null,
            RecordedSpeciesCertainty.Unknown,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    var siteSpecies1Totals =
        ObservedSiteSpeciesTotalsRow(
            observationId,
            inserted.plantingSiteId,
            speciesId1,
            null,
            RecordedSpeciesCertainty.Known,
            2,
            1,
            1,
            33,
            1,
            2,
            2 * 100 / 1,
        )
    val siteSpecies2Totals =
        ObservedSiteSpeciesTotalsRow(
            observationId,
            inserted.plantingSiteId,
            speciesId2,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            1,
            0,
            100,
            1,
            0,
            0,
        )
    var siteSpecies3Totals =
        ObservedSiteSpeciesTotalsRow(
            observationId,
            inserted.plantingSiteId,
            speciesId3,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
        )
    var siteOther1Totals =
        ObservedSiteSpeciesTotalsRow(
            observationId,
            plantingSiteId,
            null,
            "Other 1",
            RecordedSpeciesCertainty.Other,
            1,
            1,
            0,
            50,
            1,
            1,
        )
    val siteOther2Totals =
        ObservedSiteSpeciesTotalsRow(
            observationId,
            plantingSiteId,
            null,
            "Other 2",
            RecordedSpeciesCertainty.Other,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    var siteUnknownTotals =
        ObservedSiteSpeciesTotalsRow(
            observationId,
            plantingSiteId,
            null,
            null,
            RecordedSpeciesCertainty.Unknown,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    var stratum1Species1Totals =
        ObservedStratumSpeciesTotalsRow(
            observationId,
            stratumId1,
            speciesId1,
            null,
            RecordedSpeciesCertainty.Known,
            2,
            1,
            1,
            33,
            1,
            2,
            2 * 100 / 1,
        )
    val stratum1Species2Totals =
        ObservedStratumSpeciesTotalsRow(
            observationId,
            stratumId1,
            speciesId2,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            1,
            0,
            100,
            1,
            0,
            0,
        )
    var stratum1Species3Totals =
        ObservedStratumSpeciesTotalsRow(
            observationId,
            stratumId1,
            speciesId3,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
        )
    val stratum1Other1Totals =
        ObservedStratumSpeciesTotalsRow(
            observationId,
            stratumId1,
            null,
            "Other 1",
            RecordedSpeciesCertainty.Other,
            1,
            1,
            0,
            50,
            1,
            1,
        )
    val stratum1Other2Totals =
        ObservedStratumSpeciesTotalsRow(
            observationId,
            stratumId1,
            null,
            "Other 2",
            RecordedSpeciesCertainty.Other,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    var stratum1UnknownTotals =
        ObservedStratumSpeciesTotalsRow(
            observationId,
            stratumId1,
            null,
            null,
            RecordedSpeciesCertainty.Unknown,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    var stratum1Substratum1Species1Totals =
        ObservedSubstratumSpeciesTotalsRow(
            observationId,
            stratum1SubstratumId1,
            speciesId1,
            null,
            RecordedSpeciesCertainty.Known,
            2,
            1,
            1,
            33,
            1,
            2,
            2 * 100 / 1,
        )
    val stratum1Substratum1Species2Totals =
        ObservedSubstratumSpeciesTotalsRow(
            observationId,
            stratum1SubstratumId1,
            speciesId2,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            1,
            0,
            100,
            1,
            0,
            0,
        )
    var stratum1Substratum1Species3Totals =
        ObservedSubstratumSpeciesTotalsRow(
            observationId,
            stratum1SubstratumId1,
            speciesId3,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
        )
    val stratum1Substratum1Other1Totals =
        ObservedSubstratumSpeciesTotalsRow(
            observationId,
            stratum1SubstratumId1,
            null,
            "Other 1",
            RecordedSpeciesCertainty.Other,
            1,
            1,
            0,
            50,
            1,
            1,
        )
    val stratum1Substratum1Other2Totals =
        ObservedSubstratumSpeciesTotalsRow(
            observationId,
            stratum1SubstratumId1,
            null,
            "Other 2",
            RecordedSpeciesCertainty.Other,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    var stratum1Substratum1UnknownTotals =
        ObservedSubstratumSpeciesTotalsRow(
            observationId,
            stratum1SubstratumId1,
            null,
            null,
            RecordedSpeciesCertainty.Unknown,
            1,
            0,
            0,
            0,
            0,
            1,
        )

    helper.assertTotals(
        setOf(
            siteOther1Totals,
            siteOther2Totals,
            siteSpecies1Totals,
            siteSpecies2Totals,
            siteSpecies3Totals,
            siteUnknownTotals,
            stratum1Other1Totals,
            stratum1Other2Totals,
            stratum1Plot1Other1Totals,
            stratum1Plot1Other2Totals,
            stratum1Plot1Species1Totals,
            stratum1Plot1Species2Totals,
            stratum1Plot1Species3Totals,
            stratum1Plot1UnknownTotals,
            stratum1Species1Totals,
            stratum1Species2Totals,
            stratum1Species3Totals,
            stratum1Substratum1Other1Totals,
            stratum1Substratum1Other2Totals,
            stratum1Substratum1Species1Totals,
            stratum1Substratum1Species2Totals,
            stratum1Substratum1Species3Totals,
            stratum1Substratum1UnknownTotals,
            stratum1UnknownTotals,
        ),
        "Totals after first plot completed",
    )

    store.completePlot(
        observationId,
        stratum1PlotId2,
        emptySet(),
        null,
        observedTime,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId3,
                statusId = RecordedPlantStatus.Existing,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                gpsCoordinates = point(1),
                statusId = RecordedPlantStatus.Live,
            ),
        ),
    )

    // no survival rates here because it's not a permanent plot
    val stratum1Plot2Species1Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            stratum1PlotId2,
            speciesId1,
            null,
            RecordedSpeciesCertainty.Known,
            1,
            0,
            0,
            null,
            0,
            0,
        )
    val stratum1Plot2Species3Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            stratum1PlotId2,
            speciesId3,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            0,
            1,
            null,
            0,
            0,
        )
    val stratum1Plot2UnknownTotals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            stratum1PlotId2,
            null,
            null,
            RecordedSpeciesCertainty.Unknown,
            1,
            0,
            0,
            null,
            0,
            0,
        )
    siteSpecies1Totals = siteSpecies1Totals.copy(totalLive = 3)
    siteSpecies3Totals = siteSpecies3Totals.copy(totalExisting = 2)
    siteUnknownTotals = siteUnknownTotals.copy(totalLive = 2)
    stratum1Species1Totals = stratum1Species1Totals.copy(totalLive = 3)
    stratum1Species3Totals = stratum1Species3Totals.copy(totalExisting = 2)
    stratum1UnknownTotals = stratum1UnknownTotals.copy(totalLive = 2)
    stratum1Substratum1Species1Totals = stratum1Substratum1Species1Totals.copy(totalLive = 3)
    stratum1Substratum1Species3Totals = stratum1Substratum1Species3Totals.copy(totalExisting = 2)
    stratum1Substratum1UnknownTotals = stratum1Substratum1UnknownTotals.copy(totalLive = 2)

    helper.assertTotals(
        setOf(
            siteOther1Totals,
            siteOther2Totals,
            siteSpecies1Totals,
            siteSpecies2Totals,
            siteSpecies3Totals,
            siteUnknownTotals,
            stratum1Other1Totals,
            stratum1Other2Totals,
            stratum1Plot1Other1Totals,
            stratum1Plot1Other2Totals,
            stratum1Plot1Species1Totals,
            stratum1Plot1Species2Totals,
            stratum1Plot1Species3Totals,
            stratum1Plot1UnknownTotals,
            stratum1Plot2Species1Totals,
            stratum1Plot2Species3Totals,
            stratum1Plot2UnknownTotals,
            stratum1Species1Totals,
            stratum1Species2Totals,
            stratum1Species3Totals,
            stratum1Substratum1Other1Totals,
            stratum1Substratum1Other2Totals,
            stratum1Substratum1Species1Totals,
            stratum1Substratum1Species2Totals,
            stratum1Substratum1Species3Totals,
            stratum1Substratum1UnknownTotals,
            stratum1UnknownTotals,
        ),
        "Totals after additional live plant recorded",
    )

    store.completePlot(
        observationId,
        stratum2PlotId1,
        emptySet(),
        null,
        observedTime,
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Dead,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Existing,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(1),
                speciesName = "Other 1",
                statusId = RecordedPlantStatus.Live,
            ),
        ),
    )

    val stratum2Plot1Species1Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            stratum2PlotId1,
            speciesId1,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            1,
            1,
            100,
            1,
            0,
            0,
        )
    val stratum2Plot1Other1Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            stratum2PlotId1,
            null,
            "Other 1",
            RecordedSpeciesCertainty.Other,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    val stratum2Substratum1Species1Totals =
        ObservedSubstratumSpeciesTotalsRow(
            observationId,
            stratum2SubstratumId1,
            speciesId1,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            1,
            1,
            100,
            1,
            0,
            0,
        )
    val stratum2Substratum1Other1Totals =
        ObservedSubstratumSpeciesTotalsRow(
            observationId,
            stratum2SubstratumId1,
            null,
            "Other 1",
            RecordedSpeciesCertainty.Other,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    val stratum2Species1Totals =
        ObservedStratumSpeciesTotalsRow(
            observationId,
            stratumId2,
            speciesId1,
            null,
            RecordedSpeciesCertainty.Known,
            0,
            1,
            1,
            100,
            1,
            0,
            0,
        )
    val stratum2Other1Totals =
        ObservedStratumSpeciesTotalsRow(
            observationId,
            stratumId2,
            null,
            "Other 1",
            RecordedSpeciesCertainty.Other,
            1,
            0,
            0,
            0,
            0,
            1,
        )
    siteSpecies1Totals =
        siteSpecies1Totals.copy(
            totalLive = 3,
            totalDead = 2,
            totalExisting = 2,
            mortalityRate = 50,
            cumulativeDead = 2,
            survivalRate = (2 * 100.0 / (1 + 7)).roundToInt(),
        )
    siteOther1Totals = siteOther1Totals.copy(totalLive = 2, mortalityRate = 33, permanentLive = 2)

    helper.assertTotals(
        setOf(
            siteOther1Totals,
            siteOther2Totals,
            siteSpecies1Totals,
            siteSpecies2Totals,
            siteSpecies3Totals,
            siteUnknownTotals,
            stratum1Other1Totals,
            stratum1Other2Totals,
            stratum1Plot1Other1Totals,
            stratum1Plot1Other2Totals,
            stratum1Plot1UnknownTotals,
            stratum1Plot1Species1Totals,
            stratum1Plot1Species2Totals,
            stratum1Plot1Species3Totals,
            stratum1Plot2Species1Totals,
            stratum1Plot2UnknownTotals,
            stratum1Plot2Species3Totals,
            stratum1Species1Totals,
            stratum1Species2Totals,
            stratum1Substratum1Other1Totals,
            stratum1Substratum1Other2Totals,
            stratum1Substratum1Species1Totals,
            stratum1Substratum1Species2Totals,
            stratum1Substratum1Species3Totals,
            stratum1Substratum1UnknownTotals,
            stratum1UnknownTotals,
            stratum1Species3Totals,
            stratum2Other1Totals,
            stratum2Plot1Other1Totals,
            stratum2Plot1Species1Totals,
            stratum2Species1Totals,
            stratum2Substratum1Other1Totals,
            stratum2Substratum1Species1Totals,
        ),
        "Totals after observation in second stratum",
    )

    assertTableEquals(
        PlantingSitePopulationsRecord(plantingSiteId, inserted.speciesId, 3, 3),
        "Planting site total populations should be unchanged",
    )

    assertTableEquals(
        StratumPopulationsRecord(inserted.stratumId, inserted.speciesId, 2, 2),
        "Stratum total populations should be unchanged",
    )

    assertTableEquals(
        SubstratumPopulationsRecord(inserted.substratumId, inserted.speciesId, 1, 1),
        "Substratum total populations should be unchanged",
    )
  }

  @Test
  fun `does not update total plants if plant rows are empty`() {
    insertSpecies()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)

    insertPlantingSitePopulation(totalPlants = 3, plantsSinceLastObservation = 3)
    insertStratumPopulation(totalPlants = 2, plantsSinceLastObservation = 2)
    insertSubstratumPopulation(totalPlants = 1, plantsSinceLastObservation = 1)

    val observedTime = Instant.ofEpochSecond(1)
    clock.instant = Instant.ofEpochSecond(123)

    store.completePlot(
        observationId,
        plotId,
        setOf(ObservableCondition.AnimalDamage, ObservableCondition.FastGrowth),
        "Notes",
        observedTime,
        emptyList(),
    )

    val expectedConditions =
        setOf(
            ObservationPlotConditionsRecord(
                observationId,
                plotId,
                ObservableCondition.AnimalDamage,
            ),
            ObservationPlotConditionsRecord(observationId, plotId, ObservableCondition.FastGrowth),
        )

    val newRow =
        observationPlotsDao
            .fetchByObservationPlotId(
                ObservationPlotId(inserted.observationId, inserted.monitoringPlotId)
            )
            .single()
            .copy(
                notes = "Notes",
                observedTime = observedTime,
                statusId = ObservationPlotStatus.Completed,
            )

    assertTableEquals(ObservationPlotsRecord(newRow), "Updated observation plot entry")
    assertTableEquals(expectedConditions, "Inserted observation plot conditions")
    assertTableEmpty(RECORDED_PLANTS, "No plants recorded")

    assertTableEmpty(OBSERVED_PLOT_SPECIES_TOTALS, "Observed plot species should be empty")
    assertTableEmpty(
        OBSERVED_SUBSTRATUM_SPECIES_TOTALS,
        "Observed substratum species should be empty",
    )
    assertTableEmpty(OBSERVED_STRATUM_SPECIES_TOTALS, "Observed stratum species should be empty")
    assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS, "Observed site species should be empty")

    assertTableEquals(
        PlantingSitePopulationsRecord(plantingSiteId, inserted.speciesId, 3, 0),
        "Planting site total plants should be unchanged",
    )

    assertTableEquals(
        StratumPopulationsRecord(inserted.stratumId, inserted.speciesId, 2, 0),
        "Stratum total plants should be unchanged",
    )

    assertTableEquals(
        SubstratumPopulationsRecord(inserted.substratumId, inserted.speciesId, 1, 0),
        "Substratum total plants should be unchanged",
    )
  }

  @Test
  fun `updates cumulative dead from initial values inserted by populateCumulativeDead`() {
    val speciesId = insertSpecies()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    val deadPlantsRow =
        RecordedPlantsRow(
            certaintyId = RecordedSpeciesCertainty.Known,
            gpsCoordinates = point(1),
            speciesId = speciesId,
            statusId = RecordedPlantStatus.Dead,
        )
    store.completePlot(
        observationId,
        plotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(deadPlantsRow),
    )

    val observationId2 = insertObservation()
    insertObservationRequestedSubstratum()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    store.populateCumulativeDead(observationId2)

    store.completePlot(
        observationId2,
        plotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(deadPlantsRow),
    )

    assertEquals(
        2,
        with(OBSERVED_PLOT_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Plot cumulative dead for second observation",
    )
    assertEquals(
        2,
        with(OBSERVED_STRATUM_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Stratum cumulative dead for second observation",
    )
    assertEquals(
        2,
        with(OBSERVED_SITE_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Site cumulative dead for second observation",
    )
  }

  // SW-6717: This can happen if all of a substratum's monitoring plots move to a new substratum
  //          thanks to a map edit; the original substratum will have substratum-level species
  //          totals but we don't want to use them as a starting point for a new observation since
  //          there are no monitoring plots in common.
  @Test
  fun `does not use cumulative dead from past observations if current observation has no total for a species`() {
    val speciesId = insertSpecies()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    val deadPlantsRow =
        RecordedPlantsRow(
            certaintyId = RecordedSpeciesCertainty.Known,
            gpsCoordinates = point(1),
            speciesId = speciesId,
            statusId = RecordedPlantStatus.Dead,
        )
    store.completePlot(
        observationId,
        plotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(deadPlantsRow),
    )

    val observationId2 = insertObservation()
    insertObservationRequestedSubstratum()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    // We do not call populateCumulativeDead here, so there is no observed substratum species
    // total for this observation even though there's one for the previous observation.

    store.completePlot(
        observationId2,
        plotId,
        emptySet(),
        null,
        Instant.EPOCH,
        listOf(deadPlantsRow),
    )

    assertEquals(
        2,
        with(OBSERVED_PLOT_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Plot cumulative dead for second observation",
    )
    assertEquals(
        1,
        with(OBSERVED_SUBSTRATUM_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Substratum cumulative dead for second observation",
    )
    assertEquals(
        1,
        with(OBSERVED_STRATUM_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Stratum cumulative dead for second observation",
    )
    assertEquals(
        1,
        with(OBSERVED_SITE_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Site cumulative dead for second observation",
    )
  }

  @Test
  fun `marks observation as completed if this was the last incomplete plot`() {
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)

    val speciesId = insertSpecies()
    insertPlantingSitePopulation(totalPlants = 3, plantsSinceLastObservation = 3)
    insertStratumPopulation(totalPlants = 2, plantsSinceLastObservation = 2)
    insertSubstratumPopulation(totalPlants = 1, plantsSinceLastObservation = 1)

    clock.instant = Instant.ofEpochSecond(123)
    store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())

    val observation = store.fetchObservationById(observationId)

    assertEquals(ObservationState.Completed, observation.state, "Observation state")
    assertEquals(clock.instant, observation.completedTime, "Completed time")

    assertEquals(
        listOf(PlantingSitePopulationsRow(plantingSiteId, speciesId, 3, 0)),
        plantingSitePopulationsDao.findAll(),
        "Planting site plants since last observation should have been reset",
    )

    assertEquals(
        listOf(StratumPopulationsRow(inserted.stratumId, speciesId, 2, 0)),
        stratumPopulationsDao.findAll(),
        "Stratum plants since last observation should have been reset",
    )

    assertEquals(
        listOf(SubstratumPopulationsRow(inserted.substratumId, speciesId, 1, 0)),
        substratumPopulationsDao.findAll(),
        "Substratum plants since last observation should have been reset",
    )
  }

  @Test
  fun `throws exception if plot was already completed`() {
    insertObservationPlot(
        ObservationPlotsRow(
            claimedBy = user.userId,
            claimedTime = Instant.EPOCH,
            completedBy = user.userId,
            completedTime = Instant.EPOCH,
            observedTime = Instant.EPOCH,
            statusId = ObservationPlotStatus.Completed,
        )
    )

    assertThrows<PlotAlreadyCompletedException> {
      store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())
    }
  }

  @Test
  fun `throws exception if no permission to update observation`() {
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)

    every { user.canUpdateObservation(observationId) } returns false

    assertThrows<AccessDeniedException> {
      store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())
    }
  }

  @Test
  fun `throws exception if monitoring plot not assigned to observation`() {
    assertThrows<PlotNotInObservationException> {
      store.completePlot(observationId, plotId, emptySet(), null, Instant.EPOCH, emptyList())
    }
  }
}
