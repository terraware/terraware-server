package com.terraformation.backend.tracking.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt
import org.apache.commons.statistics.distribution.TDistribution

/** [plotCountStudentsT] is the infinite-df t value used to estimate the required plot count. */
enum class ConfidenceLevel(val percent: Int, val plotCountStudentsT: BigDecimal) {
  Seventy(70, BigDecimal("1.036")),
  Eighty(80, BigDecimal("1.282")),
  Ninety(90, BigDecimal("1.645")),
  NinetyFive(95, BigDecimal("1.96")),
  NinetyNine(99, BigDecimal("2.576"));

  /** Uses n - 1 degrees of freedom to avoid overstating precision for small samples. */
  fun studentsTForPlotCount(numPlots: Int): Double? {
    if (numPlots < 2) {
      return null
    }

    // A two-sided interval uses the quantile 1 - (1 - confidence) / 2 = 0.5 + confidence / 2.
    return TDistribution.of(numPlots.toDouble() - 1)
        .inverseCumulativeProbability(0.5 + percent / 200.0)
  }
}

enum class PrecisionLevel(val targetMarginOfError: BigDecimal) {
  VeryLow(BigDecimal("22.5")),
  Low(BigDecimal("20")),
  Medium(BigDecimal("15")),
  High(BigDecimal("10")),
  VeryHigh(BigDecimal("7"));

  val confidenceLevel: ConfidenceLevel
    get() = ConfidenceLevel.Ninety
}

data class CalculatedPlotCounts(val total: Int, val permanent: Int, val temporary: Int)

object PlotCountCalculator {
  /**
   * Calculates ceil((t * variability / target margin of error)^2), with percentages such as 30 for
   * 30%. The 75/25 permanent/temporary split has a minimum of one each, so their sum can exceed
   * total.
   */
  fun calculatePlotCounts(
      studentsT: BigDecimal,
      variability: BigDecimal,
      targetMarginOfError: BigDecimal,
  ): CalculatedPlotCounts {
    require(studentsT.signum() > 0) { "Student's t must be positive" }
    require(variability.signum() >= 0) { "Variability must be nonnegative" }
    require(targetMarginOfError.signum() > 0) { "Target margin of error must be positive" }

    val total =
        studentsT
            .multiply(variability)
            .pow(2)
            .divide(targetMarginOfError.pow(2), 0, RoundingMode.CEILING)
            .intValueExact()
    val permanent =
        total
            .toBigDecimal()
            .multiply(BigDecimal("0.75"))
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact()
            .coerceAtLeast(1)
    return CalculatedPlotCounts(total, permanent, (total - permanent).coerceAtLeast(1))
  }

  fun calculatePlotCounts(
      precisionLevel: PrecisionLevel,
      variability: BigDecimal,
  ): CalculatedPlotCounts =
      calculatePlotCounts(
          studentsT = precisionLevel.confidenceLevel.plotCountStudentsT,
          targetMarginOfError = precisionLevel.targetMarginOfError,
          variability = variability,
      )

  /**
   * Returns t(n - 1) * variability / sqrt(n) as an unrounded percentage, or null for fewer than two
   * plots. Unlike plot-count estimation, this uses the finite-sample t value.
   */
  fun expectedPrecision(
      confidenceLevel: ConfidenceLevel,
      numPlots: Int,
      variability: BigDecimal,
  ): Double? {
    require(variability.signum() >= 0) { "Variability must be nonnegative" }
    val studentsT = confidenceLevel.studentsTForPlotCount(numPlots) ?: return null
    return studentsT * variability.toDouble() / sqrt(numPlots.toDouble())
  }
}
