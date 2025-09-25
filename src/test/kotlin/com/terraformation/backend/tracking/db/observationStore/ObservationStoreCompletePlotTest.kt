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
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSubzoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSubzonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonePopulationsRow
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotConditionsRecord
import com.terraformation.backend.db.tracking.tables.records.ObservationPlotsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSitePopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingSubzonePopulationsRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingZonePopulationsRecord
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
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
    insertPlantingZone()
    insertPlantingSubzone()
    plotId = insertMonitoringPlot()
    observationId = insertObservation()
  }

  @Test
  fun `records plot data`() {
    val speciesId = insertSpecies()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)
    insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH)
    insertObservation()
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
        plantingSubzonesDao.fetchOneById(inserted.plantingSubzoneId)?.observedTime,
        "Subzone observed time",
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
    val zoneId1 = inserted.plantingZoneId
    val zone1SubzoneId1 = inserted.plantingSubzoneId
    val zone1PlotId2 = insertMonitoringPlot()
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
    val zoneId2 = insertPlantingZone()
    val zone2SubzoneId1 = insertPlantingSubzone()
    val zone2PlotId1 = insertMonitoringPlot()
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
    insertPlantingZonePopulation(totalPlants = 2, plantsSinceLastObservation = 2)
    insertPlantingSubzonePopulation(totalPlants = 1, plantsSinceLastObservation = 1)

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

    val zone1Plot1Species1Totals =
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
    val zone1Plot1Species2Totals =
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
    val zone1Plot1Species3Totals =
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
    val zone1Plot1Other1Totals =
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
    val zone1Plot1Other2Totals =
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
    val zone1Plot1UnknownTotals =
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
    var zone1Species1Totals =
        ObservedZoneSpeciesTotalsRow(
            observationId,
            zoneId1,
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
    val zone1Species2Totals =
        ObservedZoneSpeciesTotalsRow(
            observationId,
            zoneId1,
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
    var zone1Species3Totals =
        ObservedZoneSpeciesTotalsRow(
            observationId,
            zoneId1,
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
    val zone1Other1Totals =
        ObservedZoneSpeciesTotalsRow(
            observationId,
            zoneId1,
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
    val zone1Other2Totals =
        ObservedZoneSpeciesTotalsRow(
            observationId,
            zoneId1,
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
    var zone1UnknownTotals =
        ObservedZoneSpeciesTotalsRow(
            observationId,
            zoneId1,
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
    var zone1Subzone1Species1Totals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId,
            zone1SubzoneId1,
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
    val zone1Subzone1Species2Totals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId,
            zone1SubzoneId1,
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
    var zone1Subzone1Species3Totals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId,
            zone1SubzoneId1,
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
    val zone1Subzone1Other1Totals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId,
            zone1SubzoneId1,
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
    val zone1Subzone1Other2Totals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId,
            zone1SubzoneId1,
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
    var zone1Subzone1UnknownTotals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId,
            zone1SubzoneId1,
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
            zone1Other1Totals,
            zone1Other2Totals,
            zone1Plot1Other1Totals,
            zone1Plot1Other2Totals,
            zone1Plot1Species1Totals,
            zone1Plot1Species2Totals,
            zone1Plot1Species3Totals,
            zone1Plot1UnknownTotals,
            zone1Species1Totals,
            zone1Species2Totals,
            zone1Species3Totals,
            zone1Subzone1Other1Totals,
            zone1Subzone1Other2Totals,
            zone1Subzone1Species1Totals,
            zone1Subzone1Species2Totals,
            zone1Subzone1Species3Totals,
            zone1Subzone1UnknownTotals,
            zone1UnknownTotals,
        ),
        "Totals after first plot completed",
    )

    store.completePlot(
        observationId,
        zone1PlotId2,
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
    val zone1Plot2Species1Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            zone1PlotId2,
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
    val zone1Plot2Species3Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            zone1PlotId2,
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
    val zone1Plot2UnknownTotals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            zone1PlotId2,
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
    zone1Species1Totals = zone1Species1Totals.copy(totalLive = 3)
    zone1Species3Totals = zone1Species3Totals.copy(totalExisting = 2)
    zone1UnknownTotals = zone1UnknownTotals.copy(totalLive = 2)
    zone1Subzone1Species1Totals = zone1Subzone1Species1Totals.copy(totalLive = 3)
    zone1Subzone1Species3Totals = zone1Subzone1Species3Totals.copy(totalExisting = 2)
    zone1Subzone1UnknownTotals = zone1Subzone1UnknownTotals.copy(totalLive = 2)

    helper.assertTotals(
        setOf(
            siteOther1Totals,
            siteOther2Totals,
            siteSpecies1Totals,
            siteSpecies2Totals,
            siteSpecies3Totals,
            siteUnknownTotals,
            zone1Other1Totals,
            zone1Other2Totals,
            zone1Plot1Other1Totals,
            zone1Plot1Other2Totals,
            zone1Plot1Species1Totals,
            zone1Plot1Species2Totals,
            zone1Plot1Species3Totals,
            zone1Plot1UnknownTotals,
            zone1Plot2Species1Totals,
            zone1Plot2Species3Totals,
            zone1Plot2UnknownTotals,
            zone1Species1Totals,
            zone1Species2Totals,
            zone1Species3Totals,
            zone1Subzone1Other1Totals,
            zone1Subzone1Other2Totals,
            zone1Subzone1Species1Totals,
            zone1Subzone1Species2Totals,
            zone1Subzone1Species3Totals,
            zone1Subzone1UnknownTotals,
            zone1UnknownTotals,
        ),
        "Totals after additional live plant recorded",
    )

    store.completePlot(
        observationId,
        zone2PlotId1,
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

    val zone2Plot1Species1Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            zone2PlotId1,
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
    val zone2Plot1Other1Totals =
        ObservedPlotSpeciesTotalsRow(
            observationId,
            zone2PlotId1,
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
    val zone2Subzone1Species1Totals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId,
            zone2SubzoneId1,
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
    val zone2Subzone1Other1Totals =
        ObservedSubzoneSpeciesTotalsRow(
            observationId,
            zone2SubzoneId1,
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
    val zone2Species1Totals =
        ObservedZoneSpeciesTotalsRow(
            observationId,
            zoneId2,
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
    val zone2Other1Totals =
        ObservedZoneSpeciesTotalsRow(
            observationId,
            zoneId2,
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
            zone1Other1Totals,
            zone1Other2Totals,
            zone1Plot1Other1Totals,
            zone1Plot1Other2Totals,
            zone1Plot1UnknownTotals,
            zone1Plot1Species1Totals,
            zone1Plot1Species2Totals,
            zone1Plot1Species3Totals,
            zone1Plot2Species1Totals,
            zone1Plot2UnknownTotals,
            zone1Plot2Species3Totals,
            zone1Species1Totals,
            zone1Species2Totals,
            zone1Subzone1Other1Totals,
            zone1Subzone1Other2Totals,
            zone1Subzone1Species1Totals,
            zone1Subzone1Species2Totals,
            zone1Subzone1Species3Totals,
            zone1Subzone1UnknownTotals,
            zone1UnknownTotals,
            zone1Species3Totals,
            zone2Other1Totals,
            zone2Plot1Other1Totals,
            zone2Plot1Species1Totals,
            zone2Species1Totals,
            zone2Subzone1Other1Totals,
            zone2Subzone1Species1Totals,
        ),
        "Totals after observation in second zone",
    )

    assertTableEquals(
        PlantingSitePopulationsRecord(plantingSiteId, inserted.speciesId, 3, 3),
        "Planting site total populations should be unchanged",
    )

    assertTableEquals(
        PlantingZonePopulationsRecord(inserted.plantingZoneId, inserted.speciesId, 2, 2),
        "Planting zone total populations should be unchanged",
    )

    assertTableEquals(
        PlantingSubzonePopulationsRecord(inserted.plantingSubzoneId, inserted.speciesId, 1, 1),
        "Planting subzone total populations should be unchanged",
    )
  }

  @Test
  fun `does not update total plants if plant rows are empty`() {
    insertSpecies()
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)

    insertPlantingSitePopulation(totalPlants = 3, plantsSinceLastObservation = 3)
    insertPlantingZonePopulation(totalPlants = 2, plantsSinceLastObservation = 2)
    insertPlantingSubzonePopulation(totalPlants = 1, plantsSinceLastObservation = 1)

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
    assertTableEmpty(OBSERVED_SUBZONE_SPECIES_TOTALS, "Observed subzone species should be empty")
    assertTableEmpty(OBSERVED_ZONE_SPECIES_TOTALS, "Observed zone species should be empty")
    assertTableEmpty(OBSERVED_SITE_SPECIES_TOTALS, "Observed site species should be empty")

    assertTableEquals(
        PlantingSitePopulationsRecord(plantingSiteId, inserted.speciesId, 3, 0),
        "Planting site total plants should be unchanged",
    )

    assertTableEquals(
        PlantingZonePopulationsRecord(inserted.plantingZoneId, inserted.speciesId, 2, 0),
        "Planting zone total plants should be unchanged",
    )

    assertTableEquals(
        PlantingSubzonePopulationsRecord(inserted.plantingSubzoneId, inserted.speciesId, 1, 0),
        "Planting subzone total plants should be unchanged",
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
        with(OBSERVED_ZONE_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Zone cumulative dead for second observation",
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

  // SW-6717: This can happen if all of a subzone's monitoring plots move to a new subzone
  //          thanks to a map edit; the original subzone will have subzone-level species totals
  //          but we don't want to use them as a starting point for a new observation since
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
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)

    // We do not call populateCumulativeDead here, so there is no observed subzone species
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
        with(OBSERVED_SUBZONE_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Subzone cumulative dead for second observation",
    )
    assertEquals(
        1,
        with(OBSERVED_ZONE_SPECIES_TOTALS) {
          dslContext
              .select(CUMULATIVE_DEAD)
              .from(this)
              .where(OBSERVATION_ID.eq(observationId2))
              .fetchOne(CUMULATIVE_DEAD)
        },
        "Zone cumulative dead for second observation",
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
    insertPlantingZonePopulation(totalPlants = 2, plantsSinceLastObservation = 2)
    insertPlantingSubzonePopulation(totalPlants = 1, plantsSinceLastObservation = 1)

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
        listOf(PlantingZonePopulationsRow(inserted.plantingZoneId, speciesId, 2, 0)),
        plantingZonePopulationsDao.findAll(),
        "Planting zone plants since last observation should have been reset",
    )

    assertEquals(
        listOf(PlantingSubzonePopulationsRow(inserted.plantingSubzoneId, speciesId, 1, 0)),
        plantingSubzonePopulationsDao.findAll(),
        "Planting subzone plants since last observation should have been reset",
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
