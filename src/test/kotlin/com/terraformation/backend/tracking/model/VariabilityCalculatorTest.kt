package com.terraformation.backend.tracking.model

import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class VariabilityCalculatorTest {
  @Test
  fun `measured variability uses sample standard deviation of plot densities`() {
    val densities = listOf(1200, 1500, 900, 1800, 1100, 1400, 1300, 1600, 1000, 1200)

    val result = VariabilityCalculator.measuredVariability(densities.map { it.toBigDecimal() })!!

    assertEquals(
        MeasuredVariability(21.452821193, 10),
        result.copy(variability = result.variability.rounded(9)),
    )
  }

  @Test
  fun `empty completed plots contribute zero density to variability`() {
    val result =
        VariabilityCalculator.measuredVariability(listOf(BigDecimal.ZERO, BigDecimal.TEN))!!

    assertEquals(
        MeasuredVariability(141.421356237, 2),
        result.copy(variability = result.variability.rounded(9)),
    )
  }

  @ParameterizedTest
  @ValueSource(ints = [0, 1])
  fun `fewer than two completed plots have no measured variability`(numPlots: Int) {
    assertNull(VariabilityCalculator.measuredVariability(List(numPlots) { BigDecimal.TEN }))
  }

  @Test
  fun `all empty plots have no measured variability`() {
    assertNull(VariabilityCalculator.measuredVariability(List(12) { BigDecimal.ZERO }))
  }

  @ParameterizedTest
  @CsvSource("10,21.5,15.7,35.4", "3,45,26.0,198.7")
  fun `plausible range uses asymmetric chi square bounds`(
      numPlots: Int,
      variability: Double,
      low: Double,
      high: Double,
  ) {
    val result = VariabilityCalculator.plausibleRange(MeasuredVariability(variability, numPlots))

    assertEquals(
        PlausibleVariabilityRange(low, high),
        result.copy(high = result.high.rounded(1), low = result.low.rounded(1)),
    )
  }

  @Test
  fun `plausible range is not capped at two hundred percent`() {
    val result = VariabilityCalculator.plausibleRange(MeasuredVariability(30.0, 2))

    assertEquals(478.417182, result.high, 0.000001, "High variability")
  }

  @ParameterizedTest
  @MethodSource("plotCountRanges")
  fun `plot count range uses measured variability and both plausible bounds`(
      measurement: MeasuredVariability,
      expected: RecommendedPlotCountRange,
  ) {
    val result =
        VariabilityCalculator.recommendedPlotCountRange(
            measurement,
            ConfidenceLevel.Ninety,
            BigDecimal.TEN,
        )

    assertEquals(
        expected,
        result.copy(
            high = result.high.copy(variability = result.high.variability.rounded(1)),
            low = result.low.copy(variability = result.low.variability.rounded(1)),
        ),
    )
  }

  @ParameterizedTest
  @CsvSource("11,32,15,11,Increase", "25,12,10,25,Decrease")
  fun `recommendation uses best estimate for increases and high estimate for decreases`(
      numPlots: Int,
      variability: Double,
      margin: BigDecimal,
      currentTotal: Int,
      type: PlotCountRecommendationType,
  ) {
    val range =
        VariabilityCalculator.recommendedPlotCountRange(
            MeasuredVariability(variability, numPlots),
            ConfidenceLevel.Ninety,
            margin,
        )

    val expectedEstimate =
        when (type) {
          PlotCountRecommendationType.Increase -> range.best
          PlotCountRecommendationType.Decrease -> range.high
        }
    assertEquals(
        PlotCountRecommendation(type, expectedEstimate),
        VariabilityCalculator.recommendation(currentTotal, range),
    )
  }

  @Test
  fun `decrease is withheld when high estimate still exceeds current total`() {
    val range =
        VariabilityCalculator.recommendedPlotCountRange(
            MeasuredVariability(21.5, 10),
            ConfidenceLevel.Ninety,
            BigDecimal.TEN,
        )

    assertNull(VariabilityCalculator.recommendation(25, range))
  }

  @ParameterizedTest
  @CsvSource(
      "10,11,12,",
      "10,8,9,",
      "30,32,33,",
      "30,27,28,",
      "20,22,23,Increase",
      "20,17,18,Decrease",
  )
  fun `recommendations require at least two plots and ten percent`(
      currentTotal: Int,
      bestTotal: Int,
      highTotal: Int,
      expectedType: PlotCountRecommendationType?,
  ) {
    val best = PlotCountEstimate(30.0, CalculatedPlotCounts(bestTotal, 1, bestTotal - 1))
    val high = PlotCountEstimate(40.0, CalculatedPlotCounts(highTotal, 1, highTotal - 1))
    val range = RecommendedPlotCountRange(best, best, high)

    val result = VariabilityCalculator.recommendation(currentTotal, range)

    val expected =
        when (expectedType) {
          PlotCountRecommendationType.Increase -> PlotCountRecommendation(expectedType, best)
          PlotCountRecommendationType.Decrease -> PlotCountRecommendation(expectedType, high)
          null -> null
        }
    assertEquals(expected, result)
  }

  private fun Double.rounded(scale: Int): Double =
      toBigDecimal().setScale(scale, RoundingMode.HALF_UP).toDouble()

  companion object {
    @JvmStatic
    fun plotCountRanges(): List<Arguments> =
        listOf(
            Arguments.of(
                MeasuredVariability(21.5, 10),
                RecommendedPlotCountRange(
                    best = PlotCountEstimate(21.5, CalculatedPlotCounts(13, 10, 3)),
                    high = PlotCountEstimate(35.4, CalculatedPlotCounts(34, 26, 8)),
                    low = PlotCountEstimate(15.7, CalculatedPlotCounts(7, 5, 2)),
                ),
            ),
            Arguments.of(
                MeasuredVariability(12.0, 25),
                RecommendedPlotCountRange(
                    best = PlotCountEstimate(12.0, CalculatedPlotCounts(4, 3, 1)),
                    high = PlotCountEstimate(15.8, CalculatedPlotCounts(7, 5, 2)),
                    low = PlotCountEstimate(9.7, CalculatedPlotCounts(3, 2, 1)),
                ),
            ),
        )
  }
}
