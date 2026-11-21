package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class PlotSelectionSettingTest {
  @Test
  fun `recalculation preserves exact integer totals from variance`() {
    val counts =
        StratumPlotCounts(
            studentsT = BigDecimal("1.96"),
            variance = BigDecimal("75"),
            errorMargin = BigDecimal("9.8"),
        )
    assertEquals(
        counts.copy(numPermanentPlots = 2, numTemporaryPlots = 1),
        counts.recalculatePlotCounts(ObservationType.Monitoring),
        "recalculation preserves exact integer totals from variance",
    )
    assertEquals(
        counts.copy(numPermanentPlots = 0, numTemporaryPlots = 3),
        counts.recalculatePlotCounts(ObservationType.BaselineSurvey),
        "recalculation preserves exact integer totals from variance",
    )
  }

  @ParameterizedTest
  @CsvSource("VeryLow,22.5,4,1", "Medium,15,8,3", "High,10,19,6", "VeryHigh,7,38,12")
  fun `precision setting preserves variance and recalculates counts`(
      level: PlotPrecisionLevel,
      margin: BigDecimal,
      permanent: Int,
      temporary: Int,
  ) {
    listOf(ObservationType.Monitoring, ObservationType.BiomassMeasurements).forEach { type ->
      assertEquals(
          initial.copy(
              mode = PlotSelectionMode.PrecisionLevel,
              plotPrecisionLevel = level,
              studentsT = BigDecimal("1.645"),
              errorMargin = margin,
              numPermanentPlots = permanent,
              numTemporaryPlots = temporary,
          ),
          initial.applySetting(type, PlotSelectionSetting.PrecisionLevel(level = level)),
          "precision setting preserves variance and recalculates counts",
      )
    }
  }

  @ParameterizedTest
  @CsvSource("25.0,5.0,1,1", "5.0,1.0,1,1", "25.00,200.00,80,26")
  fun `custom setting updates parameters and counts including boundary values`(
      margin: BigDecimal,
      variability: BigDecimal,
      permanent: Int,
      temporary: Int,
  ) {
    assertEquals(
        initial.copy(
            mode = PlotSelectionMode.Custom,
            plotPrecisionLevel = null,
            studentsT = BigDecimal("1.282"),
            errorMargin = margin,
            variance = variability.pow(2),
            numPermanentPlots = permanent,
            numTemporaryPlots = temporary,
        ),
        initial
            .copy(studentsT = BigDecimal("2.576"))
            .applySetting(
                ObservationType.Monitoring,
                PlotSelectionSetting.Custom(
                    confidenceLevel = ConfidenceLevel.Eighty,
                    targetMarginOfError = margin,
                    variability = variability,
                ),
            ),
        "custom setting updates parameters and counts including boundary values",
    )
  }

  @ParameterizedTest
  @EnumSource(ObservationType::class)
  fun `direct setting changes only mode level and counts`(type: ObservationType) {
    val permanent = if (type == ObservationType.BaselineSurvey) 0 else 4
    val temporary = if (type == ObservationType.BaselineSurvey) 4 else 0
    val current =
        initial.copy(
            mode = PlotSelectionMode.PrecisionLevel,
            plotPrecisionLevel = PlotPrecisionLevel.High,
        )
    assertEquals(
        current.copy(
            mode = PlotSelectionMode.Direct,
            plotPrecisionLevel = null,
            numPermanentPlots = permanent,
            numTemporaryPlots = temporary,
        ),
        current.applySetting(
            type,
            PlotSelectionSetting.Direct(
                numPermanentPlots = permanent,
                numTemporaryPlots = temporary,
            ),
        ),
        "direct setting changes only mode level and counts",
    )
  }

  @Test
  fun `baseline calculated counts are all temporary with a minimum of one`() {
    assertEquals(
        initial.copy(
            mode = PlotSelectionMode.PrecisionLevel,
            plotPrecisionLevel = PlotPrecisionLevel.Medium,
            studentsT = BigDecimal("1.645"),
            errorMargin = BigDecimal("15"),
            numPermanentPlots = 0,
            numTemporaryPlots = 11,
        ),
        initial.applySetting(
            ObservationType.BaselineSurvey,
            PlotSelectionSetting.PrecisionLevel(level = PlotPrecisionLevel.Medium),
        ),
        "baseline calculated counts are all temporary with a minimum of one",
    )
    val custom =
        PlotSelectionSetting.Custom(
            confidenceLevel = ConfidenceLevel.Eighty,
            targetMarginOfError = BigDecimal("25"),
            variability = BigDecimal.ONE,
        )
    assertEquals(
        initial.copy(
            mode = PlotSelectionMode.Custom,
            plotPrecisionLevel = null,
            errorMargin = BigDecimal("25"),
            variance = BigDecimal.ONE,
            numPermanentPlots = 0,
            numTemporaryPlots = 1,
        ),
        initial.applySetting(ObservationType.BaselineSurvey, custom),
        "baseline calculated counts are all temporary with a minimum of one",
    )
    val zero = initial.copy(variance = BigDecimal.ZERO)
    assertEquals(
        zero.copy(
            mode = PlotSelectionMode.PrecisionLevel,
            plotPrecisionLevel = PlotPrecisionLevel.Medium,
            studentsT = BigDecimal("1.645"),
            errorMargin = BigDecimal("15"),
            numPermanentPlots = 0,
            numTemporaryPlots = 1,
        ),
        zero.applySetting(
            ObservationType.BaselineSurvey,
            PlotSelectionSetting.PrecisionLevel(level = PlotPrecisionLevel.Medium),
        ),
        "baseline calculated counts are all temporary with a minimum of one",
    )
  }

  @ParameterizedTest
  @CsvSource("4.9,30", "25.1,30", "10.01,30", "15,0.9", "15,200.1", "15,30.01")
  fun `rejects custom values outside ranges or with excess precision`(
      margin: BigDecimal,
      variability: BigDecimal,
  ) {
    assertThrows<IllegalArgumentException> {
      initial.applySetting(
          ObservationType.Monitoring,
          PlotSelectionSetting.Custom(
              confidenceLevel = ConfidenceLevel.Ninety,
              targetMarginOfError = margin,
              variability = variability,
          ),
      )
    }
  }

  @ParameterizedTest
  @CsvSource(
      "Monitoring,0,1",
      "Monitoring,1,-1",
      "BiomassMeasurements,-1,1",
      "BaselineSurvey,1,1",
      "BaselineSurvey,-1,1",
      "BaselineSurvey,0,0",
      "BaselineSurvey,0,-1",
      "Monitoring,10001,1",
      "Monitoring,1,10001",
      "BiomassMeasurements,10001,1",
      "BiomassMeasurements,1,10001",
      "BaselineSurvey,0,10001",
  )
  fun `rejects invalid direct counts`(type: ObservationType, permanent: Int, temporary: Int) {
    assertThrows<IllegalArgumentException> {
      initial.applySetting(
          type,
          PlotSelectionSetting.Direct(numPermanentPlots = permanent, numTemporaryPlots = temporary),
      )
    }
  }

  @ParameterizedTest
  @EnumSource(ObservationType::class)
  fun `accepts direct counts at the per-count limit`(type: ObservationType) {
    val permanent = if (type == ObservationType.BaselineSurvey) 0 else 10000
    assertEquals(
        initial.copy(
            mode = PlotSelectionMode.Direct,
            plotPrecisionLevel = null,
            numPermanentPlots = permanent,
            numTemporaryPlots = 10000,
        ),
        initial.applySetting(
            type,
            PlotSelectionSetting.Direct(numPermanentPlots = permanent, numTemporaryPlots = 10000),
        ),
        "Each resulting count may equal 10000 independently",
    )
  }

  @Test
  fun `rejects calculated counts over the per-count limit`() {
    assertThrows<IllegalArgumentException> {
      initial.applySetting(
          ObservationType.BaselineSurvey,
          PlotSelectionSetting.Custom(
              confidenceLevel = ConfidenceLevel.NinetyNine,
              targetMarginOfError = BigDecimal("5"),
              variability = BigDecimal("200"),
          ),
      )
    }
    ObservationType.entries.forEach { type ->
      listOf(BigDecimal("1000000"), BigDecimal("1E30")).forEach { variance ->
        assertThrows<IllegalArgumentException> {
          initial
              .copy(variance = variance)
              .applySetting(
                  type,
                  PlotSelectionSetting.PrecisionLevel(level = PlotPrecisionLevel.VeryHigh),
              )
        }
      }
    }
  }

  @ParameterizedTest
  @CsvSource("4,1,28.6", "8,3,16.4")
  fun `derives variability confidence and finite sample expected precision`(
      permanent: Int,
      temporary: Int,
      expected: Double,
  ) {
    val counts =
        initial.copy(
            studentsT = BigDecimal("1.6450"),
            numPermanentPlots = permanent,
            numTemporaryPlots = temporary,
        )
    assertEquals(0, BigDecimal("30").compareTo(counts.variability), "Variability")
    assertEquals(
        ConfidenceLevel.Ninety,
        counts.confidenceLevel,
        "derives variability confidence and finite sample expected precision",
    )
    assertEquals(expected, counts.expectedPrecision!!, 0.05, "Expected precision")
  }

  @Test
  fun `nonstandard t and insufficient plot counts have no expected precision`() {
    val nonstandard = initial.copy(studentsT = BigDecimal("1.5"))
    assertNull(nonstandard.confidenceLevel, "Nonstandard t confidence")
    assertNull(nonstandard.expectedPrecision, "Nonstandard t precision")
    listOf(0, 1).forEach { total ->
      assertNull(
          initial.copy(numPermanentPlots = total, numTemporaryPlots = 0).expectedPrecision,
          "Precision for $total plots",
      )
    }
  }

  private val initial =
      StratumPlotCounts(
          mode = PlotSelectionMode.Direct,
          studentsT = BigDecimal("1.282"),
          errorMargin = BigDecimal("12"),
          variance = BigDecimal("900"),
          numPermanentPlots = 2,
          numTemporaryPlots = 3,
      )
}
