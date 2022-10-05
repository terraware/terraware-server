package com.terraformation.backend.seedbank.model.accession

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.seeds
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class AccessionModelViabilityTest : AccessionModelTest() {
  @Test
  fun `test without seeds sown is ignored`() {
    val model =
        accession(
            total = seeds(10),
            viabilityTests =
                listOf(
                    viabilityTest(
                        testResults =
                            listOf(
                                viabilityTestResult(
                                    recordingDate = january(2), seedsGerminated = 1),
                            ),
                    ),
                ),
        )

    Assertions.assertNull(
        model.calculateLatestViabilityRecordingDate(), "Latest viability test date")
    Assertions.assertNull(model.calculateLatestViabilityPercent(), "Latest viability percent")
    Assertions.assertNull(model.calculateTotalViabilityPercent(), "Total viability percent")
  }

  @Test
  fun `test with most recent recording date is selected`() {
    val model =
        accession(
            total = seeds(100),
            viabilityTests =
                listOf(
                    viabilityTest(
                        testType = ViabilityTestType.Lab,
                        startDate = january(1),
                        seedsTested = 5,
                        testResults =
                            listOf(
                                viabilityTestResult(
                                    recordingDate = january(13), seedsGerminated = 1),
                            ),
                    ),
                    viabilityTest(
                        testType = ViabilityTestType.Lab,
                        startDate = january(3),
                        seedsTested = 4,
                        testResults =
                            listOf(
                                viabilityTestResult(
                                    recordingDate = january(14), seedsGerminated = 1),
                                viabilityTestResult(
                                    recordingDate = january(11), seedsGerminated = 1),
                            ),
                    ),
                    viabilityTest(
                        testType = ViabilityTestType.Lab,
                        startDate = january(8), // most recent start date
                        seedsTested = 3,
                        testResults =
                            listOf(
                                viabilityTestResult(
                                    recordingDate = january(12), seedsGerminated = 3),
                            ),
                    ),
                ),
        )

    Assertions.assertEquals(
        january(14), model.calculateLatestViabilityRecordingDate(), "Latest viability test date")
    Assertions.assertEquals(50, model.calculateLatestViabilityPercent(), "Latest viability percent")
  }

  @Test
  fun `total viability percentage includes all viability tests and cut test`() {
    val model =
        accession(
            total = seeds(30),
            viabilityTests =
                listOf(
                    viabilityTest(
                        seedsTested = 10,
                        testResults =
                            listOf(
                                viabilityTestResult(
                                    recordingDate = january(2), seedsGerminated = 4),
                                viabilityTestResult(
                                    recordingDate = january(3), seedsGerminated = 3),
                            ),
                    ),
                    viabilityTest(
                        startDate = january(8),
                        seedsTested = 5,
                        testResults =
                            listOf(
                                viabilityTestResult(
                                    recordingDate = january(9), seedsGerminated = 1),
                            ),
                    ),
                ),
            cutTestSeedsCompromised = 2,
            cutTestSeedsEmpty = 1,
            cutTestSeedsFilled = 5,
        )

    // 13 seeds viable of 23 tested
    val expectedPercentage = 56

    Assertions.assertEquals(expectedPercentage, model.calculateTotalViabilityPercent())
  }

  @Test
  fun `total viability percentage not set if no test results`() {
    val model =
        accession(
            total = seeds(20),
            viabilityTests = listOf(viabilityTest(seedsTested = 10)),
        )

    Assertions.assertNull(model.calculateTotalViabilityPercent())
  }

  @Test
  fun `total viability percentage ignores cut test results if values are missing`() {
    val missingEmpty =
        accession(
            total = seeds(50),
            viabilityTests =
                listOf(
                    viabilityTest(
                        seedsTested = 10,
                        testResults =
                            listOf(
                                viabilityTestResult(
                                    recordingDate = january(2), seedsGerminated = 4),
                            ),
                    ),
                ),
            cutTestSeedsCompromised = 2,
            cutTestSeedsFilled = 5,
        )

    val missingCompromised =
        missingEmpty.copy(cutTestSeedsEmpty = 1, cutTestSeedsCompromised = null)
    val missingFilled = missingEmpty.copy(cutTestSeedsEmpty = 1, cutTestSeedsFilled = null)

    // 4 seeds germinated out of 10 sown; cut test is ignored
    val expectedPercentage = 40

    Assertions.assertEquals(
        expectedPercentage, missingEmpty.calculateTotalViabilityPercent(), "Empty count missing")
    Assertions.assertEquals(
        expectedPercentage,
        missingCompromised.calculateTotalViabilityPercent(),
        "Compromised count missing")
    Assertions.assertEquals(
        expectedPercentage, missingFilled.calculateTotalViabilityPercent(), "Filled count missing")
  }

  @Test
  fun `most recent recording date is used as test date`() {
    val model =
        accession(
            total = seeds(10),
            viabilityTests =
                listOf(
                    viabilityTest(
                        seedsTested = 1,
                        testResults =
                            listOf(
                                viabilityTestResult(
                                    recordingDate = january(3), seedsGerminated = 1),
                                viabilityTestResult(
                                    recordingDate = january(4), seedsGerminated = 2),
                                viabilityTestResult(
                                    recordingDate = january(2), seedsGerminated = 3),
                            ),
                    ),
                ),
        )

    Assertions.assertEquals(january(4), model.calculateLatestViabilityRecordingDate())
  }

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

    Assertions.assertNull(model.totalViabilityPercent)
  }

  @Test
  fun `viability percent is not cleared on v2 accessions without tests`() {
    val percent = 77
    val model =
        accession()
            .copy(isManualState = true, remaining = seeds(50), totalViabilityPercent = percent)
            .withCalculatedValues(clock)

    Assertions.assertEquals(percent, model.totalViabilityPercent)
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

    Assertions.assertEquals(percent, model.totalViabilityPercent)
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

    Assertions.assertEquals(withdrawnByUserId, model.withdrawals[0].withdrawnByUserId)
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

    Assertions.assertEquals(newWithdrawnByUserId, model.withdrawals[0].withdrawnByUserId)
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

    Assertions.assertEquals(withdrawnByUserId, model.withdrawals[0].withdrawnByUserId)
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

    Assertions.assertEquals(1, model.cutTestSeedsCompromised, "Compromised")
    Assertions.assertEquals(2, model.cutTestSeedsEmpty, "Empty")
    Assertions.assertEquals(4, model.cutTestSeedsFilled, "Filled")
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

    Assertions.assertEquals(listOf(expectedTest), updated.viabilityTests)
  }

  @Test
  fun `cut test is removed if v1 cut test fields are cleared`() {
    val initial = accession(total = seeds(10)).withCalculatedValues(clock)
    val withCutTest = initial.copy(cutTestSeedsEmpty = 1).withCalculatedValues(clock, initial)
    val updated =
        withCutTest.copy(cutTestSeedsEmpty = null).withCalculatedValues(clock, withCutTest)

    Assertions.assertNotEquals(
        emptyList<ViabilityTestModel>(), withCutTest.viabilityTests, "Before edit")
    Assertions.assertEquals(emptyList<ViabilityTestModel>(), updated.viabilityTests, "After edit")
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

    Assertions.assertEquals(
        listOf(expectedViabilityTest),
        updatedAccession.viabilityTests,
        "Existing viability test should be updated")
    Assertions.assertEquals(
        listOf(expectedWithdrawal),
        updatedAccession.withdrawals,
        "Existing withdrawal should be updated")
    Assertions.assertEquals(seeds(7), updatedAccession.remaining, "Remaining quantity")
  }

  @Test
  fun `cut test on weight-based accession requires subset data`() {
    val initial = accession(total = grams(10)).withCalculatedValues(clock)

    assertThrows<IllegalArgumentException> {
      initial.copy(cutTestSeedsFilled = 1).withCalculatedValues(clock, initial)
    }
  }
}
