package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.WithdrawalPurpose
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

internal class AccessionModelTest {
  private val clock: Clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
  private val today = LocalDate.now(clock)
  private val tomorrow = today.plusDays(1)

  private var germinationId = 1L
  private var germinationTestId = 1L
  private var withdrawalId = 1L
  private var defaultState = AccessionState.Processing

  private fun accession(
      germinationTests: List<GerminationTestModel>? = null,
      cutTestSeedsCompromised: Int? = null,
      cutTestSeedsEmpty: Int? = null,
      cutTestSeedsFilled: Int? = null,
      dryingEndDate: LocalDate? = null,
      dryingStartDate: LocalDate? = null,
      processingStartDate: LocalDate? = null,
      subsetCount: Int? = null,
      subsetWeightGrams: BigDecimal? = null,
      totalWeightGrams: BigDecimal? = null,
      seedsCounted: Int? = null,
      state: AccessionState = defaultState,
      storageLocation: String? = null,
      storagePackets: Int? = null,
      storageStartDate: LocalDate? = null,
      withdrawals: List<WithdrawalModel>? = null,
  ): AccessionModel {
    return AccessionModel(
        accessionNumber = "dummy",
        cutTestSeedsEmpty = cutTestSeedsEmpty,
        cutTestSeedsFilled = cutTestSeedsFilled,
        cutTestSeedsCompromised = cutTestSeedsCompromised,
        dryingEndDate = dryingEndDate,
        dryingStartDate = dryingStartDate,
        germinationTests = germinationTests,
        id = 1L,
        processingStartDate = processingStartDate,
        seedsCounted = seedsCounted,
        source = AccessionSource.Web,
        state = state,
        storageLocation = storageLocation,
        storagePackets = storagePackets,
        storageStartDate = storageStartDate,
        subsetCount = subsetCount,
        subsetWeightGrams = subsetWeightGrams,
        totalWeightGrams = totalWeightGrams,
        withdrawals = withdrawals,
    )
  }

  private fun germinationTest(
      testType: GerminationTestType = GerminationTestType.Lab,
      startDate: LocalDate? = january(1),
      seedsSown: Int? = null,
      germinations: List<GerminationModel>? = null,
  ): GerminationTestModel {
    return GerminationTestModel(
        accessionId = 1,
        germinations = germinations,
        id = germinationTestId++,
        seedsSown = seedsSown,
        startDate = startDate,
        testType = testType,
    )
  }

  private fun germination(
      recordingDate: LocalDate = january(2),
      seedsGerminated: Int = 1
  ): GerminationModel {
    return GerminationModel(
        id = germinationId++,
        testId = germinationTestId,
        recordingDate = recordingDate,
        seedsGerminated = seedsGerminated)
  }

  private fun withdrawal(
      seedsWithdrawn: Int = 1,
      date: LocalDate = january(3),
      gramsWithdrawn: BigDecimal? = null,
      purpose: WithdrawalPurpose = WithdrawalPurpose.Other,
  ): WithdrawalModel {
    return WithdrawalModel(
        accessionId = 1,
        date = date,
        gramsWithdrawn = gramsWithdrawn,
        id = withdrawalId++,
        purpose = purpose,
        seedsWithdrawn = seedsWithdrawn,
    )
  }

  private fun january(day: Int): LocalDate {
    return LocalDate.of(2021, 1, day)
  }

  @Test
  fun `test without seeds sown is ignored`() {
    val model =
        accession(
            listOf(
                germinationTest(
                    germinations =
                        listOf(
                            germination(recordingDate = january(2), seedsGerminated = 1),
                        ),
                ),
            ),
            seedsCounted = null,
        )

    assertNull(model.calculateLatestGerminationRecordingDate(), "Latest germination test date")
    assertNull(model.calculateLatestViabilityPercent(), "Latest viability percent")
    assertNull(model.calculateTotalViabilityPercent(), "Total viability percent")
  }

  @Test
  fun `test with most recent recording date is selected`() {
    val model =
        accession(
            listOf(
                germinationTest(
                    testType = GerminationTestType.Lab,
                    startDate = january(1),
                    seedsSown = 5,
                    germinations =
                        listOf(
                            germination(recordingDate = january(13), seedsGerminated = 1),
                        ),
                ),
                germinationTest(
                    testType = GerminationTestType.Lab,
                    startDate = january(3),
                    seedsSown = 4,
                    germinations =
                        listOf(
                            germination(recordingDate = january(14), seedsGerminated = 1),
                            germination(recordingDate = january(11), seedsGerminated = 1),
                        ),
                ),
                germinationTest(
                    testType = GerminationTestType.Lab,
                    startDate = january(8), // most recent start date
                    seedsSown = 3,
                    germinations =
                        listOf(
                            germination(recordingDate = january(12), seedsGerminated = 3),
                        ),
                ),
            ),
            seedsCounted = null,
        )

    assertEquals(
        january(14),
        model.calculateLatestGerminationRecordingDate(),
        "Latest germination test date")
    assertEquals(50, model.calculateLatestViabilityPercent(), "Latest viability percent")
  }

  @Test
  fun `total viability percentage includes all germination tests and cut test`() {
    val model =
        accession(
            germinationTests =
                listOf(
                    germinationTest(
                        seedsSown = 10,
                        germinations =
                            listOf(
                                germination(recordingDate = january(2), seedsGerminated = 4),
                                germination(recordingDate = january(3), seedsGerminated = 3),
                            ),
                    ),
                    germinationTest(
                        startDate = january(8),
                        seedsSown = 5,
                        germinations =
                            listOf(
                                germination(recordingDate = january(9), seedsGerminated = 1),
                            ),
                    ),
                ),
            cutTestSeedsCompromised = 2,
            cutTestSeedsEmpty = 1,
            cutTestSeedsFilled = 5,
            seedsCounted = null,
        )

    // 13 seeds viable of 23 tested
    val expectedPercentage = 56

    assertEquals(expectedPercentage, model.calculateTotalViabilityPercent())
  }

  @Test
  fun `total viability percentage not set if no test results`() {
    val model =
        accession(
            germinationTests = listOf(germinationTest(seedsSown = 10)),
            seedsCounted = null,
        )

    assertNull(model.calculateTotalViabilityPercent())
  }

  @Test
  fun `total viability percentage ignores cut test results if values are missing`() {
    val missingEmpty =
        accession(
            germinationTests =
                listOf(
                    germinationTest(
                        seedsSown = 10,
                        germinations =
                            listOf(
                                germination(recordingDate = january(2), seedsGerminated = 4),
                            ),
                    ),
                ),
            cutTestSeedsCompromised = 2,
            cutTestSeedsFilled = 5,
            seedsCounted = null,
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
        expectedPercentage, missingFilled.calculateTotalViabilityPercent(), "Filled count missing")
  }

  @Test
  fun `most recent recording date is used as test date`() {
    val model =
        accession(
            listOf(
                germinationTest(
                    seedsSown = 1,
                    germinations =
                        listOf(
                            germination(recordingDate = january(3), seedsGerminated = 1),
                            germination(recordingDate = january(4), seedsGerminated = 2),
                            germination(recordingDate = january(2), seedsGerminated = 3),
                        ),
                ),
            ),
        )

    assertEquals(january(4), model.calculateLatestGerminationRecordingDate())
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
                    subsetWeightGrams = subsetWeight,
                    totalWeightGrams = totalWeight)
            assertNull(accession.calculateEstimatedSeedCount(), "Estimated seed count: $values")
            assertNull(accession.calculateEffectiveSeedCount(), "Effective seed count: $values")
            assertNull(accession.calculateSeedsRemaining(), "Seeds remaining: $values")
          }
        }
      }
    }
  }

  @Test
  fun `seed count is calculated based on weight`() {
    val accession =
        accession(
            subsetCount = 10, subsetWeightGrams = BigDecimal.ONE, totalWeightGrams = BigDecimal.TEN)

    assertEquals(100, accession.calculateEstimatedSeedCount(), "Estimated seed count")
    assertEquals(100, accession.calculateEffectiveSeedCount(), "Effective seed count")
    assertEquals(100, accession.calculateSeedsRemaining(), "Seeds remaining")
  }

  @Test
  fun `explicit seed count takes precedence over weight calculation`() {
    val accession =
        accession(
            seedsCounted = 90,
            subsetCount = 10,
            subsetWeightGrams = BigDecimal.ONE,
            totalWeightGrams = BigDecimal.TEN)

    assertNull(accession.calculateEstimatedSeedCount(), "Estimated seed count")
    assertEquals(90, accession.calculateEffectiveSeedCount(), "Effective seed count")
    assertEquals(90, accession.calculateSeedsRemaining(), "Seeds remaining")
  }

  @Test
  fun `cut test seed count is subtracted from seeds remaining`() {
    val accession =
        accession(
            cutTestSeedsCompromised = 2,
            cutTestSeedsEmpty = 1,
            cutTestSeedsFilled = 1,
            seedsCounted = 10,
        )

    assertEquals(6, accession.calculateSeedsRemaining())
  }

  @Test
  fun `germination test seeds sown are subtracted from seeds remaining`() {
    val accession =
        accession(
            germinationTests =
                listOf(
                    germinationTest(seedsSown = 10),
                    germinationTest(seedsSown = 5),
                ),
            seedsCounted = 40,
        )

    assertEquals(25, accession.calculateSeedsRemaining())
  }

  @Test
  fun `withdrawn seeds are subtracted from seeds remaining`() {
    val accession =
        accession(
            seedsCounted = 100,
            withdrawals =
                listOf(
                    withdrawal(1),
                    withdrawal(5),
                ))

    assertEquals(94, accession.calculateSeedsRemaining())
  }

  @Test
  fun `withdrawals for germination testing are not subtracted from seeds remaining`() {
    val accession =
        accession(
            seedsCounted = 100,
            withdrawals =
                listOf(
                    withdrawal(1).copy(purpose = WithdrawalPurpose.GerminationTesting),
                    withdrawal(5),
                ))

    assertEquals(95, accession.calculateSeedsRemaining())
  }

  @Test
  fun `withdrawn seeds counted by weight are subtracted from seeds remaining`() {
    val accession =
        accession(
            subsetCount = 10,
            subsetWeightGrams = BigDecimal.ONE,
            totalWeightGrams = BigDecimal.TEN,
            withdrawals =
                listOf(
                    withdrawal(gramsWithdrawn = BigDecimal(2.5)),
                    withdrawal(gramsWithdrawn = BigDecimal(5)),
                ))

    assertEquals(25, accession.calculateSeedsRemaining())
  }

  @Test
  fun `calculateProcessingStartDate null if no seed count`() {
    assertNull(accession().calculateProcessingStartDate(clock))
  }

  @Test
  fun `calculateProcessingStartDate preserves existing processingStartDate`() {
    val accession = accession(processingStartDate = tomorrow, seedsCounted = 150)
    assertEquals(tomorrow, accession.calculateProcessingStartDate(clock))
  }

  @Test
  fun `calculateProcessingStartDate defaults to today if seeds counted`() {
    val accession = accession(seedsCounted = 150)
    assertEquals(today, accession.calculateProcessingStartDate(clock))
  }

  @TestFactory
  fun stateTransitionRules(): List<DynamicTest> {
    val tests = mutableListOf<DynamicTest>()

    fun AccessionModel.addStateTest(
        expectedState: AccessionState,
        displayName: String
    ): AccessionModel {
      tests.add(
          DynamicTest.dynamicTest("$expectedState: $displayName") {
            val transition = getStateTransition(this, clock)
            if (expectedState == state) {
              assertNull(transition, "Unexpected state transition $transition")
            } else {
              assertEquals(
                  expectedState, transition?.newState, "Unexpected state transition $transition")
            }
          })

      return this
    }

    // Start off with an accession that meets all the conditions, then peel them back in descending
    // order of precedence to make sure the highest-precedence rule gets applied.
    accession()
        .copy(
            nurseryStartDate = today,
            withdrawals = listOf(withdrawal(10)),
            storageLocation = "location",
            storagePackets = 10,
            storageStartDate = today,
            dryingEndDate = today,
            dryingStartDate = today,
            processingStartDate = today.minusDays(14),
            seedsCounted = 10,
        )
        .addStateTest(AccessionState.Nursery, "Nursery start date entered")
        .copy(nurseryStartDate = null)
        .addStateTest(AccessionState.Withdrawn, "All seeds marked as withdrawn")
        .copy(withdrawals = null)
        .addStateTest(AccessionState.InStorage, "Number of packets or location has been entered")
        .copy(storageLocation = null)
        .addStateTest(AccessionState.InStorage, "Number of packets or location has been entered")
        .copy(storagePackets = null)
        .addStateTest(AccessionState.InStorage, "Storage start date is today or earlier")
        .copy(storageStartDate = tomorrow)
        .addStateTest(AccessionState.Dried, "Drying end date is today or earlier")
        .copy(dryingEndDate = tomorrow)
        .addStateTest(AccessionState.Drying, "Drying start date is today or earlier")
        .copy(dryingStartDate = tomorrow)
        .addStateTest(AccessionState.Processed, "2 weeks have passed since processing start date")
        .copy(processingStartDate = null)
        .addStateTest(AccessionState.Processing, "Seed count/weight entered")
        .copy(seedsCounted = null)
        .addStateTest(AccessionState.Pending, "No state conditions applied")

    return tests
  }
}
