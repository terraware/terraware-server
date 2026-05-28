package com.terraformation.backend.tracking.model

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ObservationResultsModelTest {
  @Test
  fun `calculateAreaWeightedSurvivalRate uses area weighting`() {
    // Values from Plant Tracking Math v2 spreadsheet — Site 1, Observation 2.
    // Stratum SRs are stored as integer percentages, so the inputs here are pre-rounded
    // (70/106/102), which gives 99 rather than the spreadsheet's full-precision 0.9963.
    // Zone 1: SR 70%, observed substratum area 104.10 ha (subzone 1 only at obs 1)
    // Zone 2: SR 106%, observed substratum area 328.07 ha (subzones 3+4 at obs 1)
    // Zone 3: SR 102%, observed substratum area 340.69 ha (subzones 5+6 at obs 2)
    val strata =
        listOf(
            StratumWithObservedArea(
                survivalRate = 70,
                observedSubstratumAreaHa = BigDecimal("104.1009464"),
            ),
            StratumWithObservedArea(
                survivalRate = 106,
                observedSubstratumAreaHa = BigDecimal("328.0757098"),
            ),
            StratumWithObservedArea(
                survivalRate = 102,
                observedSubstratumAreaHa = BigDecimal("340.6940063"),
            ),
        )

    assertEquals(99, calculateAreaWeightedSurvivalRate(strata))
  }

  @Test
  fun `calculateAreaWeightedSurvivalRate returns null if any stratum has null survival rate`() {
    val strata =
        listOf(
            StratumWithObservedArea(
                survivalRate = 80,
                observedSubstratumAreaHa = BigDecimal("100"),
            ),
            StratumWithObservedArea(
                survivalRate = null,
                observedSubstratumAreaHa = BigDecimal("100"),
            ),
        )

    assertNull(calculateAreaWeightedSurvivalRate(strata))
  }

  @Test
  fun `calculateAreaWeightedSurvivalRate returns null if total observed area is zero`() {
    val strata =
        listOf(
            StratumWithObservedArea(
                survivalRate = 80,
                observedSubstratumAreaHa = BigDecimal.ZERO,
            ),
        )

    assertNull(calculateAreaWeightedSurvivalRate(strata))
  }

  @Test
  fun `calculateAreaWeightedSurvivalRate returns null on empty input`() {
    assertNull(calculateAreaWeightedSurvivalRate(emptyList()))
  }
}
