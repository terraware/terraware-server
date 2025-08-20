package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

internal class AccessionModelValidationRulesTest : AccessionModelTest() {
  @Test
  fun `cannot add withdrawal with more seeds than exist in the accession`() {
    val accession = accession(remaining = seeds(10)).withCalculatedValues()

    assertThrows<IllegalArgumentException> {
      accession
          .copy(clock = tomorrowClock)
          .addWithdrawal(WithdrawalModel(date = today, withdrawn = seeds(11)))
    }
  }

  @Test
  fun `remaining quantity may not be negative`() {
    assertThrows<IllegalArgumentException>("negative seeds") { accession(remaining = seeds(-1)) }
    assertThrows<IllegalArgumentException>("negative weight") { accession(remaining = grams(-1)) }
  }

  @Test
  fun `cannot withdraw seeds if remaining quantity is not set`() {
    val initial = accession()

    assertThrows<IllegalArgumentException> {
      initial.copy(withdrawals = listOf(withdrawal(withdrawn = seeds(1))))
    }
  }

  @Test
  fun `cannot withdraw more seeds than most recent observed quantity`() {
    val initial = accession(remaining = seeds(10)).withCalculatedValues()

    assertThrows<IllegalArgumentException> {
      initial
          .copy(withdrawals = listOf(withdrawal(withdrawn = seeds(11))))
          .withCalculatedValues(initial)
    }
  }

  @Test
  fun `cannot withdraw more weight than most recent observed seed count`() {
    val initial =
        accession(subsetCount = 2, subsetWeight = grams(2), remaining = seeds(10))
            .withCalculatedValues()

    assertThrows<IllegalArgumentException> {
      initial
          .copy(withdrawals = listOf(withdrawal(withdrawn = grams(11))))
          .withCalculatedValues(initial)
    }
  }

  @Test
  fun `cannot remove remaining quantity once it has been set`() {
    val initial = accession(remaining = seeds(1)).withCalculatedValues()

    assertThrows<IllegalArgumentException> { initial.copy(remaining = null) }
  }

  @Test
  fun `cannot change remaining quantity from seeds to weight without subset info if withdrawals exist`() {
    val initial =
        accession(remaining = seeds(10))
            .withCalculatedValues()
            .copy(withdrawals = listOf(withdrawal(seeds(1))))
            .withCalculatedValues()

    assertThrows<IllegalArgumentException> { initial.copy(remaining = grams(1)) }
  }

  @Test
  fun `cannot change remaining quantity from weight to seeds without subset info if withdrawals exist`() {
    val initial =
        accession(remaining = grams(10))
            .withCalculatedValues()
            .copy(withdrawals = listOf(withdrawal(grams(1))))
            .withCalculatedValues()

    assertThrows<IllegalArgumentException> { initial.copy(remaining = seeds(1)) }
  }

  @Test
  fun `can change remaining quantity from seeds to weight without subset info if no withdrawals exist`() {
    val initial = accession(remaining = seeds(10)).withCalculatedValues()

    assertDoesNotThrow { initial.copy(remaining = grams(1)) }
  }

  @Test
  fun `can change remaining quantity from weight to seeds without subset info if no withdrawals exist`() {
    val initial = accession(remaining = grams(10)).withCalculatedValues()

    assertDoesNotThrow { initial.copy(remaining = seeds(1)) }
  }

  @Test
  fun `can change remaining quantity from seeds to weight if subset info exists`() {
    val initial =
        accession(subsetCount = 1, subsetWeight = grams(1), remaining = seeds(10))
            .withCalculatedValues()
            .copy(withdrawals = listOf(withdrawal(seeds(1))))
            .withCalculatedValues()

    assertDoesNotThrow { initial.copy(remaining = grams(10)) }
  }

  @Test
  fun `can change remaining quantity from weight to seeds if subset info exists`() {
    val initial =
        accession(subsetCount = 1, subsetWeight = grams(1), remaining = grams(10))
            .withCalculatedValues()
            .copy(withdrawals = listOf(withdrawal(grams(1))))
            .withCalculatedValues()

    assertDoesNotThrow { initial.copy(remaining = seeds(10)) }
  }

  @Test
  fun `cannot create viability test on weight-based accession without subset data`() {
    val initial = accession(remaining = grams(10)).withCalculatedValues()

    assertThrows<IllegalArgumentException> {
      initial
          .copy(clock = tomorrowClock)
          .addViabilityTest(viabilityTest(seedsTested = 1, startDate = null))
    }
  }

  @Test
  fun `cannot add viability test with more seeds sown than remaining quantity`() {
    val initial = accession(remaining = seeds(10)).withCalculatedValues()

    assertThrows<IllegalArgumentException> {
      initial
          .copy(clock = tomorrowClock)
          .addViabilityTest(viabilityTest(seedsTested = 11, startDate = null))
    }
  }

  @Test
  fun `can add viability test with more seeds sown than remaining quantity if it is older than the latest observed quantity`() {
    val initial = accession(remaining = seeds(10)).withCalculatedValues()

    assertDoesNotThrow {
      initial
          .copy(clock = tomorrowClock)
          .addViabilityTest(viabilityTest(seedsTested = 11, startDate = yesterday))
    }
  }

  @Test
  fun `can add viability test with fewer seeds sown than estimated seed count by weight`() {
    val initial =
        accession(subsetCount = 2, subsetWeight = grams(2), remaining = grams(10))
            .withCalculatedValues()

    assertDoesNotThrow {
      initial.copy(clock = tomorrowClock).addViabilityTest(viabilityTest(seedsTested = 9))
    }
  }

  @Test
  fun `cannot add new viability test with more seeds sown than estimated seed count by weight`() {
    val initial =
        accession(subsetCount = 2, subsetWeight = grams(2), remaining = grams(10))
            .withCalculatedValues()

    assertThrows<IllegalArgumentException> {
      initial
          .copy(clock = tomorrowClock)
          .addViabilityTest(viabilityTest(seedsTested = 11, startDate = null))
    }
  }

  @Test
  fun `cannot add new viability test with substrate that is not valid for test type`() {
    val initial = accession(remaining = seeds(10)).withCalculatedValues()

    assertThrows<IllegalArgumentException> {
      initial.addViabilityTest(
          viabilityTest(
              seedsTested = 1,
              substrate = ViabilityTestSubstrate.Paper,
              testType = ViabilityTestType.Nursery,
          )
      )
    }
  }
}
