package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.tracking.model.ConfidenceLevel
import com.terraformation.backend.tracking.model.PlantingSiteBuilder.Companion.existingSite
import com.terraformation.backend.tracking.model.StratumPlotCounts
import java.math.BigDecimal
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlotSettingsPreviewPayloadTest {
  @Test
  fun `preview maps changed and unchanged settings using proposed variance for alternatives`() {
    val counts =
        StratumPlotCounts(
            studentsT = BigDecimal("1.645"),
            variance = BigDecimal("900"),
            errorMargin = BigDecimal("15"),
            numPermanentPlots = 8,
            numTemporaryPlots = 3,
        )
    val site = existingSite()
    val stratum =
        site.strata
            .single()
            .copy(
                plantPlotCounts = counts.copy(variance = BigDecimal("100")),
                biomassPlotCounts = counts,
                baselinePlotCounts = counts.copy(numPermanentPlots = 0, numTemporaryPlots = 11),
            )
    val unchanged =
        stratum.copy(
            id = StratumId(999),
            name = "Unchanged",
            plantPlotCounts =
                counts.copy(
                    mode = PlotSelectionMode.Direct,
                    plotPrecisionLevel = null,
                    numPermanentPlots = 2,
                    numTemporaryPlots = 3,
                ),
            biomassPlotCounts = counts.copy(studentsT = BigDecimal("1.5")),
        )
    val model = site.copy(strata = listOf(stratum, unchanged))
    val request =
        UpdatePlotSettingsRequestPayload(
            PlotPrecisionLevel.High,
            listOf(
                UpdateStratumPlotSettingPayload(
                    stratum.id,
                    ObservationType.Monitoring,
                    PlotSelectionMode.Custom,
                    confidenceLevel = 90,
                    targetMarginOfError = BigDecimal("15"),
                    variability = BigDecimal("30"),
                )
            ),
        )
    fun preview(
        permanent: Int,
        temporary: Int,
        variability: Double = 30.0,
    ): PlotCountsPreviewPayload {
      val total = permanent + temporary
      return PlotCountsPreviewPayload(
          permanent,
          temporary,
          ConfidenceLevel.Ninety.studentsTForPlotCount(total)?.let {
            it * variability / sqrt(total.toDouble())
          },
      )
    }
    val levels =
        listOf(
            Triple(PlotPrecisionLevel.VeryLow, 4, 1),
            Triple(PlotPrecisionLevel.Low, 5, 2),
            Triple(PlotPrecisionLevel.Medium, 8, 3),
            Triple(PlotPrecisionLevel.High, 19, 6),
            Triple(PlotPrecisionLevel.VeryHigh, 38, 12),
        )
    val expectedSettings =
        ObservationType.entries
            .sortedBy { it.id }
            .map { type ->
              val baseline = type == ObservationType.BaselineSurvey
              val proposed = if (baseline) preview(0, 11) else preview(8, 3)
              PlotSettingPreviewPayload(
                  type,
                  if (type == ObservationType.Monitoring) preview(8, 3, 10.0) else proposed,
                  proposed,
                  levels.map { (level, permanent, temporary) ->
                    PrecisionLevelPlotCountsPreviewPayload(
                        level,
                        if (baseline) preview(0, permanent + temporary)
                        else preview(permanent, temporary),
                    )
                  },
                  proposed,
              )
            }
    val unchangedSettings = expectedSettings.map {
      when (it.observationType) {
        ObservationType.Monitoring -> it.copy(current = preview(2, 3), proposed = preview(2, 3))
        ObservationType.BiomassMeasurements ->
            it.copy(
                current = preview(8, 3).copy(expectedPrecision = null),
                proposed = preview(8, 3).copy(expectedPrecision = null),
                custom = null,
            )
        ObservationType.BaselineSurvey -> it
      }
    }
    assertEquals(
        PlotSettingsPreviewResponsePayload(
            listOf(
                StratumPlotSettingsPreviewPayload(stratum.id, stratum.name, expectedSettings),
                StratumPlotSettingsPreviewPayload(unchanged.id, unchanged.name, unchangedSettings),
            )
        ),
        PlotSettingsPreviewResponsePayload(model, request.toSettings(model)),
        "Changed and unchanged settings include proposed-variance alternatives",
    )
    assertEquals(
        PlotSettingsPreviewResponsePayload(emptyList()),
        PlotSettingsPreviewResponsePayload(site.copy(strata = emptyList()), emptyMap()),
        "An empty site has no preview strata",
    )
  }
}
