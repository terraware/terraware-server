package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Known
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty.Other
import com.terraformation.backend.db.tracking.tables.pojos.ObservedPlotSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSiteSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedSubzoneSpeciesTotalsRow
import com.terraformation.backend.db.tracking.tables.pojos.ObservedZoneSpeciesTotalsRow
import com.terraformation.backend.tracking.db.ObservationTestHelper.ObservationPlot
import com.terraformation.backend.tracking.db.ObservationTestHelper.ObservationZone
import com.terraformation.backend.tracking.db.ObservationTestHelper.PlantTotals
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ObservationStoreRemovePlotFromTotalsTest : BaseObservationStoreTest() {
  @Test
  fun `updates observed species totals across all observations`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val speciesId3 = insertSpecies()

    val zoneId1 = insertPlantingZone()
    val zone1SubzoneId1 = insertPlantingSubzone()
    val zone1PlotId1 = insertMonitoringPlot()
    val zone1PlotId2 = insertMonitoringPlot()
    val zoneId2 = insertPlantingZone()
    val zone2SubzoneId1 = insertPlantingSubzone()
    val zone2PlotId1 = insertMonitoringPlot()

    val observationId1 = insertObservation(completedTime = Instant.ofEpochSecond(1))

    helper.insertObservationScenario(
        ObservationZone(
            zoneId = zoneId1,
            plots =
                listOf(
                    ObservationPlot(
                        zone1PlotId1,
                        listOf(
                            PlantTotals(speciesId1, live = 2, dead = 1, existing = 1),
                            PlantTotals(speciesId2, live = 1),
                            PlantTotals("fern", live = 1, dead = 1),
                        ),
                    ),
                    ObservationPlot(
                        zone1PlotId2,
                        listOf(
                            PlantTotals(speciesId2, live = 1, dead = 1),
                        ),
                    ),
                ),
        ),
        ObservationZone(
            zoneId = zoneId2,
            plots =
                listOf(
                    ObservationPlot(
                        zone2PlotId1,
                        listOf(
                            PlantTotals(speciesId1, live = 1),
                            PlantTotals(speciesId3, live = 1),
                        ),
                    )
                ),
        ),
    )

    val observationId2 = insertObservation(completedTime = Instant.ofEpochSecond(2))

    helper.insertObservationScenario(
        ObservationZone(
            zoneId = zoneId1,
            plots =
                listOf(
                    ObservationPlot(
                        zone1PlotId1,
                        listOf(
                            PlantTotals(speciesId1, live = 1, dead = 1, existing = 1),
                            PlantTotals(speciesId2, live = 1),
                            PlantTotals("fern", live = 2),
                        ),
                    ),
                    ObservationPlot(
                        zone1PlotId2,
                        listOf(
                            PlantTotals(speciesId2, live = 1),
                        ),
                    ),
                ),
        )
    )

    store.removePlotFromTotals(zone1PlotId1)

    helper.assertTotals(
        setOf(
            ObservedPlotSpeciesTotalsRow(
                observationId = observationId1,
                monitoringPlotId = zone1PlotId1,
                speciesId = speciesId1,
                speciesName = null,
                certaintyId = Known,
                totalLive = 2,
                totalDead = 1,
                totalExisting = 1,
                mortalityRate = 33,
                cumulativeDead = 1,
                permanentLive = 2,
            ),
            // Parameter names omitted after this to keep the test method size manageable.
            ObservedPlotSpeciesTotalsRow(
                observationId1,
                zone1PlotId1,
                speciesId2,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedPlotSpeciesTotalsRow(
                observationId1,
                zone1PlotId1,
                null,
                "fern",
                Other,
                1,
                1,
                0,
                50,
                1,
                1,
            ),
            ObservedPlotSpeciesTotalsRow(
                observationId1,
                zone1PlotId2,
                speciesId2,
                null,
                Known,
                1,
                1,
                0,
                50,
                1,
                1,
            ),
            ObservedPlotSpeciesTotalsRow(
                observationId1,
                zone2PlotId1,
                speciesId1,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedPlotSpeciesTotalsRow(
                observationId1,
                zone2PlotId1,
                speciesId3,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId1,
                zone1SubzoneId1,
                speciesId1,
                null,
                Known,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId1,
                zone1SubzoneId1,
                speciesId2,
                null,
                Known,
                1,
                1,
                0,
                50,
                1,
                1,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId1,
                zone1SubzoneId1,
                null,
                "fern",
                Other,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId1,
                zone2SubzoneId1,
                speciesId1,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId1,
                zone2SubzoneId1,
                speciesId3,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId1,
                zoneId1,
                speciesId1,
                null,
                Known,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId1,
                zoneId1,
                speciesId2,
                null,
                Known,
                1,
                1,
                0,
                50,
                1,
                1,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId1,
                zoneId1,
                null,
                "fern",
                Other,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId1,
                zoneId2,
                speciesId1,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId1,
                zoneId2,
                speciesId3,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedSiteSpeciesTotalsRow(
                observationId1,
                plantingSiteId,
                speciesId1,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedSiteSpeciesTotalsRow(
                observationId1,
                plantingSiteId,
                speciesId2,
                null,
                Known,
                1,
                1,
                0,
                50,
                1,
                1,
            ),
            ObservedSiteSpeciesTotalsRow(
                observationId1,
                plantingSiteId,
                speciesId3,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedSiteSpeciesTotalsRow(
                observationId1,
                plantingSiteId,
                null,
                "fern",
                Other,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedPlotSpeciesTotalsRow(
                observationId2,
                zone1PlotId1,
                speciesId1,
                null,
                Known,
                1,
                1,
                1,
                67,
                2,
                1,
            ),
            ObservedPlotSpeciesTotalsRow(
                observationId2,
                zone1PlotId1,
                speciesId2,
                null,
                Known,
                1,
                0,
                0,
                0,
                0,
                1,
            ),
            ObservedPlotSpeciesTotalsRow(
                observationId2,
                zone1PlotId1,
                null,
                "fern",
                Other,
                2,
                0,
                0,
                33,
                1,
                2,
            ),
            ObservedPlotSpeciesTotalsRow(
                observationId2,
                zone1PlotId2,
                speciesId2,
                null,
                Known,
                1,
                0,
                0,
                50,
                1,
                1,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId2,
                zone1SubzoneId1,
                speciesId1,
                null,
                Known,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId2,
                zone1SubzoneId1,
                speciesId2,
                null,
                Known,
                1,
                0,
                0,
                50,
                1,
                1,
            ),
            ObservedSubzoneSpeciesTotalsRow(
                observationId2,
                zone1SubzoneId1,
                null,
                "fern",
                Other,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId2,
                zoneId1,
                speciesId1,
                null,
                Known,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId2,
                zoneId1,
                speciesId2,
                null,
                Known,
                1,
                0,
                0,
                50,
                1,
                1,
            ),
            ObservedZoneSpeciesTotalsRow(
                observationId2,
                zoneId1,
                null,
                "fern",
                Other,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedSiteSpeciesTotalsRow(
                observationId2,
                plantingSiteId,
                speciesId1,
                null,
                Known,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
            ObservedSiteSpeciesTotalsRow(
                observationId2,
                plantingSiteId,
                speciesId2,
                null,
                Known,
                1,
                0,
                0,
                50,
                1,
                1,
            ),
            ObservedSiteSpeciesTotalsRow(
                observationId2,
                plantingSiteId,
                null,
                "fern",
                Other,
                0,
                0,
                0,
                0,
                0,
                0,
            ),
        ),
        "Totals after plot removal",
    )
  }

  @Test
  fun `does not modify totals if plot has no recorded plants`() {
    helper.insertPlantedSite()
    val plotWithPlants = insertMonitoringPlot()
    insertObservation(completedTime = Instant.EPOCH)
    helper.insertObservationScenario(
        ObservationZone(
            zoneId = inserted.plantingZoneId,
            plots =
                listOf(
                    ObservationPlot(
                        plotWithPlants,
                        listOf(PlantTotals(inserted.speciesId, live = 3, dead = 2, existing = 1)),
                    )
                ),
        )
    )

    val totalsBeforeRemoval = helper.fetchAllTotals()

    val plotWithoutPlants = insertMonitoringPlot()
    insertObservation()
    insertObservationPlot(completedTime = Instant.EPOCH)

    store.removePlotFromTotals(plotWithoutPlants)

    helper.assertTotals(totalsBeforeRemoval, "Totals after plot removal")
  }

  @Test
  fun `throws exception if removing plot totals for an ad-hoc plot`() {
    insertPlantingSite()
    val plotId = insertMonitoringPlot(isAdHoc = true)

    assertThrows<IllegalStateException> { store.removePlotFromTotals(plotId) }
  }
}
