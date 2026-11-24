package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotCountRecommendationResolution
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_VARIABILITY
import com.terraformation.backend.db.tracking.tables.references.STRATUM_PLOT_SELECTION_PARAMETERS
import com.terraformation.backend.tracking.db.AcceleratorPlotSettingsNotEditableException
import com.terraformation.backend.tracking.db.ObservationResultsStoreV2
import com.terraformation.backend.tracking.db.PlotCountRecommendationNotAvailableException
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.event.PlantingSitePlotCountsUpdatedEvent
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEvent
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventSource
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventValues
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.StratumModel.Companion.DEFAULT_VARIANCE
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreResolveRecommendationTest : BasePlantingSiteStoreTest() {
  private val resultsStore by lazy { ObservationResultsStoreV2(dslContext) }

  @BeforeEach
  fun setUpRecommendation() {
    every { user.canReadObservation(any()) } returns true
    insertPlantingSite()
    insertStratum(name = "Test stratum")
    insertSubstratum()
    insertMonitoringPlot()
    insertObservation(completedTime = Instant.ofEpochSecond(10))
    insertObservationPlot(completedBy = user.userId)
    insertObservationStratumVariability(
        sampledPlotCount = 5,
        measuredVariability = 40.0,
        plausibleVariabilityLow = 30.0,
        plausibleVariabilityHigh = 80.0,
        configuredPlotCountAtCompletion = 8,
        estimatedPlotCountLow = 10,
        estimatedPlotCountBest = 20,
        estimatedPlotCountHigh = 75,
        recommendedPlotCount = 20,
    )
  }

  @Test
  fun `dismissal records actor and time and hides recommendation from site and observation`() {
    val settings = dslContext.fetch(STRATUM_PLOT_SELECTION_PARAMETERS)
    val expected =
        dslContext.fetchSingle(OBSERVATION_STRATUM_VARIABILITY).apply {
          recommendationResolutionId = PlotCountRecommendationResolution.Dismissed
          resolvedBy = user.userId
          resolvedTime = clock.instant()
        }

    store.dismissPlotCountRecommendation(
        inserted.plantingSiteId,
        inserted.stratumId,
        inserted.observationId,
    )

    assertTableEquals(expected, "Dismissal records its resolution, actor and time")
    assertTableEquals(settings, "Dismissal does not change plot settings")
    val siteVariability =
        store
            .fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Stratum)
            .strata
            .single()
            .latestObservationVariability!!
    val observationVariability =
        resultsStore
            .fetchOneById(inserted.observationId, ObservationResultsDepth.Stratum)
            .strata
            .single()
            .variability!!
    assertNull(siteVariability.recommendation, "Site recommendation is hidden")
    assertEquals(
        siteVariability,
        observationVariability,
        "Historical recommendation is also hidden",
    )
    assertThrows<PlotCountRecommendationNotAvailableException> {
      store.dismissPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
    assertThrows<PlotCountRecommendationNotAvailableException> {
      store.acceptPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }

    insertObservation(completedTime = Instant.ofEpochSecond(20))
    insertObservationStratumVariability(
        sampledPlotCount = 5,
        configuredPlotCountAtCompletion = 8,
        recommendedPlotCount = 20,
    )
    assertEquals(
        20L,
        store
            .fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Stratum)
            .strata
            .single()
            .latestObservationVariability!!
            .recommendation!!
            .recommendedPlotCount,
        "A new snapshot has its own unresolved recommendation",
    )
  }

  @Test
  fun `rejects stale snapshot and latest snapshot without recommendation`() {
    val previousObservation = inserted.observationId
    insertObservation(completedTime = Instant.ofEpochSecond(20))
    insertObservationStratumVariability(sampledPlotCount = 1)
    listOf(previousObservation, inserted.observationId).forEach { observationId ->
      assertThrows<PlotCountRecommendationNotAvailableException> {
        store.dismissPlotCountRecommendation(
            inserted.plantingSiteId,
            inserted.stratumId,
            observationId,
        )
      }
      assertThrows<PlotCountRecommendationNotAvailableException> {
        store.acceptPlotCountRecommendation(
            inserted.plantingSiteId,
            inserted.stratumId,
            observationId,
        )
      }
    }
  }

  @Test
  fun `resolution requires site update permission`() {
    every { user.canUpdatePlantingSite(any()) } returns false
    assertThrows<AccessDeniedException> {
      store.dismissPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
    assertThrows<AccessDeniedException> {
      store.acceptPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
  }

  @Test
  fun `resolution rejects accelerator sites`() {
    val before = dslContext.fetch(OBSERVATION_STRATUM_VARIABILITY)
    insertProject()
    insertProjectAcceleratorDetails()
    plantingSitesDao.update(
        plantingSitesDao
            .fetchOneById(inserted.plantingSiteId)!!
            .copy(projectId = inserted.projectId)
    )
    assertThrows<AcceleratorPlotSettingsNotEditableException> {
      store.dismissPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
    assertThrows<AcceleratorPlotSettingsNotEditableException> {
      store.acceptPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
    assertTableEquals(before, "Accelerator rejection does not resolve any variability")
  }

  @Test
  fun `resolution rejects strata outside the site`() {
    insertPlantingSite()
    assertThrows<StratumNotFoundException> {
      store.dismissPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
    assertThrows<StratumNotFoundException> {
      store.acceptPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
  }

  @ParameterizedTest
  @CsvSource(
      "PrecisionLevel, 40.1234, 80, 8, 20, 1.645, 10, 1609.88722756, 33, 11",
      "Custom, 40, 80, 100, 75, 1.282, 20, 6400.00, 20, 7",
      "Custom, 250, 300, 8, 20, 1.645, 10, 40000, 812, 271",
      "Custom, 0, 0, 100, 2, 1.645, 10, 1, 1, 1",
  )
  fun `acceptance uses measured increase or conservative decrease and preserves statistical settings`(
      mode: PlotSelectionMode,
      measured: Double,
      high: Double,
      configured: Long,
      recommended: Long,
      studentsT: BigDecimal,
      margin: BigDecimal,
      variance: BigDecimal,
      permanent: Int,
      temporary: Int,
  ) {
    val snapshot = dslContext.fetchSingle(OBSERVATION_STRATUM_VARIABILITY)
    snapshot.measuredVariability = measured
    snapshot.plausibleVariabilityHigh = high
    snapshot.configuredPlotCountAtCompletion = configured
    snapshot.recommendedPlotCount = recommended
    snapshot.update()
    store.updatePlotSelectionSettings(
        inserted.plantingSiteId,
        PlotPrecisionLevel.Medium,
        mapOf(
            inserted.stratumId to
                mapOf(
                    ObservationType.Monitoring to
                        { current ->
                          current.copy(
                              mode = mode,
                              plotPrecisionLevel =
                                  if (mode == PlotSelectionMode.PrecisionLevel) {
                                    PlotPrecisionLevel.Medium
                                  } else {
                                    null
                                  },
                              studentsT = studentsT,
                              errorMargin = margin,
                          )
                        }
                )
        ),
    )
    val expectedSettings =
        dslContext.fetch(STRATUM_PLOT_SELECTION_PARAMETERS).onEach { parameters ->
          if (parameters.observationTypeId == ObservationType.Monitoring) {
            parameters.variance = variance
            parameters.numPermanentPlots = permanent
            parameters.numTemporaryPlots = temporary
          }
        }
    eventPublisher.clear()
    clock.instant = Instant.ofEpochSecond(100)
    snapshot.recommendationResolutionId = PlotCountRecommendationResolution.Accepted
    snapshot.resolvedBy = user.userId
    snapshot.resolvedTime = clock.instant()

    store.acceptPlotCountRecommendation(
        inserted.plantingSiteId,
        inserted.stratumId,
        inserted.observationId,
    )

    assertTableEquals(
        expectedSettings,
        "Acceptance preserves mode and precision level while updating only Monitoring variance and counts",
    )
    assertTableEquals(snapshot, "Acceptance records its resolution, actor and time")
    eventPublisher.assertEventPublished(
        PlantingSitePlotCountsUpdatedEvent(
            inserted.plantingSiteId,
            mapOf(inserted.stratumId to setOf(ObservationType.Monitoring)),
        ),
        "Changed plot counts trigger observation reassignment",
    )
    assertNull(
        store
            .fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Stratum)
            .strata
            .single()
            .latestObservationVariability!!
            .recommendation,
        "Accepted recommendation is hidden",
    )
    eventPublisher.assertEventPublished(
        StratumPlotSelectionSettingsChangedEvent(
            organizationId = organizationId,
            plantingSiteId = inserted.plantingSiteId,
            stratumId = inserted.stratumId,
            stratumName = "Test stratum",
            observationType = ObservationType.Monitoring,
            source = StratumPlotSelectionSettingsChangedEventSource.AcceptedRecommendation,
            observationId = inserted.observationId,
            measuredVariability = BigDecimal.valueOf(measured),
            changedFrom =
                StratumPlotSelectionSettingsChangedEventValues(
                    variance = DEFAULT_VARIANCE,
                ),
            changedTo =
                StratumPlotSelectionSettingsChangedEventValues(
                    variance = variance,
                ),
            numPermanentPlots = permanent,
            numTemporaryPlots = temporary,
        ),
        "Acceptance history records settings changes and observation metadata",
    )
    assertThrows<PlotCountRecommendationNotAvailableException> {
      store.acceptPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
  }

  @Test
  fun `acceptance records history even when settings already match the recommendation`() {
    val site = store.fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Stratum)
    store.updatePlotSelectionSettings(
        inserted.plantingSiteId,
        site.defaultPlotPrecisionLevel,
        mapOf(
            inserted.stratumId to
                mapOf(
                    ObservationType.Monitoring to
                        { current ->
                          current
                              .copy(
                                  mode = PlotSelectionMode.Custom,
                                  plotPrecisionLevel = null,
                                  variance = BigDecimal("1600.00"),
                              )
                              .recalculatePlotCounts(ObservationType.Monitoring)
                        }
                )
        ),
    )
    val settings = dslContext.fetch(STRATUM_PLOT_SELECTION_PARAMETERS)
    val expectedVariability =
        dslContext.fetchSingle(OBSERVATION_STRATUM_VARIABILITY).apply {
          recommendationResolutionId = PlotCountRecommendationResolution.Accepted
          resolvedBy = user.userId
          resolvedTime = clock.instant()
        }
    eventPublisher.clear()

    store.acceptPlotCountRecommendation(
        inserted.plantingSiteId,
        inserted.stratumId,
        inserted.observationId,
    )

    assertTableEquals(settings, "Matching settings remain unchanged")
    eventPublisher.assertEventNotPublished<PlantingSitePlotCountsUpdatedEvent>(
        "Matching counts do not trigger observation reassignment"
    )
    assertTableEquals(
        expectedVariability,
        "Matching settings still record the acceptance resolution, actor and time",
    )
    eventPublisher.assertEventPublished("Matching settings still record acceptance history") { event
      ->
      event is StratumPlotSelectionSettingsChangedEvent &&
          event.source == StratumPlotSelectionSettingsChangedEventSource.AcceptedRecommendation &&
          event.observationId == inserted.observationId &&
          event.measuredVariability == BigDecimal("40.0")
    }
  }

  @Test
  fun `acceptance rejects direct mode`() {
    val site = store.fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Stratum)
    store.updatePlotSelectionSettings(
        inserted.plantingSiteId,
        site.defaultPlotPrecisionLevel,
        mapOf(
            inserted.stratumId to
                mapOf(
                    ObservationType.Monitoring to
                        {
                          it.copy(mode = PlotSelectionMode.Direct, plotPrecisionLevel = null)
                        }
                )
        ),
    )
    assertThrows<PlotCountRecommendationNotAvailableException> {
      store.acceptPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
  }
}
