package com.terraformation.backend.tracking.model

import java.math.BigDecimal
import kotlin.math.sqrt
import org.apache.commons.statistics.distribution.ChiSquaredDistribution

internal const val MIN_VARIABILITY_PLOTS = 5

data class MeasuredVariability(val variability: Double, val numPlots: Int) {
  val hasSmallSample: Boolean
    get() = numPlots < MIN_VARIABILITY_PLOTS
}

data class PlausibleVariabilityRange(val low: Double, val high: Double)

data class PlotCountEstimate(val variability: Double, val plotCounts: CalculatedPlotCounts)

data class RecommendedPlotCountRange(
    val low: PlotCountEstimate,
    val best: PlotCountEstimate,
    val high: PlotCountEstimate,
)

enum class PlotCountRecommendationType {
  Increase,
  Decrease,
}

data class PlotCountRecommendation(
    val type: PlotCountRecommendationType,
    val estimate: PlotCountEstimate,
)

object VariabilityCalculator {
  /**
   * Densities must include zero for empty completed plots, and only plant densities from eligible
   * plots in one observation. Returns the sample standard deviation divided by the mean, as a
   * percentage.
   */
  fun measuredVariability(plotDensities: Collection<Double>): MeasuredVariability? {
    if (plotDensities.size < 2 || plotDensities.any { !it.isFinite() || it < 0 }) {
      return null
    }

    val mean = plotDensities.average()
    if (mean == 0.0) {
      return null
    }

    val sampleVariance =
        plotDensities.sumOf { (it - mean) * (it - mean) } / (plotDensities.size - 1)
    return MeasuredVariability(sqrt(sampleVariance) / mean * 100, plotDensities.size)
  }

  /** A central 90% chi-square interval for standard deviation, treating the mean as fixed. */
  fun plausibleRange(measurement: MeasuredVariability): PlausibleVariabilityRange {
    val degreesOfFreedom = measurement.numPlots.toDouble() - 1
    val distribution = ChiSquaredDistribution.of(degreesOfFreedom)
    return PlausibleVariabilityRange(
        high =
            measurement.variability *
                sqrt(degreesOfFreedom / distribution.inverseCumulativeProbability(0.05)),
        low =
            measurement.variability *
                sqrt(degreesOfFreedom / distribution.inverseCumulativeProbability(0.95)),
    )
  }

  fun recommendedPlotCountRange(
      measurement: MeasuredVariability,
      confidenceLevel: ConfidenceLevel,
      targetMarginOfError: BigDecimal,
  ): RecommendedPlotCountRange =
      recommendedPlotCountRange(
          measurement,
          plausibleRange(measurement),
          confidenceLevel.plotCountStudentsT,
          targetMarginOfError,
      )

  fun recommendedPlotCountRange(
      measurement: MeasuredVariability,
      range: PlausibleVariabilityRange,
      studentsT: BigDecimal,
      targetMarginOfError: BigDecimal,
  ): RecommendedPlotCountRange {
    fun estimate(variability: Double): PlotCountEstimate =
        PlotCountEstimate(
            variability,
            PlotCountCalculator.calculatePlotCounts(
                studentsT = studentsT,
                targetMarginOfError = targetMarginOfError,
                variability = BigDecimal.valueOf(variability),
            ),
        )

    return RecommendedPlotCountRange(
        best = estimate(measurement.variability),
        high = estimate(range.high),
        low = estimate(range.low),
    )
  }

  /**
   * Increases use the best estimate; decreases use the high estimate so uncertainty cannot justify
   * cutting too many plots. Both require at least two plots and 10% of the caller's current total.
   */
  fun recommendation(
      currentTotal: Long,
      range: RecommendedPlotCountRange,
  ): PlotCountRecommendation? {
    val bestTotal = range.best.plotCounts.deployableTotal
    val highTotal = range.high.plotCounts.deployableTotal
    val increase = bestTotal - currentTotal
    val decrease = currentTotal - highTotal
    if (increase >= 2 && increase * 10 >= currentTotal) {
      return PlotCountRecommendation(PlotCountRecommendationType.Increase, range.best)
    }
    if (decrease >= 2 && decrease * 10 >= currentTotal) {
      return PlotCountRecommendation(PlotCountRecommendationType.Decrease, range.high)
    }
    return null
  }
}
