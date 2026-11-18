package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import java.math.BigDecimal

sealed interface PlotSelectionSetting {
  data class PrecisionLevel(val level: PlotPrecisionLevel) : PlotSelectionSetting

  data class Custom(
      val confidenceLevel: ConfidenceLevel,
      val targetMarginOfError: BigDecimal,
      val variability: BigDecimal,
  ) : PlotSelectionSetting

  data class Direct(val numPermanentPlots: Int, val numTemporaryPlots: Int) : PlotSelectionSetting
}

fun StratumPlotCounts.applySetting(
    observationType: ObservationType,
    setting: PlotSelectionSetting,
): StratumPlotCounts {
  val baseline = observationType == ObservationType.BaselineSurvey
  val parameters =
      when (setting) {
        is PlotSelectionSetting.PrecisionLevel -> {
          copy(
              mode = PlotSelectionMode.PrecisionLevel,
              plotPrecisionLevel = setting.level,
              studentsT = ConfidenceLevel.Ninety.plotCountStudentsT,
              errorMargin = setting.level.targetMarginOfError,
          )
        }
        is PlotSelectionSetting.Custom -> {
          require(setting.targetMarginOfError in BigDecimal("5.0")..BigDecimal("25.0")) {
            "Target margin of error must be between 5.0 and 25.0"
          }
          require(setting.variability in BigDecimal("1.0")..BigDecimal("200.0")) {
            "Variability must be between 1.0 and 200.0"
          }
          require(setting.targetMarginOfError.stripTrailingZeros().scale() <= 1) {
            "Target margin of error must have at most one decimal place"
          }
          require(setting.variability.stripTrailingZeros().scale() <= 1) {
            "Variability must have at most one decimal place"
          }
          copy(
              mode = PlotSelectionMode.Custom,
              plotPrecisionLevel = null,
              studentsT = setting.confidenceLevel.plotCountStudentsT,
              errorMargin = setting.targetMarginOfError,
              variance = setting.variability.pow(2),
          )
        }
        is PlotSelectionSetting.Direct -> {
          require(
              if (baseline) setting.numPermanentPlots == 0 else setting.numPermanentPlots >= 1
          ) {
            "Permanent plot count must be ${if (baseline) "zero" else "at least one"}"
          }
          require(setting.numTemporaryPlots >= if (baseline) 1 else 0) {
            "Temporary plot count must be at least ${if (baseline) "one" else "zero"}"
          }
          return copy(
              mode = PlotSelectionMode.Direct,
              plotPrecisionLevel = null,
              numPermanentPlots = setting.numPermanentPlots,
              numTemporaryPlots = setting.numTemporaryPlots,
          )
        }
      }
  return parameters.recalculatePlotCounts(observationType)
}

fun StratumPlotCounts.recalculatePlotCounts(observationType: ObservationType): StratumPlotCounts {
  val baseline = observationType == ObservationType.BaselineSurvey
  val counts =
      PlotCountCalculator.calculatePlotCounts(
          studentsT = studentsT,
          variability = variability,
          targetMarginOfError = errorMargin,
      )
  return copy(
      numPermanentPlots = if (baseline) 0 else counts.permanent,
      numTemporaryPlots = if (baseline) counts.total.coerceAtLeast(1) else counts.temporary,
  )
}
