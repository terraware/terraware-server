package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class AccessionModelViabilityTest : AccessionModelTest() {
  @Test
  fun `viability percent is not auto-populated on v2 accessions when test results are added`() {
    val model =
        accession(remaining = seeds(50))
            .withCalculatedValues()
            .addViabilityTest(
                viabilityTest(
                    seedsTested = 10,
                    testResults =
                        listOf(
                            viabilityTestResult(recordingDate = january(2), seedsGerminated = 4)
                        ),
                )
            )

    assertNull(model.totalViabilityPercent)
  }

  @Test
  fun `viability percent is not cleared on v2 accessions without tests`() {
    val percent = 77
    val model =
        accession(remaining = seeds(50))
            .copy(totalViabilityPercent = percent)
            .withCalculatedValues()

    assertEquals(percent, model.totalViabilityPercent)
  }

  @Test
  fun `viability percent is not overwritten on v2 accessions when test results are added`() {
    val percent = 16
    val model =
        accession(remaining = seeds(50))
            .copy(totalViabilityPercent = percent)
            .withCalculatedValues()
            .addViabilityTest(
                viabilityTest(
                    seedsTested = 5,
                    testResults =
                        listOf(
                            viabilityTestResult(recordingDate = january(2), seedsGerminated = 5)
                        ),
                )
            )

    assertEquals(percent, model.totalViabilityPercent)
  }

  @Test
  fun `viability test withdrawnByUserId is propagated to new withdrawals`() {
    val withdrawnByUserId = UserId(1234)
    val model =
        accession(remaining = seeds(10))
            .withCalculatedValues()
            .addViabilityTest(viabilityTest(seedsTested = 1, withdrawnByUserId = withdrawnByUserId))

    assertEquals(withdrawnByUserId, model.withdrawals[0].withdrawnByUserId)
  }

  @Test
  fun `viability test withdrawnByUserId change is propagated to existing withdrawals`() {
    val oldWithdrawnByUserId = UserId(1234)
    val newWithdrawnByUserId = UserId(5678)

    val viabilityTest = viabilityTest(seedsTested = 1, withdrawnByUserId = oldWithdrawnByUserId)
    val viabilityTestId = viabilityTest.id!!

    val model =
        accession(
                remaining = seeds(10),
                viabilityTests = listOf(viabilityTest),
                withdrawals =
                    listOf(
                        withdrawal(
                            purpose = WithdrawalPurpose.ViabilityTesting,
                            viabilityTestId = viabilityTestId,
                        )
                    ),
            )
            .withCalculatedValues()
            .updateViabilityTest(viabilityTestId) {
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
        accession(
                remaining = seeds(10),
                viabilityTests = listOf(viabilityTest),
                withdrawals =
                    listOf(
                        withdrawal(
                            purpose = WithdrawalPurpose.ViabilityTesting,
                            viabilityTestId = viabilityTestId,
                        )
                    ),
            )
            .withCalculatedValues()
            .updateViabilityTest(viabilityTestId) { it.copy(withdrawnByUserId = null) }

    assertEquals(withdrawnByUserId, model.withdrawals[0].withdrawnByUserId)
  }

  @Test
  fun `change to seeds tested causes withdrawal and accession remaining seeds to update`() {
    val initialTest = viabilityTest(seedsTested = 1, startDate = null)
    val initial =
        accession(remaining = seeds(100)).withCalculatedValues().addViabilityTest(initialTest)

    assertEquals(seeds(99), initial.remaining, "Initial quantity")

    val updated =
        initial.copy(clock = tomorrowClock).updateViabilityTest(initialTest.id!!) {
          it.copy(seedsTested = 25)
        }

    assertEquals(seeds(75), updated.remaining, "Updated quantity")
  }

  @Test
  fun `change to seeds tested causes withdrawal and accession remaining weights to update`() {
    val initialTest = viabilityTest(seedsTested = 2, startDate = null)
    val initial =
        accession(remaining = grams(100), subsetCount = 2, subsetWeight = grams(1))
            .copy(clock = yesterdayClock)
            .withCalculatedValues()
            .addViabilityTest(initialTest)

    assertEquals(grams(99), initial.remaining, "Initial quantity")

    val updated =
        initial.copy(clock = tomorrowClock).updateViabilityTest(initialTest.id!!) {
          it.copy(seedsTested = 50)
        }

    assertEquals(grams(75), updated.remaining, "Updated quantity")
  }

  // SW-2026
  @Test
  fun `withdrawing after adding a viability test updates remaining quantity`() {
    fun fixedClock(seconds: Long) = Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC)

    val initial =
        AccessionModel(
                id = AccessionId(1L),
                accessionNumber = "dummy",
                clock = fixedClock(1),
                createdTime = Instant.EPOCH,
                remaining = grams(100),
                source = DataSource.Web,
                state = AccessionState.InStorage,
                subsetCount = 2,
                subsetWeightQuantity = grams(1),
            )
            .withCalculatedValues()

    val withTest =
        initial
            .copy(clock = fixedClock(2))
            .addViabilityTest(
                ViabilityTestModel(seedsTested = 10, testType = ViabilityTestType.Lab)
            )

    assertEquals(grams(95), withTest.remaining, "After test")

    val withWithdrawal =
        withTest
            .copy(clock = fixedClock(3))
            .addWithdrawal(
                WithdrawalModel(
                    date = LocalDate.EPOCH,
                    purpose = WithdrawalPurpose.Nursery,
                    withdrawn = grams(95),
                )
            )

    assertEquals(grams(0), withWithdrawal.remaining, "After withdrawal")
  }
}
