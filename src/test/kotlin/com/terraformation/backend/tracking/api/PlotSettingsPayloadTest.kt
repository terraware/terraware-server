package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.tracking.model.ConfidenceLevel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import com.terraformation.backend.tracking.model.StratumPlotCounts
import java.math.BigDecimal
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlotSettingsPayloadTest {
  private val site = existingSite()

  @Test
  fun `response maps all observation types and rounds only variability`() {
    val counts =
        StratumPlotCounts(
            mode = PlotSelectionMode.Custom,
            studentsT = BigDecimal("1.6450"),
            errorMargin = BigDecimal("15"),
            variance = BigDecimal("909.0225"),
            numPermanentPlots = 8,
            numTemporaryPlots = 3,
        )
    val stratum =
        site.strata
            .single()
            .copy(
                plantPlotCounts = counts,
                biomassPlotCounts = counts.copy(studentsT = BigDecimal("1.5")),
                baselinePlotCounts = counts.copy(numPermanentPlots = 0, numTemporaryPlots = 1),
            )
    val expected =
        PlotSelectionSettingPayload(
            ObservationType.Monitoring,
            PlotSelectionMode.Custom,
            null,
            90,
            BigDecimal("15"),
            BigDecimal("30.2"),
            8,
            3,
            ConfidenceLevel.Ninety.studentsTForPlotCount(11)!! * 30.15 / sqrt(11.0),
        )
    val expectedStratum =
        StratumPlotSettingsPayload(
            stratum.id,
            stratum.name,
            listOf(
                expected,
                expected.copy(
                    observationType = ObservationType.BiomassMeasurements,
                    confidenceLevel = null,
                    expectedPrecision = null,
                ),
                expected.copy(
                    observationType = ObservationType.BaselineSurvey,
                    numPermanentPlots = 0,
                    numTemporaryPlots = 1,
                    expectedPrecision = null,
                ),
            ),
        )
    assertEquals(
        PlotSettingsResponsePayload(PlotPrecisionLevel.High, listOf(expectedStratum)),
        PlotSettingsResponsePayload(
            site.copy(defaultPlotPrecisionLevel = PlotPrecisionLevel.High, strata = listOf(stratum))
        ),
        "response maps all observation types and rounds only variability",
    )
  }
}
