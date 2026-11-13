package com.terraformation.backend.tracking.model

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PlotCountCalculatorTest {
  @ParameterizedTest
  @CsvSource(
      "Seventy,1.093",
      "Eighty,1.372",
      "Ninety,1.812",
      "NinetyFive,2.228",
      "NinetyNine,3.169",
  )
  fun `eleven plots use two sided t quantiles with ten degrees of freedom`(
      confidence: ConfidenceLevel,
      finiteT: Double,
  ) {
    assertEquals(finiteT, confidence.studentsTForPlotCount(11)!!, 0.0005, "Finite df t")
  }

  @ParameterizedTest
  @CsvSource(
      "VeryLow,5,4,1",
      "Low,7,5,2",
      "Medium,11,8,3",
      "High,25,19,6",
      "VeryHigh,50,38,12",
  )
  fun `precision levels determine plot counts at thirty percent variability`(
      precision: PrecisionLevel,
      total: Int,
      permanent: Int,
      temporary: Int,
  ) {
    assertEquals(
        CalculatedPlotCounts(total, permanent, temporary),
        PlotCountCalculator.calculatePlotCounts(precision, BigDecimal("30")),
    )
  }

  @ParameterizedTest
  @CsvSource(
      "1.645,21.5,10,13,10,3",
      "1,1.000000000000000001,1,2,2,1",
  )
  fun `plot count rounds up and splits 75 percent permanent`(
      studentsT: BigDecimal,
      variability: BigDecimal,
      margin: BigDecimal,
      total: Int,
      permanent: Int,
      temporary: Int,
  ) {
    assertEquals(
        CalculatedPlotCounts(total, permanent, temporary),
        PlotCountCalculator.calculatePlotCounts(studentsT, variability, margin),
    )
  }

  @Test
  fun `plot count does not round up when the formula result is a whole number`() {
    assertEquals(
        CalculatedPlotCounts(1, 1, 1),
        PlotCountCalculator.calculatePlotCounts(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
    )
  }

  @Test
  fun `plot count places at least one permanent and one temporary plot`() {
    assertEquals(
        CalculatedPlotCounts(1, 1, 1),
        PlotCountCalculator.calculatePlotCounts(
            BigDecimal("1.282"),
            BigDecimal("5"),
            BigDecimal("25"),
        ),
    )
  }

  @ParameterizedTest
  @CsvSource("5,30,28.6", "13,21.5,10.6")
  fun `expected precision accounts for plot count and variability`(
      numPlots: Int,
      variability: BigDecimal,
      expected: Double,
  ) {
    assertEquals(
        expected,
        PlotCountCalculator.expectedPrecision(ConfidenceLevel.Ninety, numPlots, variability)!!,
        0.05,
        "Expected precision",
    )
  }

  @Test
  fun `expected precision is undefined for one plot`() {
    assertNull(PlotCountCalculator.expectedPrecision(ConfidenceLevel.Ninety, 1, BigDecimal.TEN))
  }
}
