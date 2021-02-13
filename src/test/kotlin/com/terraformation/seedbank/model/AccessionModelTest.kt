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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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

  @Nested
  inner class StateTransitions {
    @Nested
    inner class FromPending {
      @BeforeEach
      fun setDefaultState() {
        defaultState = AccessionState.Pending
      }

      @Test
      fun `no transition when start dates entered`() {
        val accession =
            accession(
                dryingEndDate = today,
                dryingStartDate = today,
                processingStartDate = today,
                storageStartDate = today)
        assertNoStateTransition(accession)
      }

      @Test
      fun `no transition to Processing when weight-based seed count is incomplete`() {
        listOf(1, null).forEach { subsetCount ->
          listOf(BigDecimal.ONE, null).forEach { subsetWeight ->
            listOf(BigDecimal.TEN, null).forEach { totalWeight ->
              if (subsetCount == null || subsetWeight == null || totalWeight == null) {
                assertNoStateTransition(
                    accession(
                        subsetCount = subsetCount,
                        subsetWeightGrams = subsetWeight,
                        totalWeightGrams = totalWeight))
              }
            }
          }
        }
      }

      @Test
      fun `transitions to Processing when weight-based seed count is entered`() {
        val accession =
            accession(
                subsetCount = 1,
                subsetWeightGrams = BigDecimal.ONE,
                totalWeightGrams = BigDecimal.TEN)
        assertStateTransition(accession, AccessionState.Processing)
      }

      @Test
      fun `transitions to Processing when exact seed count is entered`() {
        val accession = accession(seedsCounted = 10)
        assertStateTransition(accession, AccessionState.Processing)
      }
    }

    @Nested
    inner class FromProcessing {
      @BeforeEach
      fun setDefaultState() {
        defaultState = AccessionState.Processing
      }

      @Test
      fun `no transition when drying start date is in the future`() {
        assertNoStateTransition(accession(dryingStartDate = tomorrow))
      }

      @Test
      fun `no transition based on drying end date or storage start date`() {
        assertNoStateTransition(accession(dryingEndDate = today, storageStartDate = today))
      }

      @Test
      fun `transitions to Drying when drying start date arrives`() {
        assertStateTransition(accession(dryingStartDate = today), AccessionState.Drying)
      }

      @Test
      fun `transitions to Withdrawn when no seeds remaining`() {
        assertStateTransition(
            accession(seedsCounted = 10, withdrawals = listOf(withdrawal(10))),
            AccessionState.Withdrawn)
      }

      @Test
      fun `state remains in Processing until 2 weeks have passed`() {
        assertNoStateTransition(accession(processingStartDate = today.minusDays(13)))
      }

      @Test
      fun `state transitions from Processing to Processed after 2 weeks`() {
        assertStateTransition(
            accession(processingStartDate = today.minusDays(14)), AccessionState.Processed)
      }
    }

    @Nested
    inner class FromProcessed {
      @BeforeEach
      fun setDefaultState() {
        defaultState = AccessionState.Processed
      }

      @Test
      fun `transitions to Drying when drying start date arrives`() {
        assertStateTransition(accession(dryingStartDate = today), AccessionState.Drying)
      }

      @Test
      fun `transitions to Withdrawn when no seeds remaining`() {
        assertStateTransition(
            accession(seedsCounted = 10, withdrawals = listOf(withdrawal(10))),
            AccessionState.Withdrawn)
      }

      @Test
      fun `no transition based on drying end date or storage start date`() {
        assertNoStateTransition(accession(dryingEndDate = today, storageStartDate = today))
      }
    }

    @Nested
    inner class FromDrying {
      @BeforeEach
      fun setDefaultState() {
        defaultState = AccessionState.Drying
      }

      @Test
      fun `transitions to Dried when drying end date arrives`() {
        assertStateTransition(accession(dryingEndDate = today), AccessionState.Dried)
      }

      @Test
      fun `no transition when drying end date is in the future`() {
        assertNoStateTransition(accession(dryingEndDate = tomorrow))
      }

      @Test
      fun `no transition when storage start date arrives but no drying end date entered`() {
        assertNoStateTransition(accession(storageStartDate = today))
      }

      @Test
      fun `no transition when storage and drying start dates are in the future`() {
        assertNoStateTransition(accession(dryingEndDate = tomorrow, storageStartDate = tomorrow))
      }

      @Test
      fun `transitions to InStorage when both drying end date and storage start date have arrived`() {
        assertStateTransition(
            accession(dryingEndDate = today, storageStartDate = today), AccessionState.InStorage)
      }

      @Test
      fun `no transition when number of packets and storage location entered without drying end date`() {
        assertNoStateTransition(accession(storageLocation = "location", storagePackets = 1))
      }

      @Test
      fun `transitions to InStorage when number of packets is entered`() {
        assertStateTransition(
            accession(dryingEndDate = today, storagePackets = 1), AccessionState.InStorage)
      }

      @Test
      fun `transitions to InStorage when storage location is entered`() {
        assertStateTransition(
            accession(dryingEndDate = today, storageLocation = "location"),
            AccessionState.InStorage)
      }

      @Test
      fun `transitions to Withdrawn when no seeds remaining`() {
        assertStateTransition(
            accession(seedsCounted = 10, withdrawals = listOf(withdrawal(10))),
            AccessionState.Withdrawn)
      }
    }

    @Nested
    inner class FromDried {
      @BeforeEach
      fun setDefaultState() {
        defaultState = AccessionState.Dried
      }

      @Test
      fun `transitions to InStorage when storage start date arrives`() {
        assertStateTransition(accession(storageStartDate = today), AccessionState.InStorage)
      }

      @Test
      fun `no transition when storage start date is in the future`() {
        assertNoStateTransition(accession(storageStartDate = tomorrow))
      }

      @Test
      fun `transitions to InStorage when number of packets is entered`() {
        assertStateTransition(accession(storagePackets = 1), AccessionState.InStorage)
      }

      @Test
      fun `transitions to InStorage when storage location is entered`() {
        assertStateTransition(accession(storageLocation = "location"), AccessionState.InStorage)
      }

      @Test
      fun `transitions to Withdrawn when no seeds remaining`() {
        assertStateTransition(
            accession(seedsCounted = 10, withdrawals = listOf(withdrawal(10))),
            AccessionState.Withdrawn)
      }
    }

    @Nested
    inner class FromInStorage {
      @BeforeEach
      fun setDefaultState() {
        defaultState = AccessionState.InStorage
      }

      @Test
      fun `transitions to Withdrawn when no seeds remaining`() {
        assertStateTransition(
            accession(seedsCounted = 10, withdrawals = listOf(withdrawal(10))),
            AccessionState.Withdrawn)
      }
    }

    @Nested
    inner class FromWithdrawn {
      @BeforeEach
      fun setDefaultState() {
        defaultState = AccessionState.Withdrawn
      }

      @Test
      fun `adjustment to previous withdrawal does not revert state`() {
        assertNoStateTransition(accession(seedsCounted = 10, withdrawals = listOf(withdrawal(9))))
      }
    }

    private fun assertStateTransition(accession: AccessionModel, state: AccessionState) {
      assertEquals(state, accession.getStateTransition(accession, clock)?.newState)
    }

    private fun assertNoStateTransition(accession: AccessionModel) {
      assertNull(accession.getStateTransition(accession, clock))
    }
  }
}
