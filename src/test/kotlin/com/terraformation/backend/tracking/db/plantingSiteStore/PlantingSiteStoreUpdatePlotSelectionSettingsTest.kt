package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_PLOT_SELECTION_PARAMETERS
import com.terraformation.backend.tracking.db.AcceleratorPlotSettingsNotEditableException
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEvent
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventSettings
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventSource
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventValues
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSiteStoreUpdatePlotSelectionSettingsTest : BasePlantingSiteStoreTest() {
  @Test
  fun `applies edits to latest stored parameters and validates before saving`() {
    val siteId = insertPlantingSite()
    val stratumId = insertStratum()
    val initial =
        store.fetchSiteById(siteId, PlantingSiteDepth.Stratum).strata.single().plantPlotCounts
    store.updatePlotSelectionSettings(
        siteId,
        PlotPrecisionLevel.High,
        mapOf(
            stratumId to
                mapOf(
                    ObservationType.Monitoring to { it.copy(variance = java.math.BigDecimal("75")) }
                )
        ),
    )
    store.updatePlotSelectionSettings(
        siteId,
        PlotPrecisionLevel.High,
        mapOf(stratumId to mapOf(ObservationType.Monitoring to { it.copy(numPermanentPlots = 7) })),
    )
    val expected = initial.copy(variance = java.math.BigDecimal("75"), numPermanentPlots = 7)
    assertEquals(
        expected,
        store.fetchSiteById(siteId, PlantingSiteDepth.Stratum).strata.single().plantPlotCounts,
    )
    val siteBefore = plantingSitesDao.fetchOneById(siteId)
    assertThrows<IllegalArgumentException> {
      store.updatePlotSelectionSettings(
          siteId,
          PlotPrecisionLevel.Low,
          mapOf(
              stratumId to
                  mapOf(
                      ObservationType.Monitoring to
                          {
                            throw IllegalArgumentException("Invalid edit")
                          }
                  )
          ),
      )
    }
    assertEquals(siteBefore, plantingSitesDao.fetchOneById(siteId))
    assertEquals(
        expected,
        store.fetchSiteById(siteId, PlantingSiteDepth.Stratum).strata.single().plantPlotCounts,
    )
  }

  @Test
  fun `merges parameters and publishes only changed types and strata`() {
    val siteId = insertPlantingSite()
    val changedId = insertStratum(name = "Changed")
    val unchangedId = insertStratum()
    insertStratum()
    val initial = store.fetchSiteById(siteId, PlantingSiteDepth.Stratum)
    val previous = initial.strata.first { it.id == changedId }.baselinePlotCounts
    val counts = previous.copy(numTemporaryPlots = previous.numTemporaryPlots + 3)
    val expectedParameters =
        dslContext.fetch(STRATUM_PLOT_SELECTION_PARAMETERS).onEach {
          if (it.stratumId == changedId && it.observationTypeId == ObservationType.BaselineSurvey) {
            it.numTemporaryPlots = counts.numTemporaryPlots
          }
        }
    val expectedStrata = dslContext.fetch(STRATA)
    val expectedSite =
        plantingSitesDao
            .fetchOneById(siteId)!!
            .copy(
                defaultPlotPrecisionLevelId = PlotPrecisionLevel.High,
                modifiedTime = Instant.ofEpochSecond(100),
                modifiedBy = user.userId,
            )
    clock.instant = expectedSite.modifiedTime!!
    val events = mutableListOf<StratumPlotSelectionSettingsChangedEvent>()
    eventPublisher.register<StratumPlotSelectionSettingsChangedEvent> { events.add(it) }

    store.updatePlotSelectionSettings(
        siteId,
        PlotPrecisionLevel.High,
        mapOf(
            changedId to
                mapOf(
                    ObservationType.BaselineSurvey to
                        {
                          it.copy(numTemporaryPlots = it.numTemporaryPlots + 3)
                        },
                    ObservationType.Monitoring to { it },
                ),
            unchangedId to mapOf(ObservationType.BaselineSurvey to { it }),
        ),
    )

    assertEquals(expectedSite, plantingSitesDao.fetchOneById(siteId))
    assertTableEquals(expectedParameters)
    assertTableEquals(expectedStrata)
    val expectedEvent =
        StratumPlotSelectionSettingsChangedEvent(
            organizationId = organizationId,
            plantingSiteId = siteId,
            stratumId = changedId,
            stratumName = "Changed",
            source = StratumPlotSelectionSettingsChangedEventSource.UserEdit,
            settings =
                listOf(
                    StratumPlotSelectionSettingsChangedEventSettings(
                        observationType = ObservationType.BaselineSurvey,
                        changedFrom =
                            StratumPlotSelectionSettingsChangedEventValues(
                                numTemporaryPlots = previous.numTemporaryPlots
                            ),
                        changedTo =
                            StratumPlotSelectionSettingsChangedEventValues(
                                numTemporaryPlots = counts.numTemporaryPlots
                            ),
                        numPermanentPlots = counts.numPermanentPlots,
                        numTemporaryPlots = counts.numTemporaryPlots,
                    )
                ),
        )
    eventPublisher.assertEventPublished(expectedEvent)
    assertEquals(listOf(expectedEvent), events)
  }

  @Test
  fun `changing only default leaves all stratum settings unchanged`() {
    val siteId = insertPlantingSite()
    insertStratum()
    val initial = store.fetchSiteById(siteId, PlantingSiteDepth.Stratum)
    store.updatePlotSelectionSettings(siteId, PlotPrecisionLevel.High, emptyMap())
    assertEquals(
        initial.copy(defaultPlotPrecisionLevel = PlotPrecisionLevel.High),
        store.fetchSiteById(siteId, PlantingSiteDepth.Stratum),
    )
    eventPublisher.assertEventNotPublished<StratumPlotSelectionSettingsChangedEvent>()
  }

  @Test
  fun `rejects accelerator site`() {
    insertProject()
    insertProjectAcceleratorDetails()
    val siteId = insertPlantingSite(projectId = inserted.projectId)
    val initial = plantingSitesDao.fetchOneById(siteId)
    assertThrows<AcceleratorPlotSettingsNotEditableException> {
      store.updatePlotSelectionSettings(siteId, PlotPrecisionLevel.High, emptyMap())
    }
    assertEquals(initial, plantingSitesDao.fetchOneById(siteId))
  }

  @Test
  fun `rejects stratum from another site before saving changes`() {
    val siteId = insertPlantingSite()
    insertPlantingSite()
    val foreignId = insertStratum()
    val initial = plantingSitesDao.fetchOneById(siteId)
    assertThrows<StratumNotFoundException> {
      store.updatePlotSelectionSettings(
          siteId,
          PlotPrecisionLevel.High,
          mapOf(foreignId to emptyMap()),
      )
    }
    assertEquals(initial, plantingSitesDao.fetchOneById(siteId))
    eventPublisher.assertEventNotPublished<StratumPlotSelectionSettingsChangedEvent>()
  }
}
