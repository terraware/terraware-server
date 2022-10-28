package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.seeds
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionModelViabilityTest : AccessionModelTest() {
  @Test
  fun `viability percent is not auto-populated on v2 accessions when test results are added`() {
    val model =
        accession()
            .copy(isManualState = true, remaining = seeds(50))
            .withCalculatedValues(clock)
            .addViabilityTest(
                viabilityTest(
                    seedsTested = 10,
                    testResults =
                        listOf(
                            viabilityTestResult(recordingDate = january(2), seedsGerminated = 4))),
                clock)

    assertNull(model.totalViabilityPercent)
  }

  @Test
  fun `viability percent is not cleared on v2 accessions without tests`() {
    val percent = 77
    val model =
        accession()
            .copy(isManualState = true, remaining = seeds(50), totalViabilityPercent = percent)
            .withCalculatedValues(clock)

    assertEquals(percent, model.totalViabilityPercent)
  }

  @Test
  fun `viability percent is not overwritten on v2 accessions when test results are added`() {
    val percent = 16
    val model =
        accession()
            .copy(isManualState = true, remaining = seeds(50), totalViabilityPercent = percent)
            .withCalculatedValues(clock)
            .addViabilityTest(
                viabilityTest(
                    seedsTested = 5,
                    testResults =
                        listOf(
                            viabilityTestResult(recordingDate = january(2), seedsGerminated = 5))),
                clock)

    assertEquals(percent, model.totalViabilityPercent)
  }

  @Test
  fun `viability test withdrawnByUserId is propagated to new withdrawals`() {
    val withdrawnByUserId = UserId(1234)
    val model =
        accession()
            .copy(isManualState = true, remaining = seeds(10))
            .withCalculatedValues(clock)
            .addViabilityTest(
                viabilityTest(seedsTested = 1, withdrawnByUserId = withdrawnByUserId), clock)

    assertEquals(withdrawnByUserId, model.withdrawals[0].withdrawnByUserId)
  }

  @Test
  fun `viability test withdrawnByUserId change is propagated to existing withdrawals`() {
    val oldWithdrawnByUserId = UserId(1234)
    val newWithdrawnByUserId = UserId(5678)

    val viabilityTest = viabilityTest(seedsTested = 1, withdrawnByUserId = oldWithdrawnByUserId)
    val viabilityTestId = viabilityTest.id!!

    val model =
        accession()
            .copy(
                isManualState = true,
                remaining = seeds(10),
                viabilityTests = listOf(viabilityTest),
                withdrawals =
                    listOf(
                        withdrawal(
                            purpose = WithdrawalPurpose.ViabilityTesting,
                            viabilityTestId = viabilityTestId)))
            .withCalculatedValues(clock)
            .updateViabilityTest(viabilityTestId, clock) {
              it.copy(withdrawnByUserId = newWithdrawnByUserId)
            }

    assertEquals(newWithdrawnByUserId, model.withdrawals[0].withdrawnByUserId)
  }

  @Test
  fun `viability test update with null withdrawnByUserId does not change user ID of existing withdrawal`() {
    val withdrawnByUserId = UserId(1234)

    val viabilityTest = viabilityTest(seedsTested = 1, withdrawnByUserId = withdrawnByUserId)
    val viabilityTestId = viabilityTest.id!!

    val model =
        accession()
            .copy(
                isManualState = true,
                remaining = seeds(10),
                viabilityTests = listOf(viabilityTest),
                withdrawals =
                    listOf(
                        withdrawal(
                            purpose = WithdrawalPurpose.ViabilityTesting,
                            viabilityTestId = viabilityTestId)))
            .withCalculatedValues(clock)
            .updateViabilityTest(viabilityTestId, clock) { it.copy(withdrawnByUserId = null) }

    assertEquals(withdrawnByUserId, model.withdrawals[0].withdrawnByUserId)
  }

  @Test
  fun `change to seeds tested causes withdrawal and accession remaining seeds to update`() {
    val initialTest = viabilityTest(seedsTested = 1, startDate = null)
    val initial =
        accession()
            .copy(isManualState = true, remaining = seeds(100))
            .withCalculatedValues(clock)
            .addViabilityTest(initialTest, clock)

    assertEquals(seeds(99), initial.remaining, "Initial quantity")

    val updated =
        initial.updateViabilityTest(initialTest.id!!, tomorrowClock) { it.copy(seedsTested = 25) }

    assertEquals(seeds(75), updated.remaining, "Updated quantity")
  }

  @Test
  fun `change to seeds tested causes withdrawal and accession remaining weights to update`() {
    val initialTest = viabilityTest(seedsTested = 2, startDate = null)
    val initial =
        accession()
            .copy(
                isManualState = true,
                remaining = grams(100),
                subsetCount = 2,
                subsetWeightQuantity = grams(1))
            .withCalculatedValues(yesterdayClock)
            .addViabilityTest(initialTest, clock)

    assertEquals(grams(99), initial.remaining, "Initial quantity")

    val updated =
        initial.updateViabilityTest(initialTest.id!!, tomorrowClock) { it.copy(seedsTested = 50) }

    assertEquals(grams(75), updated.remaining, "Updated quantity")
  }

  @Test
  fun `cut test results are reflected in v1 cut test fields`() {
    val model =
        accession()
            .copy(isManualState = true, remaining = seeds(10))
            .addViabilityTest(
                viabilityTest(
                    ViabilityTestType.Cut,
                    seedsCompromised = 1,
                    seedsEmpty = 2,
                    seedsFilled = 3,
                    seedsTested = 6),
                clock)
            .addViabilityTest(
                viabilityTest(ViabilityTestType.Cut, seedsFilled = 1, seedsTested = 1), clock)

    assertEquals(1, model.cutTestSeedsCompromised, "Compromised")
    assertEquals(2, model.cutTestSeedsEmpty, "Empty")
    assertEquals(4, model.cutTestSeedsFilled, "Filled")
  }

  @Test
  fun `new cut test is added if v1 cut test fields are newly populated`() {
    val initial = accession(total = seeds(10))
    val updated =
        initial
            .copy(cutTestSeedsCompromised = 1, cutTestSeedsEmpty = 2, cutTestSeedsFilled = 3)
            .withCalculatedValues(clock, initial)

    val expectedTest =
        ViabilityTestModel(
            remaining = seeds(4),
            seedsCompromised = 1,
            seedsEmpty = 2,
            seedsFilled = 3,
            seedsTested = 6,
            testType = ViabilityTestType.Cut,
        )

    assertEquals(listOf(expectedTest), updated.viabilityTests)
  }

  @Test
  fun `cut test is removed if v1 cut test fields are cleared`() {
    val initial = accession(total = seeds(10)).withCalculatedValues(clock)
    val withCutTest = initial.copy(cutTestSeedsEmpty = 1).withCalculatedValues(clock, initial)
    val updated =
        withCutTest.copy(cutTestSeedsEmpty = null).withCalculatedValues(clock, withCutTest)

    assertNotEquals(emptyList<ViabilityTestModel>(), withCutTest.viabilityTests, "Before edit")
    assertEquals(emptyList<ViabilityTestModel>(), updated.viabilityTests, "After edit")
  }

  @Test
  fun `existing cut test is updated if v1 cut test fields are modified`() {
    val initialViabilityTest =
        ViabilityTestModel(
            id = ViabilityTestId(1),
            remaining = seeds(9),
            seedsFilled = 1,
            seedsTested = 1,
            testType = ViabilityTestType.Cut)
    val initialWithdrawal =
        withdrawal(
            remaining = seeds(9), viabilityTestId = initialViabilityTest.id, withdrawn = seeds(1))

    val initialAccession =
        accession(
                cutTestSeedsFilled = 1,
                total = seeds(10),
                viabilityTests = listOf(initialViabilityTest),
                withdrawals = listOf(initialWithdrawal))
            .withCalculatedValues(clock)
    val updatedAccession =
        initialAccession
            .copy(
                cutTestSeedsCompromised = 1,
                cutTestSeedsFilled = 2,
                // v1 PUT requests won't include the cut test or its withdrawal since we filter
                // them out of v1 GET responses.
                viabilityTests = emptyList(),
                withdrawals = emptyList())
            .withCalculatedValues(clock, initialAccession)

    val expectedViabilityTest =
        initialViabilityTest.copy(
            remaining = seeds(7), seedsCompromised = 1, seedsFilled = 2, seedsTested = 3)
    val expectedWithdrawal =
        initialWithdrawal.copy(
            estimatedCount = 3,
            remaining = seeds(7),
            viabilityTest = expectedViabilityTest,
            withdrawn = seeds(3))

    assertEquals(
        listOf(expectedViabilityTest),
        updatedAccession.viabilityTests,
        "Existing viability test should be updated")
    assertEquals(
        listOf(expectedWithdrawal),
        updatedAccession.withdrawals,
        "Existing withdrawal should be updated")
    assertEquals(seeds(7), updatedAccession.remaining, "Remaining quantity")
  }

  @Test
  fun `cut test on weight-based accession requires subset data`() {
    val initial = accession(total = grams(10)).withCalculatedValues(clock)

    assertThrows<IllegalArgumentException> {
      initial.copy(cutTestSeedsFilled = 1).withCalculatedValues(clock, initial)
    }
  }

  // SW-2026
  @Test
  fun `withdrawing after adding a viability test updates remaining quantity`() {
    fun fixedClock(seconds: Long) = Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC)

    val initial =
        AccessionModel(
                id = AccessionId(1L),
                accessionNumber = "dummy",
                createdTime = Instant.EPOCH,
                isManualState = true,
                remaining = grams(100),
                source = DataSource.Web,
                state = AccessionState.InStorage,
                subsetCount = 2,
                subsetWeightQuantity = grams(1),
            )
            .withCalculatedValues(fixedClock(1))

    val withTest =
        initial.addViabilityTest(
            ViabilityTestModel(
                seedsTested = 10,
                testType = ViabilityTestType.Lab,
            ),
            fixedClock(2))

    assertEquals(grams(95), withTest.remaining, "After test")

    val withWithdrawal =
        withTest.addWithdrawal(
            WithdrawalModel(
                date = LocalDate.EPOCH,
                purpose = WithdrawalPurpose.Nursery,
                withdrawn = grams(95),
            ),
            fixedClock(3))

    assertEquals(grams(0), withWithdrawal.remaining, "After withdrawal")
  }
}
