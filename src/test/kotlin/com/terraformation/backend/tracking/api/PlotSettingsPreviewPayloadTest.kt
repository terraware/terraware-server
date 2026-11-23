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
import org.junit.jupiter.api.assertThrows

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
    listOf(BigDecimal("250000"), BigDecimal("1E30")).forEach { variance ->
      val largeStratum =
          stratum.copy(
              plantPlotCounts = counts.copy(variance = variance, errorMargin = BigDecimal("5"))
          )
      val largeSite = site.copy(strata = listOf(largeStratum))
      val direct =
          request.copy(
              strata =
                  listOf(
                      request.strata
                          .single()
                          .copy(
                              mode = PlotSelectionMode.Direct,
                              numPermanentPlots = 7,
                              numTemporaryPlots = 3,
                          )
                  )
          )
      val actual =
          PlotSettingsPreviewResponsePayload(largeSite, direct.toSettings(largeSite))
              .strata
              .single()
              .settings
              .first { it.observationType == ObservationType.Monitoring }
      assertEquals(
          PlotPrecisionLevel.entries.sortedBy { it.id },
          actual.precisionLevels.map { it.plotPrecisionLevel },
          "Large variance $variance preserves every alternative level",
      )
      assertEquals(
          PlotCountsPreviewPayload(null, null, null),
          actual.precisionLevels
              .single { it.plotPrecisionLevel == PlotPrecisionLevel.VeryHigh }
              .counts,
          "Oversized precision alternative has null counts and precision for variance $variance",
      )
      assertEquals(
          PlotCountsPreviewPayload(null, null, null),
          actual.custom,
          "Oversized custom alternative has null counts and precision for variance $variance",
      )
      assertEquals(
          preview(7, 3, sqrt(variance.toDouble())),
          actual.proposed,
          "Valid Direct proposal remains available for variance $variance",
      )
      if (variance == BigDecimal("250000")) {
        assertEquals(
            preview(2255, 752, 500.0),
            actual.precisionLevels
                .single { it.plotPrecisionLevel == PlotPrecisionLevel.Medium }
                .counts,
            "An alternative below the count limit remains available",
        )
      }
      assertThrows<IllegalArgumentException> {
        direct
            .copy(
                strata =
                    listOf(
                        direct.strata
                            .single()
                            .copy(
                                mode = PlotSelectionMode.PrecisionLevel,
                                plotPrecisionLevel = PlotPrecisionLevel.VeryHigh,
                            )
                    )
            )
            .toSettings(largeSite)
      }
      assertThrows<IllegalArgumentException> {
        request
            .copy(
                strata =
                    listOf(
                        request.strata
                            .single()
                            .copy(
                                observationType = ObservationType.BaselineSurvey,
                                confidenceLevel = 99,
                                targetMarginOfError = BigDecimal("5"),
                                variability = BigDecimal("200"),
                            )
                    )
            )
            .toSettings(largeSite)
      }
    }
  }
}
