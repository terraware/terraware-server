package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.tracking.PlotCountRecommendationResolution
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_STRATUM_VARIABILITY
import com.terraformation.backend.db.tracking.tables.references.STRATUM_PLOT_SELECTION_PARAMETERS
import com.terraformation.backend.tracking.db.AcceleratorPlotSettingsNotEditableException
import com.terraformation.backend.tracking.db.ObservationResultsStoreV2
import com.terraformation.backend.tracking.db.PlotCountRecommendationNotAvailableException
import com.terraformation.backend.tracking.db.StratumNotFoundException
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreResolveRecommendationTest : BasePlantingSiteStoreTest() {
  private val resultsStore by lazy { ObservationResultsStoreV2(dslContext) }

  @BeforeEach
  fun setUpRecommendation() {
    every { user.canReadObservation(any()) } returns true
    insertPlantingSite()
    insertStratum()
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
    assertTableEquals(expected, "Repeated dismissal does not change the resolution")

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
    val before = dslContext.fetch(OBSERVATION_STRATUM_VARIABILITY)
    listOf(previousObservation, inserted.observationId).forEach { observationId ->
      assertThrows<PlotCountRecommendationNotAvailableException> {
        store.dismissPlotCountRecommendation(
            inserted.plantingSiteId,
            inserted.stratumId,
            observationId,
        )
      }
    }
    assertTableEquals(before, "Rejected dismissal does not resolve any variability")
  }

  @Test
  fun `dismissal requires site update permission`() {
    every { user.canUpdatePlantingSite(any()) } returns false
    assertThrows<AccessDeniedException> {
      store.dismissPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
  }

  @Test
  fun `dismissal rejects accelerator sites`() {
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
    assertTableEquals(before, "Accelerator rejection does not resolve any variability")
  }

  @Test
  fun `dismissal rejects strata outside the site`() {
    val before = dslContext.fetch(OBSERVATION_STRATUM_VARIABILITY)
    insertPlantingSite()
    assertThrows<StratumNotFoundException> {
      store.dismissPlotCountRecommendation(
          inserted.plantingSiteId,
          inserted.stratumId,
          inserted.observationId,
      )
    }
    assertTableEquals(before, "Unauthorized dismissal does not resolve any variability")
  }
}
