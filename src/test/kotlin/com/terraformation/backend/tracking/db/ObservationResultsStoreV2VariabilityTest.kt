package com.terraformation.backend.tracking.db

import com.terraformation.backend.tracking.model.ObservationPlotCountRange
import com.terraformation.backend.tracking.model.ObservationPlotCountRecommendation
import com.terraformation.backend.tracking.model.ObservationResultsDepth
import com.terraformation.backend.tracking.model.ObservationStratumVariabilityModel
import com.terraformation.backend.tracking.model.PlausibleVariabilityRange
import com.terraformation.backend.tracking.model.PlotCountRecommendationType
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ObservationResultsStoreV2VariabilityTest : ObservationScenarioTest() {
  @Test
  fun `returns the variability snapshot recorded for each observation`() {
    insertStratum()
    insertSubstratum()
    insertMonitoringPlot()
    val originalSiteHistoryId = inserted.plantingSiteHistoryId
    val originalStratumHistoryId = inserted.stratumHistoryId

    val olderObservationId = insertObservation(completedTime = Instant.ofEpochSecond(10))
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

    // The only plot in the newer observation was assigned under a newer map, so the original
    // stratum history has no observed plots and is included because of its snapshot.
    insertPlantingSiteHistory()
    insertStratumHistory()
    insertSubstratumHistory()
    insertMonitoringPlot()
    val newerObservationId =
        insertObservation(
            completedTime = Instant.ofEpochSecond(20),
            plantingSiteHistoryId = originalSiteHistoryId,
        )
    insertObservationPlot(completedBy = user.userId)
    insertObservationStratumVariability(
        stratumHistoryId = originalStratumHistoryId,
        sampledPlotCount = 2,
        measuredVariability = 10.0,
        plausibleVariabilityLow = 5.0,
        plausibleVariabilityHigh = 60.0,
        configuredPlotCountAtCompletion = 10,
        estimatedPlotCountLow = 2,
        estimatedPlotCountBest = 3,
        estimatedPlotCountHigh = 4,
        recommendedPlotCount = 4,
    )

    assertEquals(
        ObservationStratumVariabilityModel(
            observationId = olderObservationId,
            sampledPlotCount = 5,
            measuredVariability = 40.0,
            plausibleVariabilityRange = PlausibleVariabilityRange(30.0, 80.0),
            configuredPlotCountAtCompletion = 8,
            estimatedPlotCountRange = ObservationPlotCountRange(10, 20, 75),
            recommendation =
                ObservationPlotCountRecommendation(PlotCountRecommendationType.Increase, 20),
        ),
        resultsStoreV2
            .fetchOneById(olderObservationId, ObservationResultsDepth.Stratum)
            .strata
            .single()
            .variability,
        "Variability of older observation",
    )

    assertEquals(
        ObservationStratumVariabilityModel(
            observationId = newerObservationId,
            sampledPlotCount = 2,
            measuredVariability = 10.0,
            plausibleVariabilityRange = PlausibleVariabilityRange(5.0, 60.0),
            configuredPlotCountAtCompletion = 10,
            estimatedPlotCountRange = ObservationPlotCountRange(2, 3, 4),
            recommendation =
                ObservationPlotCountRecommendation(PlotCountRecommendationType.Decrease, 4),
        ),
        resultsStoreV2
            .fetchOneById(newerObservationId, ObservationResultsDepth.Stratum)
            .strata
            .single()
            .variability,
        "Variability of stratum with no plots observed under the observation's map",
    )
  }
}
