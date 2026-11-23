package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.STRATUM_PLOT_SELECTION_PARAMETERS
import com.terraformation.backend.tracking.db.AcceleratorPlotSettingsNotEditableException
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.event.StratumPlotCountsUpdatedEvent
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEvent
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventSource
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventValues
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import java.math.BigDecimal
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
    val unchangedDefault = PlotPrecisionLevel.Medium
    val siteBeforeEdits = dslContext.fetch(PLANTING_SITES)
    clock.instant = Instant.ofEpochSecond(100)
    // Separate updates require the count edit to retain the saved variance;
    // a combined copy would not detect an edit based on stale values.
    store.updatePlotSelectionSettings(
        siteId,
        unchangedDefault,
        mapOf(
            stratumId to
                mapOf(ObservationType.Monitoring to { it.copy(variance = BigDecimal("75")) })
        ),
    )
    eventPublisher.assertEventNotPublished<StratumPlotCountsUpdatedEvent>()
    store.updatePlotSelectionSettings(
        siteId,
        unchangedDefault,
        mapOf(stratumId to mapOf(ObservationType.Monitoring to { it.copy(numPermanentPlots = 7) })),
    )
    assertTableEquals(
        siteBeforeEdits,
        "Unchanged default preserves site metadata",
    )
    val expectedPlotCounts = initial.copy(variance = BigDecimal("75"), numPermanentPlots = 7)
    assertEquals(
        expectedPlotCounts,
        store.fetchSiteById(siteId, PlantingSiteDepth.Stratum).strata.single().plantPlotCounts,
        "applies edits to latest stored parameters and validates before saving",
    )
    val siteBefore = dslContext.fetch(PLANTING_SITES)
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
    assertTableEquals(
        siteBefore,
        "applies edits to latest stored parameters and validates before saving",
    )
    assertEquals(
        expectedPlotCounts,
        store.fetchSiteById(siteId, PlantingSiteDepth.Stratum).strata.single().plantPlotCounts,
        "applies edits to latest stored parameters and validates before saving",
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
    clock.instant = Instant.ofEpochSecond(100)
    val expectedSite =
        dslContext.fetch(PLANTING_SITES).onEach {
          it.defaultPlotPrecisionLevelId = PlotPrecisionLevel.High
          it.modifiedTime = clock.instant()
          it.modifiedBy = user.userId
        }

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

    assertTableEquals(
        expectedSite,
        "merges parameters and publishes only changed types and strata",
    )
    assertTableEquals(expectedParameters)
    assertTableEquals(expectedStrata)
    val expectedEvent =
        StratumPlotSelectionSettingsChangedEvent(
            organizationId = organizationId,
            plantingSiteId = siteId,
            stratumId = changedId,
            stratumName = "Changed",
            source = StratumPlotSelectionSettingsChangedEventSource.UserEdit,
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
    eventPublisher.assertEventsPublished(
        listOf(
            expectedEvent,
            StratumPlotCountsUpdatedEvent(
                siteId,
                changedId,
                setOf(ObservationType.BaselineSurvey),
                upcomingOnly = true,
            ),
        )
    )
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
        "changing only default leaves all stratum settings unchanged",
    )
    eventPublisher.assertEventNotPublished<StratumPlotSelectionSettingsChangedEvent>()
  }

  @Test
  fun `rejects accelerator site`() {
    insertProject()
    insertProjectAcceleratorDetails()
    val siteId = insertPlantingSite(projectId = inserted.projectId)
    val initial = dslContext.fetch(PLANTING_SITES)
    assertThrows<AcceleratorPlotSettingsNotEditableException> {
      store.updatePlotSelectionSettings(siteId, PlotPrecisionLevel.High, emptyMap())
    }
    assertTableEquals(
        initial,
        "rejects accelerator site",
    )
  }

  @Test
  fun `rejects stratum from another site before saving changes`() {
    val siteId = insertPlantingSite()
    insertPlantingSite()
    val foreignId = insertStratum()
    val initial = dslContext.fetch(PLANTING_SITES)
    assertThrows<StratumNotFoundException> {
      store.updatePlotSelectionSettings(
          siteId,
          PlotPrecisionLevel.High,
          mapOf(foreignId to emptyMap()),
      )
    }
    assertTableEquals(
        initial,
        "rejects stratum from another site before saving changes",
    )
    eventPublisher.assertEventNotPublished<StratumPlotSelectionSettingsChangedEvent>()
  }
}
