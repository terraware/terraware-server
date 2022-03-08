package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.GerminationId
import com.terraformation.backend.db.GerminationTestId
import com.terraformation.backend.db.GerminationTestType
import com.terraformation.backend.db.ProcessingMethod
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.seedbank.grams
import com.terraformation.backend.seedbank.milligrams
import com.terraformation.backend.seedbank.seeds
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

internal class AccessionModelTest {
  private val clock: Clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
  private val today = LocalDate.now(clock)
  private val tomorrow = today.plusDays(1)
  private val yesterday = today.minusDays(1)

  private var germinationId = GerminationId(1)
  private var germinationTestId = GerminationTestId(1)
  private var withdrawalId = WithdrawalId(1)
  private var defaultState = AccessionState.Processing

  private fun accession(
      germinationTests: List<GerminationTestModel> = emptyList(),
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
        accessionNumber = "dummy",
        cutTestSeedsEmpty = cutTestSeedsEmpty,
        cutTestSeedsFilled = cutTestSeedsFilled,
        cutTestSeedsCompromised = cutTestSeedsCompromised,
        dryingEndDate = dryingEndDate,
        dryingStartDate = dryingStartDate,
        germinationTests = germinationTests,
        id = AccessionId(1L),
        processingMethod = processingMethod,
        processingStartDate = processingStartDate,
        source = AccessionSource.Web,
        state = state,
        storageLocation = storageLocation,
        storagePackets = storagePackets,
        storageStartDate = storageStartDate,
        subsetCount = subsetCount,
        subsetWeightQuantity = subsetWeight,
        total = total,
        withdrawals = withdrawals,
    )
  }

  private fun nextGerminationTestId(): GerminationTestId {
    val current = germinationTestId
    germinationTestId = GerminationTestId(current.value + 1)
    return current
  }

  private fun germinationTest(
      testType: GerminationTestType = GerminationTestType.Lab,
      startDate: LocalDate? = january(1),
      seedsSown: Int? = null,
      germinations: List<GerminationModel>? = null,
      remaining: SeedQuantityModel? = null,
  ): GerminationTestModel {
    return GerminationTestModel(
        accessionId = AccessionId(1),
        germinations = germinations,
        id = nextGerminationTestId(),
        remaining = remaining,
        seedsSown = seedsSown,
        startDate = startDate,
        testType = testType,
    )
  }

  private fun nextGerminationId(): GerminationId {
    val current = germinationId
    germinationId = GerminationId(current.value + 1)
    return current
  }

  private fun germination(
      recordingDate: LocalDate = january(2),
      seedsGerminated: Int = 1
  ): GerminationModel {
    return GerminationModel(
        id = nextGerminationId(),
        testId = germinationTestId,
        recordingDate = recordingDate,
        seedsGerminated = seedsGerminated)
  }

  private fun nextWithdrawalId(): WithdrawalId {
    val current = withdrawalId
    withdrawalId = WithdrawalId(current.value + 1)
    return current
  }

  private fun withdrawal(
      withdrawn: SeedQuantityModel = seeds(1),
      date: LocalDate = january(3),
      germinationTestId: GerminationTestId? = null,
      purpose: WithdrawalPurpose =
          if (germinationTestId != null) WithdrawalPurpose.GerminationTesting
          else WithdrawalPurpose.Other,
      remaining: SeedQuantityModel =
          if (withdrawn.units == SeedQuantityUnits.Seeds) seeds(10) else grams(10),
  ): WithdrawalModel {
    return WithdrawalModel(
        accessionId = AccessionId(1),
        date = date,
        germinationTestId = germinationTestId,
        id = nextWithdrawalId(),
        purpose = purpose,
        remaining = remaining,
        withdrawn = withdrawn,
    )
  }

  private fun january(day: Int): LocalDate {
    return LocalDate.of(2021, 1, day)
  }

  @Nested
  inner class GerminationTestCalculations {
    @Test
    fun `test without seeds sown is ignored`() {
      val model =
          accession(
              total = seeds(10),
              germinationTests =
                  listOf(
                      germinationTest(
                          germinations =
                              listOf(
                                  germination(recordingDate = january(2), seedsGerminated = 1),
                              ),
                      ),
                  ),
          )

      assertNull(model.calculateLatestGerminationRecordingDate(), "Latest germination test date")
      assertNull(model.calculateLatestViabilityPercent(), "Latest viability percent")
      assertNull(model.calculateTotalViabilityPercent(), "Total viability percent")
    }

    @Test
    fun `test with most recent recording date is selected`() {
      val model =
          accession(
              total = seeds(100),
              germinationTests =
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
              total = seeds(30),
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
              germinationTests = listOf(germinationTest(seedsSown = 10)),
          )

      assertNull(model.calculateTotalViabilityPercent())
    }

    @Test
    fun `total viability percentage ignores cut test results if values are missing`() {
      val missingEmpty =
          accession(
              total = seeds(50),
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
              germinationTests =
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
  }

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
            germinationTests = listOf(germinationTest(seedsSown = 1)),
            withdrawals = listOf(withdrawal(withdrawn = seeds(1), date = tomorrow)))
      }
    }

    @Test
    fun `cannot specify negative seeds remaining for weight-based accessions`() {
      assertThrows<IllegalArgumentException>("germination tests") {
        accession(
            processingMethod = ProcessingMethod.Weight,
            total = grams(1),
            germinationTests = listOf(germinationTest(seedsSown = 1, remaining = grams(-1))))
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
  }

  @Nested
  inner class WeightBasedAccessionCalculations {
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
              assertNull(accession.calculateEstimatedSeedCount(), "Estimated seed count: $values")
            }
          }
        }
      }
    }

    @Test
    fun `estimated seed count is calculated based on weight`() {
      val accession = accession(subsetCount = 10, subsetWeight = milligrams(200), total = grams(1))

      assertEquals(50, accession.calculateEstimatedSeedCount())
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
              germinationTests =
                  listOf(
                      germinationTest(remaining = grams(99)),
                      germinationTest(remaining = grams(90)),
                      germinationTest(remaining = grams(96)),
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
    fun `calculateRemaining uses value from germination test if it is the lowest one`() {
      val accession =
          accession(
              total = grams(100),
              germinationTests =
                  listOf(
                      germinationTest(remaining = grams(99)),
                      germinationTest(remaining = grams(90)),
                      germinationTest(remaining = grams(96)),
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
      val germinationTest = germinationTest(remaining = grams(50))
      val accession =
          accession(
              total = grams(100),
              germinationTests = listOf(germinationTest),
              withdrawals =
                  listOf(withdrawal(germinationTestId = germinationTest.id, remaining = grams(70))))

      val withdrawals = accession.calculateWithdrawals(clock)

      assertEquals(grams<SeedQuantityModel>(50), withdrawals[0].remaining)
    }

    @Test
    fun `calculateWithdrawals rejects IDs not in caller-supplied withdrawal list`() {
      val accession = accession(total = grams(100), withdrawals = listOf(withdrawal(grams(1))))

      assertThrows<IllegalArgumentException> {
        accession.calculateWithdrawals(clock, listOf(withdrawal(grams(1))))
      }
    }

    @Test
    fun `calculateWithdrawals looks in caller-supplied withdrawal list for test withdrawals`() {
      val germinationTest = germinationTest(remaining = grams(95))
      val existingWithdrawalForTest = withdrawal(grams(5), germinationTestId = germinationTest.id)
      val otherExistingWithdrawal = withdrawal(grams(1))

      val accession =
          accession(
              total = grams(100),
              germinationTests = listOf(germinationTest),
              withdrawals =
                  listOf(
                      otherExistingWithdrawal.copy(
                          germinationTestId = germinationTest.id,
                          purpose = WithdrawalPurpose.GerminationTesting)))

      val withdrawals =
          accession.calculateWithdrawals(
              clock, listOf(existingWithdrawalForTest, otherExistingWithdrawal))
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
          germinationTest(seedsSown = 8, startDate = tomorrow, remaining = grams(85))
      private val futureTestWithdrawal =
          withdrawal(
              grams(8),
              date = tomorrow,
              germinationTestId = futureTest.id,
              purpose = WithdrawalPurpose.GerminationTesting,
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
                germinationTests = listOf(futureTest),
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
      fun `calculateTotalPastWithdrawalQuantity includes withdrawals and germination tests`() {
        val accession =
            accession(
                total = grams(100),
                germinationTests =
                    listOf(
                        germinationTest(seedsSown = 1, startDate = today, remaining = grams(96))),
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
                germinationTests = listOf(futureTest),
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
                germinationTests = listOf(futureTest),
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
                germinationTests = listOf(futureTest),
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
    fun `germination test seeds sown are subtracted from seeds remaining`() {
      val accession =
          accession(
              germinationTests =
                  listOf(
                      germinationTest(seedsSown = 10),
                      germinationTest(seedsSown = 5),
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
    fun `withdrawals for germination testing without corresponding tests are not subtracted from seeds remaining`() {
      val accession =
          accession(
              total = seeds(100),
              withdrawals =
                  listOf(
                      withdrawal(
                          seeds(1),
                          remaining = seeds(0),
                          purpose = WithdrawalPurpose.GerminationTesting),
                      withdrawal(seeds(5), remaining = seeds(0)),
                  ))

      assertEquals(seeds(95), accession.calculateRemaining(clock))
    }

    @Test
    fun `calculateWithdrawals updates seeds remaining on tests`() {
      val testWithExistingWithdrawal = germinationTest(seedsSown = 4, startDate = january(2))
      val accession =
          accession(
              total = seeds(100),
              germinationTests =
                  listOf(
                      germinationTest(seedsSown = 8, startDate = january(4)),
                      testWithExistingWithdrawal,
                  ),
              withdrawals =
                  listOf(
                      WithdrawalModel(
                          accessionId = AccessionId(1),
                          date = january(15), // different from test date
                          germinationTestId = testWithExistingWithdrawal.id,
                          id = nextWithdrawalId(),
                          purpose = WithdrawalPurpose.GerminationTesting,
                          remaining = seeds(0),
                          withdrawn = seeds(testWithExistingWithdrawal.seedsSown!!),
                      ),
                      withdrawal(seeds(2), remaining = seeds(0), date = january(3)),
                  ),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(
          listOf<SeedQuantityModel>(seeds(96), seeds(86)),
          withdrawals.mapNotNull { it.germinationTest?.remaining })
    }

    @Test
    fun `calculateWithdrawals walks tests and withdrawals in time order`() {
      val accession =
          accession(
              total = seeds(100),
              germinationTests =
                  listOf(
                      germinationTest(seedsSown = 4, startDate = january(2)),
                      germinationTest(seedsSown = 8, startDate = january(4)),
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
      val test = germinationTest(seedsSown = 1, startDate = null)
      val accession =
          accession(
              total = seeds(100),
              germinationTests = listOf(test),
              withdrawals =
                  listOf(withdrawal(seeds(1), date = january(15), germinationTestId = test.id)),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(january(15), withdrawals[0].date)
    }

    @Test
    fun `calculateWithdrawals updates withdrawal date based on updated test date`() {
      val test = germinationTest(seedsSown = 1, startDate = january(5))
      val accession =
          accession(
              total = seeds(100),
              germinationTests = listOf(test),
              withdrawals =
                  listOf(withdrawal(seeds(1), date = january(15), germinationTestId = test.id)),
          )

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(
          january(5), withdrawals[0].date, "Withdrawal date should be copied from test date")
      assertEquals(
          accession.withdrawals[0].id, withdrawals[0].id, "Should update existing test withdrawal")
    }

    @Test
    fun `calculateWithdrawals generates withdrawals for tests without seedsSown values`() {
      val accession =
          accession(
              total = seeds(100), germinationTests = listOf(germinationTest(seedsSown = null)))

      val withdrawals = accession.calculateWithdrawals(clock)
      assertEquals(1, withdrawals.size, "Number of generated withdrawals")
      assertEquals(
          accession.germinationTests[0].id,
          withdrawals[0].germinationTestId,
          "Withdrawal should refer to germination test")
      assertNull(withdrawals[0].withdrawn, "Withdrawal amount")
    }

    @Nested
    inner class TimeBasedCalculations {
      private val germinationTest = germinationTest(seedsSown = 8, startDate = tomorrow)

      private val accession =
          accession(
              total = seeds(100),
              germinationTests = listOf(germinationTest),
              withdrawals =
                  listOf(
                      withdrawal(seeds(1), date = yesterday),
                      withdrawal(seeds(2), date = today),
                      withdrawal(seeds(4), date = tomorrow),
                      withdrawal(
                          seeds(8), date = tomorrow, germinationTestId = germinationTest.id)))

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

      // Start off with an accession that meets all the conditions, then peel them back in
      // descending
      // order of precedence to make sure the highest-precedence rule gets applied.
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
          .addStateTest(AccessionState.Nursery, "Nursery start date entered")
          .copy(nurseryStartDate = null)
          .addStateTest(AccessionState.Withdrawn, "All seeds marked as withdrawn")
          .copy(withdrawals = emptyList())
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
          .copy(total = null, processingMethod = null)
          .addStateTest(AccessionState.Pending, "Checked-in time entered")
          .copy(checkedInTime = null)
          .addStateTest(AccessionState.AwaitingCheckIn, "No state conditions applied")

      return tests
    }
  }
}
