package com.terraformation.backend.seedbank.model

import com.terraformation.backend.assertJsonEquals
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestResultId
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.milligrams
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

internal class AccessionModelTest {
  private val today = january(2)
  private val todayInstant = today.atStartOfDay(ZoneOffset.UTC).toInstant()
  private val clock: Clock = Clock.fixed(todayInstant, ZoneOffset.UTC)
  private val tomorrow = today.plusDays(1)
  private val tomorrowInstant = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant()
  private val tomorrowClock = Clock.fixed(tomorrowInstant, ZoneOffset.UTC)
  private val yesterday = today.minusDays(1)
  private val yesterdayInstant = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant()
  private val yesterdayClock = Clock.fixed(yesterdayInstant, ZoneOffset.UTC)

  private var viabilityTestResultId = ViabilityTestResultId(1)
  private var viabilityTestId = ViabilityTestId(1)
  private var withdrawalId = WithdrawalId(1)
  private var defaultState = AccessionState.Processing

  private fun accession(
      viabilityTests: List<ViabilityTestModel> = emptyList(),
      cutTestSeedsCompromised: Int? = null,
      cutTestSeedsEmpty: Int? = null,
      cutTestSeedsFilled: Int? = null,
      dryingEndDate: LocalDate? = null,
      dryingStartDate: LocalDate? = null,
      processingStartDate: LocalDate? = null,
      subsetCount: Int? = null,
      state: AccessionState = defaultState,
      storageLocation: String? = null,
      storagePackets: Int? = null,
      storageStartDate: LocalDate? = null,
      subsetWeight: SeedQuantityModel? = null,
      total: SeedQuantityModel? = null,
      withdrawals: List<WithdrawalModel> = emptyList(),
      processingMethod: ProcessingMethod? =
          total?.let { if (it.grams != null) ProcessingMethod.Weight else ProcessingMethod.Count },
  ): AccessionModel {
    return AccessionModel(
        id = AccessionId(1L),
        accessionNumber = "dummy",
        createdTime = clock.instant(),
        cutTestSeedsCompromised = cutTestSeedsCompromised,
        cutTestSeedsEmpty = cutTestSeedsEmpty,
        cutTestSeedsFilled = cutTestSeedsFilled,
        dryingEndDate = dryingEndDate,
        dryingStartDate = dryingStartDate,
        processingMethod = processingMethod,
        processingStartDate = processingStartDate,
        source = DataSource.Web,
        state = state,
        storageLocation = storageLocation,
        storagePackets = storagePackets,
        storageStartDate = storageStartDate,
        subsetCount = subsetCount,
        subsetWeightQuantity = subsetWeight,
        total = total,
        viabilityTests = viabilityTests,
        withdrawals = withdrawals,
    )
  }

  private fun nextViabilityTestId(): ViabilityTestId {
    val current = viabilityTestId
    viabilityTestId = ViabilityTestId(current.value + 1)
    return current
  }

  private fun viabilityTest(
      testType: ViabilityTestType = ViabilityTestType.Lab,
      startDate: LocalDate? = january(1),
      seedsTested: Int? = null,
      testResults: List<ViabilityTestResultModel>? = null,
      remaining: SeedQuantityModel? = null,
      substrate: ViabilityTestSubstrate? = null,
      withdrawnByUserId: UserId? = null,
      seedsCompromised: Int? = null,
      seedsEmpty: Int? = null,
      seedsFilled: Int? = null,
  ): ViabilityTestModel {
    return ViabilityTestModel(
        accessionId = AccessionId(1),
        id = nextViabilityTestId(),
        remaining = remaining,
        seedsCompromised = seedsCompromised,
        seedsEmpty = seedsEmpty,
        seedsFilled = seedsFilled,
        seedsTested = seedsTested,
        startDate = startDate,
        substrate = substrate,
        testResults = testResults,
        testType = testType,
        withdrawnByUserId = withdrawnByUserId,
    )
  }

  private fun nextViabilityTestResultId(): ViabilityTestResultId {
    val current = viabilityTestResultId
    viabilityTestResultId = ViabilityTestResultId(current.value + 1)
    return current
  }

  private fun viabilityTestResult(
      recordingDate: LocalDate = january(2),
      seedsGerminated: Int = 1
  ): ViabilityTestResultModel {
    return ViabilityTestResultModel(
        id = nextViabilityTestResultId(),
        recordingDate = recordingDate,
        seedsGerminated = seedsGerminated,
        testId = viabilityTestId)
  }

  private fun nextWithdrawalId(): WithdrawalId {
    val current = withdrawalId
    withdrawalId = WithdrawalId(current.value + 1)
    return current
  }

  private fun withdrawal(
      withdrawn: SeedQuantityModel = seeds(1),
      date: LocalDate = january(3),
      viabilityTestId: ViabilityTestId? = null,
      purpose: WithdrawalPurpose =
          if (viabilityTestId != null) WithdrawalPurpose.ViabilityTesting
          else WithdrawalPurpose.Other,
      remaining: SeedQuantityModel =
          if (withdrawn.units == SeedQuantityUnits.Seeds) seeds(10) else grams(10),
      createdTime: Instant = clock.instant(),
      id: WithdrawalId? = nextWithdrawalId(),
      withdrawnByUserId: UserId? = null,
      estimatedCount: Int? =
          if (withdrawn.units == SeedQuantityUnits.Seeds) withdrawn.quantity.toInt() else null,
  ): WithdrawalModel {
    return WithdrawalModel(
        createdTime = createdTime,
        date = date,
        estimatedCount = estimatedCount,
        id = id,
        purpose = purpose,
        remaining = remaining,
        viabilityTestId = viabilityTestId,
        withdrawn = withdrawn,
        withdrawnByUserId = withdrawnByUserId,
    )
  }

  private fun january(day: Int): LocalDate {
    return LocalDate.of(2021, 1, day)
  }

  @Nested
  inner class ViabilityTestCalculations {
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

      assertNull(model.calculateLatestViabilityRecordingDate(), "Latest viability test date")
      assertNull(model.calculateLatestViabilityPercent(), "Latest viability percent")
      assertNull(model.calculateTotalViabilityPercent(), "Total viability percent")
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

      assertEquals(
          january(14), model.calculateLatestViabilityRecordingDate(), "Latest viability test date")
      assertEquals(50, model.calculateLatestViabilityPercent(), "Latest viability percent")
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

      assertEquals(expectedPercentage, model.calculateTotalViabilityPercent())
    }

    @Test
    fun `total viability percentage not set if no test results`() {
      val model =
          accession(
              total = seeds(20),
              viabilityTests = listOf(viabilityTest(seedsTested = 10)),
          )

      assertNull(model.calculateTotalViabilityPercent())
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

      assertEquals(
          expectedPercentage, missingEmpty.calculateTotalViabilityPercent(), "Empty count missing")
      assertEquals(
          expectedPercentage,
          missingCompromised.calculateTotalViabilityPercent(),
          "Compromised count missing")
      assertEquals(
          expectedPercentage,
          missingFilled.calculateTotalViabilityPercent(),
          "Filled count missing")
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

      assertEquals(january(4), model.calculateLatestViabilityRecordingDate())
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
                              viabilityTestResult(
                                  recordingDate = january(2), seedsGerminated = 4))),
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
                              viabilityTestResult(
                                  recordingDate = january(2), seedsGerminated = 5))),
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
  }

  @Suppress("UnusedDataClassCopyResult")
  @Nested
  inner class ValidationRules {
    @Test
    fun `cannot specify accession size in seeds if processing method is Weight`() {
      assertThrows<IllegalArgumentException> {
        accession(processingMethod = ProcessingMethod.Weight, total = seeds(1))
      }
    }

    @Test
    fun `cannot specify accession size in weight if processing method is Count`() {
      assertThrows<IllegalArgumentException> {
        accession(processingMethod = ProcessingMethod.Count, total = grams(1))
      }
    }

    @Test
    fun `processing method is required if total size specified`() {
      assertThrows<IllegalArgumentException> {
        accession(processingMethod = null, total = grams(1))
      }
    }

    @Test
    fun `cannot withdraw more seeds than exist in the accession`() {
      assertThrows<IllegalArgumentException> {
        accession(
            processingMethod = ProcessingMethod.Count,
            total = seeds(1),
            viabilityTests = listOf(viabilityTest(seedsTested = 1)),
            withdrawals = listOf(withdrawal(withdrawn = seeds(1), date = tomorrow)))
      }
    }

    @Test
    fun `cannot add withdrawal with more seeds than exist in the accession`() {
      val accession =
          accession().copy(isManualState = true, remaining = seeds(10)).withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> {
        accession.addWithdrawal(WithdrawalModel(date = today, withdrawn = seeds(11)), tomorrowClock)
      }
    }

    @Test
    fun `cannot specify negative seeds remaining for weight-based accessions`() {
      assertThrows<IllegalArgumentException>("Viability tests") {
        accession(
            processingMethod = ProcessingMethod.Weight,
            total = grams(1),
            viabilityTests = listOf(viabilityTest(seedsTested = 1, remaining = grams(-1))))
      }

      assertThrows<IllegalArgumentException>("withdrawals") {
        accession(
            processingMethod = ProcessingMethod.Weight,
            total = grams(1),
            withdrawals = listOf(withdrawal(withdrawn = grams(1), remaining = grams(-1))))
      }
    }

    @Test
    fun `total quantity must be greater than zero`() {
      assertThrows<IllegalArgumentException>("zero seeds") {
        accession(processingMethod = ProcessingMethod.Count, total = seeds(0))
      }
      assertThrows<IllegalArgumentException>("zero weight") {
        accession(processingMethod = ProcessingMethod.Weight, total = grams(0))
      }
      assertThrows<IllegalArgumentException>("negative seeds") {
        accession(processingMethod = ProcessingMethod.Count, total = seeds(-1))
      }
      assertThrows<IllegalArgumentException>("negative weight") {
        accession(processingMethod = ProcessingMethod.Weight, total = grams(-1))
      }
    }

    @Test
    fun `cannot withdraw seeds if remaining quantity is not set`() {
      val initial = accession().copy(isManualState = true)

      assertThrows<IllegalArgumentException> {
        initial.copy(withdrawals = listOf(withdrawal(withdrawn = seeds(1))))
      }
    }

    @Test
    fun `cannot withdraw more seeds than most recent observed quantity`() {
      val initial =
          accession().copy(isManualState = true, remaining = seeds(10)).withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> {
        initial
            .copy(withdrawals = listOf(withdrawal(withdrawn = seeds(11))))
            .withCalculatedValues(clock, initial)
      }
    }

    @Test
    fun `cannot withdraw more weight than most recent observed seed count`() {
      val initial =
          accession()
              .copy(
                  isManualState = true,
                  subsetCount = 2,
                  subsetWeightQuantity = grams(2),
                  remaining = seeds(10))
              .withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> {
        initial
            .copy(withdrawals = listOf(withdrawal(withdrawn = grams(11))))
            .withCalculatedValues(clock, initial)
      }
    }

    @Test
    fun `cannot remove remaining quantity once it has been set`() {
      val initial =
          accession().copy(isManualState = true, remaining = seeds(1)).withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> { initial.copy(remaining = null) }
    }

    @Test
    fun `cannot change remaining quantity from seeds to weight without subset info if withdrawals exist`() {
      val initial =
          accession()
              .copy(isManualState = true, remaining = seeds(10))
              .withCalculatedValues(clock)
              .copy(withdrawals = listOf(withdrawal(seeds(1))))
              .withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> { initial.copy(remaining = grams(1)) }
    }

    @Test
    fun `cannot change remaining quantity from weight to seeds without subset info if withdrawals exist`() {
      val initial =
          accession()
              .copy(isManualState = true, remaining = grams(10))
              .withCalculatedValues(clock)
              .copy(withdrawals = listOf(withdrawal(grams(1))))
              .withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> { initial.copy(remaining = seeds(1)) }
    }

    @Test
    fun `can change remaining quantity from seeds to weight without subset info if no withdrawals exist`() {
      val initial =
          accession().copy(isManualState = true, remaining = seeds(10)).withCalculatedValues(clock)

      assertDoesNotThrow { initial.copy(remaining = grams(1)) }
    }

    @Test
    fun `can change remaining quantity from weight to seeds without subset info if no withdrawals exist`() {
      val initial =
          accession().copy(isManualState = true, remaining = grams(10)).withCalculatedValues(clock)

      assertDoesNotThrow { initial.copy(remaining = seeds(1)) }
    }

    @Test
    fun `can change remaining quantity from seeds to weight if subset info exists`() {
      val initial =
          accession()
              .copy(
                  isManualState = true,
                  subsetCount = 1,
                  subsetWeightQuantity = grams(1),
                  remaining = seeds(10))
              .withCalculatedValues(clock)
              .copy(withdrawals = listOf(withdrawal(seeds(1))))
              .withCalculatedValues(clock)

      assertDoesNotThrow { initial.copy(remaining = grams(10)) }
    }

    @Test
    fun `can change remaining quantity from weight to seeds if subset info exists`() {
      val initial =
          accession()
              .copy(
                  isManualState = true,
                  subsetCount = 1,
                  subsetWeightQuantity = grams(1),
                  remaining = grams(10))
              .withCalculatedValues(clock)
              .copy(withdrawals = listOf(withdrawal(grams(1))))
              .withCalculatedValues(clock)

      assertDoesNotThrow { initial.copy(remaining = seeds(10)) }
    }

    @Test
    fun `cannot create viability test on weight-based accession without subset data`() {
      val initial =
          accession().copy(isManualState = true, remaining = grams(10)).withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> {
        initial.addViabilityTest(viabilityTest(seedsTested = 1, startDate = null), tomorrowClock)
      }
    }

    @Test
    fun `cannot add viability test with more seeds sown than remaining quantity`() {
      val initial =
          accession().copy(isManualState = true, remaining = seeds(10)).withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> {
        initial.addViabilityTest(viabilityTest(seedsTested = 11, startDate = null), tomorrowClock)
      }
    }

    @Test
    fun `can add viability test with more seeds sown than remaining quantity if it is older than the latest observed quantity`() {
      val initial =
          accession().copy(isManualState = true, remaining = seeds(10)).withCalculatedValues(clock)

      assertDoesNotThrow {
        initial.addViabilityTest(
            viabilityTest(seedsTested = 11, startDate = yesterday), tomorrowClock)
      }
    }

    @Test
    fun `can add viability test with fewer seeds sown than estimated seed count by weight`() {
      val initial =
          accession()
              .copy(
                  isManualState = true,
                  subsetCount = 2,
                  subsetWeightQuantity = grams(2),
                  remaining = grams(10))
              .withCalculatedValues(clock)

      assertDoesNotThrow { initial.addViabilityTest(viabilityTest(seedsTested = 9), tomorrowClock) }
    }

    @Test
    fun `cannot add new viability test with more seeds sown than estimated seed count by weight`() {
      val initial =
          accession()
              .copy(
                  isManualState = true,
                  subsetCount = 2,
                  subsetWeightQuantity = grams(2),
                  remaining = grams(10))
              .withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> {
        initial.addViabilityTest(viabilityTest(seedsTested = 11, startDate = null), tomorrowClock)
      }
    }

    @Test
    fun `cannot add new viability test with substrate that is not valid for test type`() {
      val initial =
          accession().copy(isManualState = true, remaining = seeds(10)).withCalculatedValues(clock)

      assertThrows<IllegalArgumentException> {
        initial.addViabilityTest(
            viabilityTest(
                seedsTested = 1,
                substrate = ViabilityTestSubstrate.Paper,
                testType = ViabilityTestType.Nursery),
            clock)
      }
    }
  }

  @Nested
  inner class WeightBasedAccessionCalculations {
    @Test
    fun `observed quantity is null if no total weight`() {
      val accession = accession(processingMethod = ProcessingMethod.Weight)
      assertAll(
          { assertNull(accession.calculateLatestObservedQuantity(clock), "Quantity") },
          { assertNull(accession.calculateLatestObservedTime(clock), "Time") })
    }

    @Test
    fun `observed quantity is total quantity if no withdrawals`() {
      val accession = accession(total = grams(50))
      assertAll(
          { assertEquals(grams(50), accession.calculateLatestObservedQuantity(clock), "Quantity") },
          { assertEquals(clock.instant(), accession.calculateLatestObservedTime(clock), "Time") })
    }

    @Test
    fun `observed quantity is based on withdrawal remaining quantities`() {
      val accession =
          accession(
              total = grams(100),
              withdrawals =
                  listOf(
                      withdrawal(remaining = grams(95), createdTime = Instant.ofEpochSecond(1)),
                      withdrawal(remaining = grams(89), createdTime = Instant.ofEpochSecond(2)),
                      withdrawal(remaining = grams(91), createdTime = Instant.ofEpochSecond(3)),
                  ))

      assertAll(
          { assertEquals(grams(89), accession.calculateLatestObservedQuantity(clock), "Quantity") },
          {
            assertEquals(
                Instant.ofEpochSecond(3), accession.calculateLatestObservedTime(clock), "Time")
          })
    }

    @Test
    fun `estimated seed count is null if information is missing`() {
      listOf(1, null).forEach { subsetCount ->
        listOf(BigDecimal("1.1"), null).forEach { subsetWeight ->
          listOf(BigDecimal("5.5"), null).forEach { totalWeight ->
            val values = listOf(subsetCount, subsetWeight, totalWeight)
            if (values.any { it == null }) {
              val accession =
                  accession(
                      subsetCount = subsetCount,
                      subsetWeight = SeedQuantityModel.of(subsetWeight, SeedQuantityUnits.Grams),
                      total = SeedQuantityModel.of(totalWeight, SeedQuantityUnits.Grams))
              assertNull(
                  accession.calculateEstimatedSeedCount(accession.total),
                  "Estimated seed count: $values")
            }
          }
        }
      }
    }

    @Test
    fun `estimated seed count is calculated based on weight`() {
      val accession = accession(subsetCount = 10, subsetWeight = milligrams(200), total = grams(1))

      assertEquals(50, accession.calculateEstimatedSeedCount(accession.total))
    }

    @Test
    fun `remaining defaults to total weight if no withdrawals are present`() {
      val accession = accession(subsetCount = 10, subsetWeight = milligrams(100), total = grams(1))

      assertEquals(grams(1), accession.calculateRemaining(clock))
    }

    @Test
    fun `calculateRemaining uses value from withdrawals if it is the lowest one`() {
      val accession =
          accession(
              total = grams(100),
              viabilityTests =
                  listOf(
                      viabilityTest(remaining = grams(99)),
                      viabilityTest(remaining = grams(90)),
                      viabilityTest(remaining = grams(96)),
                  ),
              withdrawals =
                  listOf(
                      withdrawal(remaining = grams(95)),
                      withdrawal(remaining = grams(89)),
                      withdrawal(remaining = grams(91)),
                  ))

      assertEquals(grams(89), accession.calculateRemaining(clock))
    }

    @Test
    fun `calculateRemaining uses value from viability test if it is the lowest one`() {
      val accession =
          accession(
              total = grams(100),
              viabilityTests =
                  listOf(
                      viabilityTest(remaining = grams(99)),
                      viabilityTest(remaining = grams(90)),
                      viabilityTest(remaining = grams(96)),
                  ),
              withdrawals =
                  listOf(
                      withdrawal(remaining = grams(95)),
                      withdrawal(remaining = grams(92)),
                      withdrawal(remaining = grams(91)),
                  ))

      assertEquals(grams(90), accession.calculateRemaining(clock))
    }

    @Test
    fun `calculateRemaining returns value in same units as accession total weight`() {
      val accession =
          accession(
              total = milligrams(2000), withdrawals = listOf(withdrawal(remaining = grams(1))))

      assertEquals(milligrams(1000), accession.calculateRemaining(clock))
    }

    @Test
    fun `calculateWithdrawals updates remaining amounts based on tests`() {
      val viabilityTest = viabilityTest(remaining = grams(50))
      val accession =
          accession(
              total = grams(100),
              viabilityTests = listOf(viabilityTest),
              withdrawals =
                  listOf(withdrawal(viabilityTestId = viabilityTest.id, remaining = grams(70))))

      val withdrawals = accession.calculateWithdrawals(clock)

      assertEquals(grams<SeedQuantityModel>(50), withdrawals[0].remaining)
    }

    @Test
    fun `calculateWithdrawals rejects IDs not in caller-supplied withdrawal list`() {
      val accession = accession(total = grams(100), withdrawals = listOf(withdrawal(grams(1))))

      assertThrows<IllegalArgumentException> {
        accession.calculateWithdrawals(
            clock, AccessionModel(withdrawals = listOf(withdrawal(grams(1)))))
      }
    }

    @Test
    fun `calculateWithdrawals looks in caller-supplied withdrawal list for test withdrawals`() {
      val viabilityTest = viabilityTest(remaining = grams(95))
      val existingWithdrawalForTest = withdrawal(grams(5), viabilityTestId = viabilityTest.id)
      val otherExistingWithdrawal = withdrawal(grams(1))

      val accession =
          accession(
              total = grams(100),
              viabilityTests = listOf(viabilityTest),
              withdrawals =
                  listOf(
                      otherExistingWithdrawal.copy(
                          viabilityTestId = viabilityTest.id,
                          purpose = WithdrawalPurpose.ViabilityTesting)))

      val withdrawals =
          accession.calculateWithdrawals(
              clock,
              accession(
                  total = grams(100),
                  withdrawals = listOf(existingWithdrawalForTest, otherExistingWithdrawal)))
      assertEquals(existingWithdrawalForTest.id, withdrawals[0].id, "Withdrawal ID")
    }

    @Test
    fun `calculateWithdrawals calculates weightDifference based on weight remaining`() {
      val accession =
          accession(
              total = grams(100),
              withdrawals =
                  listOf(
                      withdrawal(remaining = grams(91)),
                      withdrawal(remaining = grams(91)),
                      withdrawal(remaining = grams(0))))

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(
          listOf<SeedQuantityModel>(grams(9), grams(0), grams(91)),
          withdrawals.map { it.weightDifference })
    }

    @Test
    fun `calculateWithdrawals does not generate negative withdrawn amounts if time order is wrong`() {
      val accession =
          accession(
              total = grams(100),
              withdrawals =
                  listOf(
                      withdrawal(remaining = grams(92), date = january(1)),
                      withdrawal(remaining = grams(93), date = january(2)),
                      withdrawal(remaining = grams(95), date = january(3)),
                  ))

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(listOf(january(3), january(2), january(1)), withdrawals.map { it.date })
      assertEquals(
          listOf<SeedQuantityModel>(grams(5), grams(2), grams(1)),
          withdrawals.map { it.weightDifference },
          "Weight differences")
    }

    @Nested
    inner class TimeBasedWeightCalculations {
      private val futureTest =
          viabilityTest(seedsTested = 8, startDate = tomorrow, remaining = grams(85))
      private val futureTestWithdrawal =
          withdrawal(
              grams(8),
              date = tomorrow,
              viabilityTestId = futureTest.id,
              purpose = WithdrawalPurpose.ViabilityTesting,
              remaining = grams(85),
          )

      @Test
      fun `calculateTotalPastWithdrawalQuantity returns null if no withdrawals`() {
        val accession = accession(total = grams(100))

        assertNull(accession.calculateTotalPastWithdrawalQuantity(clock))
      }

      @Test
      fun `calculateTotalPastWithdrawalQuantity does not count scheduled withdrawals`() {
        val accession =
            accession(
                total = grams(100),
                viabilityTests = listOf(futureTest),
                withdrawals =
                    listOf(
                        withdrawal(grams(1), date = yesterday, remaining = grams(99)),
                        withdrawal(milligrams(1), date = today, remaining = milligrams(97000)),
                        withdrawal(seeds(4), date = tomorrow, remaining = grams(93)),
                        futureTestWithdrawal))

        assertEquals(
            grams<SeedQuantityModel>(3), accession.calculateTotalPastWithdrawalQuantity(clock))
      }

      @Test
      fun `calculateTotalPastWithdrawalQuantity includes withdrawals and viability tests`() {
        val accession =
            accession(
                total = grams(100),
                viabilityTests =
                    listOf(
                        viabilityTest(seedsTested = 1, startDate = today, remaining = grams(96))),
                withdrawals =
                    listOf(
                        withdrawal(grams(1), date = yesterday, remaining = grams(99)),
                        withdrawal(milligrams(1), date = today, remaining = milligrams(97000))))

        assertEquals(
            grams<SeedQuantityModel>(4), accession.calculateTotalPastWithdrawalQuantity(clock))
      }

      @Test
      fun `calculateTotalScheduledWithdrawalQuantity includes manual and test withdrawals`() {
        val accession =
            accession(
                total = grams(100),
                viabilityTests = listOf(futureTest),
                withdrawals =
                    listOf(
                        withdrawal(grams(1), date = yesterday, remaining = grams(99)),
                        withdrawal(milligrams(1), date = today, remaining = milligrams(97000)),
                        withdrawal(seeds(4), date = tomorrow, remaining = grams(93)),
                        futureTestWithdrawal))

        assertEquals(
            seeds<SeedQuantityModel>(12),
            accession.calculateTotalScheduledWithdrawalQuantity(clock))
      }

      @Test
      fun `calculateTotalScheduledWithdrawalQuantity does not count past withdrawals`() {
        val accession =
            accession(
                total = grams(100),
                withdrawals =
                    listOf(
                        withdrawal(grams(1), date = yesterday, remaining = grams(99)),
                        withdrawal(milligrams(1), date = today, remaining = milligrams(97000)),
                        withdrawal(grams(1), date = tomorrow, remaining = grams(93))))

        assertEquals(
            grams<SeedQuantityModel>(4), accession.calculateTotalScheduledWithdrawalQuantity(clock))
      }

      @Test
      fun `calculateTotalScheduledNonTestQuantity returns count-based result for count-based withdrawals`() {
        val accession =
            accession(
                total = grams(100),
                viabilityTests = listOf(futureTest),
                withdrawals =
                    listOf(
                        withdrawal(grams(1), date = yesterday, remaining = grams(99)),
                        withdrawal(milligrams(1), date = today, remaining = milligrams(97000)),
                        withdrawal(seeds(4), date = tomorrow, remaining = grams(93)),
                        futureTestWithdrawal))

        assertEquals(
            seeds<SeedQuantityModel>(4), accession.calculateTotalScheduledNonTestQuantity(clock))
      }

      @Test
      fun `calculateTotalScheduledTestQuantity returns seed count of scheduled test withdrawals`() {
        val accession =
            accession(
                total = grams(100),
                viabilityTests = listOf(futureTest),
                withdrawals =
                    listOf(
                        withdrawal(grams(1), date = yesterday, remaining = grams(99)),
                        withdrawal(milligrams(1), date = today, remaining = milligrams(97000)),
                        withdrawal(seeds(4), date = tomorrow, remaining = grams(93)),
                        futureTestWithdrawal))

        assertEquals(
            seeds<SeedQuantityModel>(8), accession.calculateTotalScheduledTestQuantity(clock))
      }

      @Test
      fun `calculateTotalScheduledWithdrawalQuantity returns weight if mix of count and weight withdrawals`() {
        val accession =
            accession(
                total = grams(100),
                withdrawals =
                    listOf(
                        withdrawal(grams(1), date = yesterday, remaining = grams(99)),
                        withdrawal(seeds(4), date = tomorrow, remaining = grams(93)),
                        withdrawal(milligrams(1), date = tomorrow, remaining = grams(85))))

        assertEquals(
            grams<SeedQuantityModel>(14),
            accession.calculateTotalScheduledWithdrawalQuantity(clock))
      }
    }
  }

  @Nested
  inner class CountBasedAccessionCalculations {
    @Test
    fun `observed quantity is null if accession has no processing method`() {
      val accession = accession()
      assertAll(
          { assertNull(accession.calculateLatestObservedQuantity(clock), "Quantity") },
          { assertNull(accession.calculateLatestObservedTime(clock), "Time") },
      )
    }

    @Test
    fun `observed quantity is null if accession has no initial quantity`() {
      val accession = accession(processingMethod = ProcessingMethod.Count)
      assertAll(
          { assertNull(accession.calculateLatestObservedQuantity(clock), "Quantity") },
          { assertNull(accession.calculateLatestObservedTime(clock), "Time") },
      )
    }

    @Test
    fun `observed quantity is initial quantity if present`() {
      val accession = accession(total = seeds(10))
      assertAll(
          { assertEquals(seeds(10), accession.calculateLatestObservedQuantity(clock), "Quantity") },
          { assertEquals(clock.instant(), accession.calculateLatestObservedTime(clock), "Time") },
      )
    }

    @Test
    fun `remaining defaults to total count if no withdrawals are present`() {
      val accession = accession(total = seeds(15))

      assertEquals(seeds(15), accession.calculateRemaining(clock))
    }

    @Test
    fun `cut test seed count is not subtracted from seeds remaining`() {
      val accession =
          accession(
              cutTestSeedsCompromised = 2,
              cutTestSeedsEmpty = 1,
              cutTestSeedsFilled = 1,
              total = seeds(10),
          )

      assertEquals(seeds(10), accession.calculateRemaining(clock))
    }

    @Test
    fun `viability test seeds sown are subtracted from seeds remaining`() {
      val accession =
          accession(
              viabilityTests =
                  listOf(
                      viabilityTest(seedsTested = 10),
                      viabilityTest(seedsTested = 5),
                  ),
              total = seeds(40),
          )

      assertEquals(seeds(25), accession.calculateRemaining(clock))
    }

    @Test
    fun `withdrawn seeds are subtracted from seeds remaining`() {
      val accession =
          accession(
              total = seeds(100),
              withdrawals =
                  listOf(
                      withdrawal(seeds(1), remaining = seeds(0)),
                      withdrawal(seeds(5), remaining = seeds(0)),
                  ))

      assertEquals(seeds(94), accession.calculateRemaining(clock))
    }

    @Test
    fun `withdrawals for viability testing without corresponding tests are not subtracted from seeds remaining`() {
      val accession =
          accession(
              total = seeds(100),
              withdrawals =
                  listOf(
                      withdrawal(
                          seeds(1),
                          remaining = seeds(0),
                          purpose = WithdrawalPurpose.ViabilityTesting),
                      withdrawal(seeds(5), remaining = seeds(0)),
                  ))

      assertEquals(seeds(95), accession.calculateRemaining(clock))
    }

    @Test
    fun `calculateWithdrawals updates seeds remaining on tests`() {
      val testWithExistingWithdrawal = viabilityTest(seedsTested = 4, startDate = january(2))
      val accession =
          accession(
              total = seeds(100),
              viabilityTests =
                  listOf(
                      viabilityTest(seedsTested = 8, startDate = january(4)),
                      testWithExistingWithdrawal,
                  ),
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          accessionId = AccessionId(1),
                          date = january(15), // different from test date
                          id = nextWithdrawalId(),
                          purpose = WithdrawalPurpose.ViabilityTesting,
                          remaining = seeds(0),
                          viabilityTestId = testWithExistingWithdrawal.id,
                          withdrawn = seeds(testWithExistingWithdrawal.seedsTested!!),
                      ),
                      withdrawal(seeds(2), remaining = seeds(0), date = january(3)),
                  ),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(
          listOf<SeedQuantityModel>(seeds(96), seeds(86)),
          withdrawals.mapNotNull { it.viabilityTest?.remaining })
    }

    @Test
    fun `calculateWithdrawals walks tests and withdrawals in time order`() {
      val accession =
          accession(
              total = seeds(100),
              viabilityTests =
                  listOf(
                      viabilityTest(seedsTested = 4, startDate = january(2)),
                      viabilityTest(seedsTested = 8, startDate = january(4)),
                  ),
              withdrawals =
                  listOf(
                      withdrawal(seeds(2), remaining = seeds(0), date = january(3)),
                      withdrawal(seeds(1), remaining = seeds(0), date = january(1)),
                  ),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(
          listOf<SeedQuantityModel>(seeds(99), seeds(95), seeds(93), seeds(85)),
          withdrawals.map { it.remaining })
    }

    @Test
    fun `calculateWithdrawals retains withdrawal date if test is undated`() {
      val test = viabilityTest(seedsTested = 1, startDate = null)
      val accession =
          accession(
              total = seeds(100),
              viabilityTests = listOf(test),
              withdrawals =
                  listOf(withdrawal(seeds(1), date = january(15), viabilityTestId = test.id)),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(january(15), withdrawals[0].date)
    }

    @Test
    fun `calculateWithdrawals updates withdrawal date based on updated test date`() {
      val test = viabilityTest(seedsTested = 1, startDate = january(5))
      val accession =
          accession(
              total = seeds(100),
              viabilityTests = listOf(test),
              withdrawals =
                  listOf(withdrawal(seeds(1), date = january(15), viabilityTestId = test.id)),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(
          january(5), withdrawals[0].date, "Withdrawal date should be copied from test date")
      assertEquals(
          accession.withdrawals[0].id, withdrawals[0].id, "Should update existing test withdrawal")
    }

    @Test
    fun `calculateWithdrawals generates withdrawals for tests without seedsTested values`() {
      val accession =
          accession(total = seeds(100), viabilityTests = listOf(viabilityTest(seedsTested = null)))

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(1, withdrawals.size, "Number of generated withdrawals")
      assertEquals(
          accession.viabilityTests[0].id,
          withdrawals[0].viabilityTestId,
          "Withdrawal should refer to viability test")
      assertNull(withdrawals[0].withdrawn, "Withdrawal amount")
    }

    @Nested
    inner class TimeBasedCalculations {
      private val viabilityTest = viabilityTest(seedsTested = 8, startDate = tomorrow)

      private val accession =
          accession(
              total = seeds(100),
              viabilityTests = listOf(viabilityTest),
              withdrawals =
                  listOf(
                      withdrawal(seeds(1), date = yesterday),
                      withdrawal(seeds(2), date = today),
                      withdrawal(seeds(4), date = tomorrow),
                      withdrawal(seeds(8), date = tomorrow, viabilityTestId = viabilityTest.id)))

      @Test
      fun `calculateTotalPastWithdrawalQuantity does not count scheduled withdrawals`() {
        assertEquals(
            seeds<SeedQuantityModel>(3), accession.calculateTotalPastWithdrawalQuantity(clock))
      }

      @Test
      fun `calculateTotalScheduledWithdrawalQuantity does not count past withdrawals`() {
        assertEquals(
            seeds<SeedQuantityModel>(12),
            accession.calculateTotalScheduledWithdrawalQuantity(clock))
      }

      @Test
      fun `calculateTotalScheduledNonTestQuantity does not count scheduled tests`() {
        assertEquals(
            seeds<SeedQuantityModel>(4), accession.calculateTotalScheduledNonTestQuantity(clock))
      }

      @Test
      fun `calculateTotalScheduledTestQuantity does not count scheduled non-test withdrawals`() {
        assertEquals(
            seeds<SeedQuantityModel>(8), accession.calculateTotalScheduledTestQuantity(clock))
      }
    }
  }

  @Nested
  inner class StateRelatedCalculations {

    @Test
    fun `calculateProcessingStartDate null if no seed count`() {
      assertNull(accession().calculateProcessingStartDate(clock))
    }

    @Test
    fun `calculateProcessingStartDate preserves existing processingStartDate`() {
      val accession = accession(processingStartDate = tomorrow, total = seeds(150))
      assertEquals(tomorrow, accession.calculateProcessingStartDate(clock))
    }

    @Test
    fun `calculateProcessingStartDate defaults to today if seeds counted`() {
      val accession = accession(total = seeds(150))
      assertEquals(today, accession.calculateProcessingStartDate(clock))
    }

    @TestFactory
    fun stateTransitionRules(): List<DynamicTest> {
      val tests = mutableListOf<DynamicTest>()

      fun AccessionModel.addStateTest(
          modify: AccessionModel.() -> AccessionModel,
          expectedState: AccessionState?,
          displayName: String,
      ): AccessionModel {
        val newModel = modify()
        tests.add(
            DynamicTest.dynamicTest("$state -> $expectedState: $displayName") {
              val transition = getStateTransition(newModel, clock)
              if (expectedState == null || expectedState == state) {
                assertNull(transition, "Unexpected state transition $transition")
              } else {
                assertEquals(
                    expectedState, transition?.newState, "Unexpected state transition $transition")
              }
            })

        return newModel
      }

      // Start off with an accession that meets all the conditions, then peel them back in
      // descending order of precedence to make sure the highest-precedence rule gets applied.
      accession()
          .copy(
              nurseryStartDate = today,
              withdrawals = listOf(withdrawal(seeds(10), remaining = seeds(0))),
              storageLocation = "location",
              storagePackets = 10,
              storageStartDate = today,
              dryingEndDate = today,
              dryingStartDate = today,
              processingStartDate = today.minusDays(14),
              processingMethod = ProcessingMethod.Count,
              total = seeds(10),
              checkedInTime = Instant.EPOCH,
          )
          .addStateTest({ this }, AccessionState.Nursery, "Nursery start date entered")
          .addStateTest(
              { copy(nurseryStartDate = null) },
              AccessionState.Withdrawn,
              "All seeds marked as withdrawn")
          .addStateTest(
              { copy(withdrawals = emptyList()) },
              AccessionState.InStorage,
              "Number of packets or location has been entered")
          .addStateTest(
              { copy(storageLocation = null) },
              AccessionState.InStorage,
              "Number of packets or location has been entered")
          .addStateTest(
              { copy(storagePackets = null) },
              AccessionState.InStorage,
              "Storage start date is today or earlier")
          .addStateTest(
              { copy(storageStartDate = tomorrow) },
              AccessionState.Dried,
              "Drying end date is today or earlier")
          .addStateTest(
              { copy(dryingEndDate = tomorrow) },
              AccessionState.Drying,
              "Drying start date is today or earlier")
          .addStateTest(
              { copy(dryingStartDate = tomorrow) },
              AccessionState.Processed,
              "2 weeks have passed since processing start date")
          .addStateTest(
              { copy(processingStartDate = null) },
              AccessionState.Processing,
              "Seed count/weight entered")
          .addStateTest(
              { copy(total = null, processingMethod = null) },
              AccessionState.Pending,
              "Checked-in time entered")
          .addStateTest(
              { copy(checkedInTime = null) },
              AccessionState.AwaitingCheckIn,
              "No state conditions applied")

      // Make sure that manual state updates are applied except in specific cases, and that
      // the correct automatic state updates happen even when the accession allows state editing.
      accession()
          .copy(
              isManualState = true,
              state = AccessionState.AwaitingProcessing,
          )
          .addStateTest(
              { copy(state = AccessionState.UsedUp) },
              AccessionState.AwaitingProcessing,
              "Can't change to Used Up when no quantity has been set")

      accession()
          .copy(
              isManualState = true,
              latestObservedQuantity = seeds(10),
              latestObservedTime = Instant.EPOCH,
              remaining = seeds(10),
              state = AccessionState.InStorage,
              total = seeds(10),
              withdrawals = listOf(withdrawal(seeds(10))),
          )
          .withCalculatedValues(clock)
          .addStateTest({ this }, AccessionState.UsedUp, "All seeds marked as withdrawn")
          .addStateTest(
              { copy(state = AccessionState.InStorage) },
              AccessionState.UsedUp,
              "Can't change from Used Up when no seeds remaining")
          .copy(state = AccessionState.UsedUp)
          .addStateTest(
              { copy(withdrawals = emptyList()) },
              AccessionState.InStorage,
              "Accession is no longer used up")
          .addStateTest(
              { copy(state = AccessionState.Drying) },
              AccessionState.Drying,
              "Change from InStorage to Drying")
          .addStateTest(
              { copy(state = AccessionState.Processing) },
              AccessionState.Processing,
              "Change from Drying to Processing")
          .addStateTest(
              { copy(state = AccessionState.AwaitingProcessing) },
              AccessionState.AwaitingProcessing,
              "Change from Processing to Awaiting Processing")
          .addStateTest(
              { copy(state = AccessionState.AwaitingCheckIn) },
              AccessionState.AwaitingProcessing,
              "Can't change back to Awaiting Check-In")
          .addStateTest(
              { copy(state = AccessionState.InStorage) },
              AccessionState.InStorage,
              "Change from Awaiting Check-In to In Storage")

      return tests
    }
  }

  @Nested
  inner class QuantityCalculations {
    @Test
    fun `observed quantity is updated if remaining quantity is changed`() {
      val existing = accession().copy(isManualState = true, remaining = seeds(10))

      val updated = existing.copy(remaining = seeds(9))

      assertEquals(
          seeds(9),
          updated.calculateLatestObservedQuantity(tomorrowClock, existing),
          "Observed quantity")
      assertEquals(
          tomorrowInstant,
          updated.calculateLatestObservedTime(tomorrowClock, existing),
          "Observed time")
    }

    @Test
    fun `observed quantity is not updated if remaining quantity is unchanged`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = seeds(10),
                  latestObservedTime = yesterdayInstant,
                  remaining = seeds(9))

      val updated = accession.copy(remaining = seeds(9))

      assertEquals(
          seeds(10),
          updated.calculateLatestObservedQuantity(tomorrowClock, accession),
          "Observed quantity")
      assertEquals(
          yesterdayInstant,
          updated.calculateLatestObservedTime(tomorrowClock, accession),
          "Observed time")
    }

    @Test
    fun `observed quantity is not affected by new withdrawals`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = seeds(10),
                  latestObservedTime = yesterdayInstant,
                  remaining = seeds(10))

      val updated = accession.copy(withdrawals = listOf(withdrawal(seeds(2), date = today)))

      assertEquals(
          seeds(10),
          updated.calculateLatestObservedQuantity(tomorrowClock, accession),
          "Observed quantity")
      assertEquals(
          yesterdayInstant,
          updated.calculateLatestObservedTime(tomorrowClock, accession),
          "Observed time")
    }

    // SW-1723
    @Test
    fun `observed quantity is not affected by repeated calls to withCalculatedValues with same base model`() {
      val accession =
          accession()
              .copy(isManualState = true, remaining = seeds(10))
              .withCalculatedValues(yesterdayClock)
      assertEquals(seeds(10), accession.latestObservedQuantity, "Original observed quantity")

      val withWithdrawal =
          accession.addWithdrawal(withdrawal(seeds(2), date = today, id = null), clock)
      assertEquals(
          seeds(10), accession.latestObservedQuantity, "Observed quantity after adding withdrawal")

      val recalculated = withWithdrawal.withCalculatedValues(tomorrowClock, accession)
      assertEquals(
          seeds(10), recalculated.latestObservedQuantity, "Observed quantity after recalculating")
      assertEquals(
          yesterdayInstant, recalculated.latestObservedTime, "Observed time after recalculating")
    }

    @Test
    fun `remaining quantity is updated by withdrawal dated after observed quantity`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = seeds(10),
                  latestObservedTime = yesterdayInstant,
                  remaining = seeds(10))

      val updated =
          accession.copy(withdrawals = listOf(withdrawal(seeds(2), date = today, id = null)))

      assertEquals(
          seeds(8), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity is not affected by withdrawals dated before latest observed quantity`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = grams(10),
                  latestObservedTime = todayInstant,
                  remaining = grams(10))

      val updated =
          accession.copy(
              withdrawals =
                  listOf(
                      withdrawal(
                          grams(1), date = yesterday, createdTime = tomorrowInstant, id = null)))

      assertEquals(
          grams(10), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity is not affected by withdrawals created earlier on the day of the observed quantity`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = grams(10),
                  latestObservedTime = todayInstant,
                  remaining = grams(10))

      val updated =
          accession.copy(
              withdrawals =
                  listOf(
                      withdrawal(
                          grams(1), date = today, createdTime = yesterdayInstant, id = null)))

      assertEquals(
          grams(10), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity is updated by withdrawals created later on the day of the observed quantity`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = grams(10),
                  latestObservedTime = todayInstant,
                  remaining = grams(10))
              .withCalculatedValues(clock)

      val updated =
          accession.copy(
              withdrawals =
                  listOf(
                      withdrawal(
                          grams(1),
                          date = today,
                          createdTime = todayInstant.plusSeconds(1),
                          id = null)))

      assertEquals(
          grams(9), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity in weight is updated by withdrawal in seeds`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = grams(10),
                  latestObservedTime = Instant.EPOCH,
                  remaining = grams(10),
                  // 3 grams per seed
                  subsetCount = 2,
                  subsetWeightQuantity = grams(6))

      val updated = accession.copy(withdrawals = listOf(withdrawal(seeds(2), id = null)))

      assertEquals(
          grams(4), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    @Test
    fun `remaining quantity is updated by viability test but observed quantity is not`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = seeds(10),
                  latestObservedTime = Instant.EPOCH,
                  remaining = seeds(10))

      val updated = accession.copy(viabilityTests = listOf(viabilityTest(seedsTested = 2)))

      assertEquals(
          seeds(10),
          updated.calculateLatestObservedQuantity(tomorrowClock, accession),
          "Observed quantity")
      assertEquals(
          Instant.EPOCH,
          updated.calculateLatestObservedTime(tomorrowClock, accession),
          "Observed time")
      assertEquals(
          seeds(8), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    // V1 COMPATIBILITY
    @Test
    fun `remaining quantity in seeds is calculated for viability test and its withdrawal`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = seeds(10),
                  latestObservedTime = Instant.EPOCH,
                  remaining = seeds(10))

      val updated = accession.addViabilityTest(viabilityTest(seedsTested = 1), clock)

      assertEquals(
          seeds(9), updated.viabilityTests.getOrNull(0)?.remaining, "Seeds remaining on test")
      assertEquals(
          seeds(9), updated.withdrawals.getOrNull(0)?.remaining, "Seeds remaining on withdrawal")
    }

    @Test
    fun `remaining quantity change takes precedence over newly-added withdrawal`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  latestObservedQuantity = seeds(10),
                  latestObservedTime = Instant.EPOCH,
                  remaining = seeds(10))

      val updated =
          accession.copy(
              remaining = seeds(5), withdrawals = listOf(withdrawal(seeds(1), id = null)))

      assertEquals(
          seeds(5),
          updated.calculateLatestObservedQuantity(tomorrowClock, accession),
          "Observed quantity")
      assertEquals(
          tomorrowInstant,
          updated.calculateLatestObservedTime(tomorrowClock, accession),
          "Observed time")
      assertEquals(
          seeds(5), updated.calculateRemaining(tomorrowClock, accession), "Remaining quantity")
    }

    // V1 COMPATIBILITY
    @Test
    fun `total quantity is populated when remaining quantity is set for the first time`() {
      val accession =
          accession().copy(isManualState = true, remaining = seeds(10)).withCalculatedValues(clock)

      assertEquals(seeds(10), accession.total)
    }

    // V1 COMPATIBILITY
    @Test
    fun `total quantity is not updated when remaining quantity is updated`() {
      val accession =
          accession()
              .copy(isManualState = true, remaining = seeds(10))
              .withCalculatedValues(clock)
              .copy(remaining = seeds(9))
              .withCalculatedValues(clock)

      assertEquals(seeds(10), accession.total)
    }

    @Test
    fun `estimated seed count is the same as remaining quantity if it is count-based`() {
      val accession =
          accession()
              .copy(isManualState = true, remaining = seeds(10))
              .withCalculatedValues(clock)
              .addWithdrawal(withdrawal(seeds(1), date = tomorrow, id = null), tomorrowClock)

      assertEquals(9, accession.estimatedSeedCount)
    }

    @Test
    fun `estimated seed count is null if remaining quantity is weight-based and no subset data`() {
      val accession =
          accession().copy(isManualState = true, remaining = grams(10)).withCalculatedValues(clock)

      assertNull(accession.estimatedSeedCount)
    }

    @Test
    fun `estimated seed count is calculated based on subset data`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  remaining = grams(10),
                  subsetCount = 1,
                  subsetWeightQuantity = grams(2))
              .withCalculatedValues(clock)
              .addWithdrawal(withdrawal(grams(1), date = tomorrow, id = null), tomorrowClock)

      // 9 grams, 2 grams per seed = 4.5 seeds, rounded up to 5
      assertEquals(5, accession.estimatedSeedCount)
    }

    @Test
    fun `estimated weight is the same as remaining quantity if it is weight-based`() {
      val accession =
          accession()
              .copy(isManualState = true, remaining = grams(10))
              .withCalculatedValues(clock)
              .addWithdrawal(withdrawal(grams(1), date = tomorrow, id = null), tomorrowClock)

      assertEquals(grams(9), accession.estimatedWeight)
    }

    @Test
    fun `estimated weight is null if remaining quantity is count-based and no subset data`() {
      val accession =
          accession().copy(isManualState = true, remaining = seeds(10)).withCalculatedValues(clock)

      assertNull(accession.estimatedWeight)
    }

    @Test
    fun `estimated weight is calculated based on subset data`() {
      val accession =
          accession()
              .copy(
                  isManualState = true,
                  remaining = seeds(10),
                  subsetCount = 2,
                  subsetWeightQuantity = grams(1))
              .withCalculatedValues(clock)

      assertEquals(grams(5), accession.estimatedWeight)
    }
  }

  @Nested
  inner class V1ToV2Conversion {
    @Test
    fun `weight-based accession with no subset data and a viability test`() {
      val initial =
          AccessionModel(
              checkedInTime = yesterdayInstant,
              createdTime = yesterdayInstant,
              processingMethod = ProcessingMethod.Weight,
              total = grams(10),
              viabilityTests =
                  listOf(
                      ViabilityTestModel(
                          id = ViabilityTestId(1),
                          remaining = grams(9),
                          seedsTested = 1,
                          startDate = today,
                          testType = ViabilityTestType.Lab,
                      ),
                  ),
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          createdTime = todayInstant,
                          date = today,
                          id = WithdrawalId(1),
                          purpose = WithdrawalPurpose.ViabilityTesting,
                          remaining = grams(9),
                          viabilityTestId = ViabilityTestId(1),
                          withdrawn = seeds(1),
                      ),
                  ),
          )

      val expected =
          AccessionModel(
              checkedInTime = yesterdayInstant,
              createdTime = yesterdayInstant,
              estimatedWeight = grams(9),
              isManualState = true,
              latestObservedQuantity = grams(9),
              latestObservedTime = todayInstant,
              processingMethod = ProcessingMethod.Weight,
              remaining = grams(9),
              state = AccessionState.AwaitingProcessing,
              total = grams(10),
              viabilityTests =
                  listOf(
                      ViabilityTestModel(
                          id = ViabilityTestId(1),
                          remaining = grams(9),
                          seedsTested = 1,
                          startDate = today,
                          testType = ViabilityTestType.Lab,
                      ),
                  ),
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          createdTime = todayInstant,
                          date = today,
                          estimatedCount = 1,
                          estimatedWeight = grams(1),
                          id = WithdrawalId(1),
                          purpose = WithdrawalPurpose.ViabilityTesting,
                          remaining = grams(9),
                          viabilityTest =
                              ViabilityTestModel(
                                  id = ViabilityTestId(1),
                                  remaining = grams(9),
                                  seedsTested = 1,
                                  startDate = today,
                                  testType = ViabilityTestType.Lab,
                              ),
                          viabilityTestId = ViabilityTestId(1),
                          weightDifference = grams(1),
                          withdrawn = seeds(1),
                      ),
                  ),
          )

      assertJsonEquals(expected, initial.toV2Compatible(tomorrowClock))
    }
  }

  @Nested
  inner class V2ToV1Conversion {
    @Test
    fun `viability percent is overwritten with computed value`() {
      val expectedPercent = 40
      val v2Model =
          accession()
              .copy(isManualState = true, remaining = seeds(500))
              .withCalculatedValues(clock)
              .addViabilityTest(
                  viabilityTest(
                      seedsTested = 100,
                      testResults =
                          listOf(
                              viabilityTestResult(
                                  recordingDate = january(2), seedsGerminated = expectedPercent))),
                  clock)
      assertNull(v2Model.totalViabilityPercent)

      val v1Model = v2Model.toV1Compatible(clock)
      assertEquals(40, v1Model.totalViabilityPercent)
    }

    @Test
    fun `backdated viability tests with more seeds than initial quantity`() {
      val initialV2Model =
          accession()
              .copy(isManualState = true, remaining = seeds(2))
              .withCalculatedValues(yesterdayClock)
      val v2Model =
          initialV2Model
              .copy(latestObservedQuantityCalculated = false, remaining = seeds(1))
              .withCalculatedValues(clock, initialV2Model)
              // In v2, this is valid because it is dated yesterday and we have an observed quantity
              // from today that overrides what would otherwise be a negative seeds remaining value.
              .addViabilityTest(viabilityTest(seedsTested = 3, startDate = yesterday), clock)

      val v1Model = v2Model.toV1Compatible(clock)
      assertEquals(seeds(1), v1Model.viabilityTests.getOrNull(0)?.remaining)
    }

    @Test
    fun `backdated withdrawals with more seeds than initial quantity`() {
      val initialV2Model =
          accession()
              .copy(isManualState = true, remaining = seeds(2))
              .withCalculatedValues(yesterdayClock)
      val v2Model =
          initialV2Model
              .copy(latestObservedQuantityCalculated = false, remaining = seeds(1))
              .withCalculatedValues(clock, initialV2Model)
              // In v2, this is valid because it is yesterday and we have an observed quantity from
              // today that overrides what would otherwise be a negative seeds remaining value.
              .addWithdrawal(withdrawal(seeds(3), date = yesterday, id = null), clock)

      val v1Model = v2Model.toV1Compatible(clock)
      assertEquals(seeds(1), v1Model.withdrawals.getOrNull(0)?.remaining)
    }
  }
}
